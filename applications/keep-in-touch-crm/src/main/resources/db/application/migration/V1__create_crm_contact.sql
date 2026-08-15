DO $$
BEGIN
    IF to_regclass('kernel.kernel_runtime_marker') IS NULL THEN
        RAISE EXCEPTION 'Kernel migrations must run first';
    END IF;
END $$;

CREATE TABLE crm_contact (
    id uuid PRIMARY KEY,
    display_name text NOT NULL
);
