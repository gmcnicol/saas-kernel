package io.github.gmcnicol.kernel.contract;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Shared workflow-test properties; fixtures retain their own containers and domain setup. */
public final class WorkflowTestDatabase {
    private WorkflowTestDatabase() {}

    public static void register(
            BiConsumer<String, Supplier<Object>> properties,
            Supplier<String> jdbcUrl, Supplier<String> migrationUser, Supplier<String> migrationPassword) {
        properties.accept("spring.datasource.url", jdbcUrl::get);
        properties.accept("spring.datasource.username", () -> "kernel_test_login");
        properties.accept("spring.datasource.password", () -> "kernel-test");
        properties.accept("spring.flyway.url", jdbcUrl::get);
        properties.accept("spring.flyway.user", migrationUser::get);
        properties.accept("spring.flyway.password", migrationPassword::get);
        properties.accept("kernel.intent-worker.enabled", () -> "false");
    }
}
