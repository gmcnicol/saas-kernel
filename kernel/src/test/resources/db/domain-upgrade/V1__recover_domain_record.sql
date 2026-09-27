SET LOCAL row_security = off;
CREATE TABLE recovered_domain_record (name text NOT NULL);
INSERT INTO recovered_domain_record
SELECT payload_content::jsonb ->> 'name' FROM kernel.typed_intent WHERE tenant_id = 'legacy' AND status = 'SUCCEEDED';
