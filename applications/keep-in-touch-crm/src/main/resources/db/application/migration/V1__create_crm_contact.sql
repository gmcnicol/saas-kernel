DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM kernel.flyway_kernel_schema_history
        WHERE version = '1' AND success
    ) THEN
        RAISE EXCEPTION 'Kernel migrations must run first';
    END IF;
END $$;

CREATE TABLE crm_contact (
    id uuid PRIMARY KEY,
    display_name text NOT NULL
);
