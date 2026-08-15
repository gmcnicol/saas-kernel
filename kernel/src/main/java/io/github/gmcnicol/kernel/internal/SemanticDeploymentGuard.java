package io.github.gmcnicol.kernel.internal;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.context.SmartLifecycle;

/** Keeps mixed Semantic Pack versions from becoming ready together. */
final class SemanticDeploymentGuard implements SmartLifecycle {

    private final DataSource dataSource;
    private final String applicationId;
    private final String checksum;
    private long lockKey;
    private final Runnable sessionLost;
    private Connection connection;
    private volatile boolean running;

    SemanticDeploymentGuard(DataSource dataSource, String applicationId, String checksum) {
        this(dataSource, applicationId, checksum, () -> { });
    }

    SemanticDeploymentGuard(DataSource dataSource, String applicationId, String checksum, Runnable sessionLost) {
        this.dataSource = dataSource;
        this.applicationId = applicationId;
        this.checksum = checksum;
        this.sessionLost = sessionLost;
    }

    @Override
    public synchronized void start() {
        if (running) return;
        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(true);
            try (var statement = connection.prepareStatement("SELECT hashtextextended(?, 0)")) {
                statement.setString(1, applicationId);
                try (var result = statement.executeQuery()) {
                    if (!result.next()) throw new SQLException("Cannot derive deployment lock key");
                    lockKey = result.getLong(1);
                }
            }
            if (queryBoolean("SELECT pg_try_advisory_lock(?)")) {
                try {
                    try (var statement = connection.prepareStatement(
                            "SELECT kernel.set_application_semantic_deployment(?, ?)")) {
                        statement.setString(1, applicationId);
                        statement.setString(2, checksum);
                        statement.execute();
                    }
                    execute("SELECT pg_advisory_lock_shared(?)");
                } finally {
                    queryBoolean("SELECT pg_advisory_unlock(?)");
                }
            } else {
                execute("SELECT pg_advisory_lock_shared(?)");
                try (var statement = connection.prepareStatement("""
                        SELECT semantic_pack_checksum
                        FROM kernel.application_semantic_deployment
                        WHERE application_id = ?
                        """)) {
                    statement.setString(1, applicationId);
                    try (var result = statement.executeQuery()) {
                        if (!result.next() || !checksum.equals(result.getString(1))) {
                            queryBoolean("SELECT pg_advisory_unlock_shared(?)");
                            throw new IllegalStateException(
                                    "Semantic Pack changed while an older Application process is running");
                        }
                    }
                }
            }
            running = true;
        } catch (SQLException | RuntimeException exception) {
            close();
            throw new IllegalStateException("Cannot establish Semantic Pack deployment guard", exception);
        }
    }

    @Override
    public synchronized void stop() {
        if (connection != null) {
            try {
                queryBoolean("SELECT pg_advisory_unlock_shared(?)");
            } catch (SQLException ignored) {
                // Closing the session releases the lock.
            }
        }
        close();
        running = false;
    }

    @Override
    public synchronized boolean isRunning() {
        if (!running || connection == null) return false;
        try (var statement = connection.prepareStatement("""
                SELECT semantic_pack_checksum
                FROM kernel.application_semantic_deployment
                WHERE application_id = ?
                """)) {
            statement.setString(1, applicationId);
            try (var result = statement.executeQuery()) {
                if (result.next() && checksum.equals(result.getString(1))) return true;
            }
        } catch (SQLException exception) {
            // Lost sessions release their advisory lock.
        }
        running = false;
        close();
        sessionLost.run();
        return false;
    }

    private boolean queryBoolean(String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, lockKey);
            try (var result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private void execute(String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, lockKey);
            statement.execute();
        }
    }

    private void close() {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Nothing else can recover a broken session.
        } finally {
            connection = null;
        }
    }
}
