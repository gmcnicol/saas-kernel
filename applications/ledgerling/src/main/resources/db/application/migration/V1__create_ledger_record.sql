DO $$
BEGIN
    IF to_regclass('kernel.kernel_runtime_marker') IS NULL THEN
        RAISE EXCEPTION 'Kernel migrations must run first';
    END IF;
END $$;

CREATE TABLE ledger_record (
    id uuid PRIMARY KEY,
    reference text NOT NULL
);
