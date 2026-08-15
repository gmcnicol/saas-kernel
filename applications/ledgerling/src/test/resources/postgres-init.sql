CREATE ROLE kernel_runtime NOLOGIN NOBYPASSRLS;
CREATE ROLE kernel_worker NOLOGIN NOBYPASSRLS;
CREATE ROLE kernel_test_login LOGIN PASSWORD 'kernel-test' NOSUPERUSER NOBYPASSRLS;
GRANT kernel_runtime TO kernel_test_login;
