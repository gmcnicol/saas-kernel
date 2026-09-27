-- Stop all old processes before this upgrade. Never discard accepted, unfinished work.
SET LOCAL row_security = off;
LOCK TABLE kernel.typed_intent IN ACCESS EXCLUSIVE MODE;
DO $$
BEGIN
    IF EXISTS (SELECT FROM kernel.typed_intent WHERE status IN ('PENDING', 'CLAIMED', 'RETRY_WAIT')) THEN
        RAISE EXCEPTION 'Drain pending Intents with the previous Application before removing Kernel payload storage';
    END IF;
END $$;

-- Forward-only removal. Domain records are untouched. These columns must not be recreated.
ALTER TABLE kernel.typed_projected_state DROP COLUMN content;
DROP TABLE kernel.typed_evaluation_fact;
ALTER TABLE kernel.typed_intent DROP COLUMN payload_content;
ALTER TABLE kernel.typed_event DROP COLUMN event_content, DROP COLUMN projection_content;
