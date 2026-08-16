-- Kernel schema and history table are created by Flyway before this migration runs.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'kernel_runtime') THEN
        CREATE ROLE kernel_runtime NOLOGIN NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'kernel_worker') THEN
        CREATE ROLE kernel_worker NOLOGIN NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'kernel_offer_resolver') THEN
        CREATE ROLE kernel_offer_resolver NOLOGIN NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'kernel_intent_claimer') THEN
        CREATE ROLE kernel_intent_claimer NOLOGIN NOBYPASSRLS;
    END IF;
END $$;

ALTER ROLE kernel_runtime NOLOGIN NOSUPERUSER NOBYPASSRLS;
ALTER ROLE kernel_worker NOLOGIN NOSUPERUSER NOBYPASSRLS;
ALTER ROLE kernel_offer_resolver NOLOGIN NOSUPERUSER NOBYPASSRLS;
ALTER ROLE kernel_intent_claimer NOLOGIN NOSUPERUSER NOBYPASSRLS;

GRANT USAGE ON SCHEMA kernel
    TO kernel_runtime, kernel_worker, kernel_offer_resolver, kernel_intent_claimer;
