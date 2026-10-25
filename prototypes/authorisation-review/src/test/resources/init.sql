CREATE ROLE review_runtime NOLOGIN NOINHERIT NOBYPASSRLS;
CREATE ROLE review_login LOGIN PASSWORD 'review' NOINHERIT NOBYPASSRLS;
GRANT review_runtime TO review_login;

CREATE TABLE contact (tenant text, network text, id text, person text, revision bigint,
                      display_name text, email text, private_note text);
CREATE TABLE filing (tenant text, practice text, id text, client text, revision bigint,
                     reference text, working_note text);
ALTER TABLE contact ENABLE ROW LEVEL SECURITY;
ALTER TABLE contact FORCE ROW LEVEL SECURITY;
ALTER TABLE filing ENABLE ROW LEVEL SECURITY;
ALTER TABLE filing FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_contact ON contact TO review_runtime
    USING (tenant = current_setting('review.tenant', true));
CREATE POLICY tenant_filing ON filing TO review_runtime
    USING (tenant = current_setting('review.tenant', true));
GRANT SELECT ON contact, filing TO review_runtime;

-- The same person in two Networks is intentionally NOT the resource identity.
INSERT INTO contact VALUES
 ('t1', 'network-a', 'contact-a', 'same-human', 1, 'Ada', 'ada@private.test', 'medical-secret'),
 ('t1', 'network-b', 'contact-b', 'same-human', 1, 'Hidden Network', 'hidden@private.test', 'other-network-secret'),
 ('t2', 'network-a', 'contact-a', 'same-human', 1, 'Other tenant', 'tenant@private.test', 'tenant-secret');
INSERT INTO filing VALUES
 ('t1', 'practice-a', 'filing-a', 'client-a', 1, 'REF-A', 'staff-working-note-A'),
 ('t1', 'practice-a', 'filing-b', 'client-b', 1, 'REF-B', 'staff-working-note-B'),
 ('t2', 'practice-a', 'filing-a', 'client-a', 1, 'OTHER-TENANT', 'tenant-working-note');
