#!/usr/bin/env python3
"""PROTOTYPE: probe the minimum durable Intent and Event state model."""

import hashlib
import json
import sqlite3
import sys
from pathlib import Path


DB = Path("/tmp/saas-kernel-PROTOTYPE-intent-event.sqlite3")
TERMINAL = {"SUCCEEDED", "STALE", "FAILED"}


def connect(reset=False):
    if reset:
        DB.unlink(missing_ok=True)
    db = sqlite3.connect(DB)
    db.row_factory = sqlite3.Row
    db.executescript(
        """
        PRAGMA foreign_keys = ON;
        CREATE TABLE IF NOT EXISTS projected_state (
            subject_id TEXT PRIMARY KEY,
            version INTEGER NOT NULL,
            value TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS intent (
            intent_id TEXT PRIMARY KEY,
            subject_id TEXT NOT NULL,
            action_id TEXT NOT NULL,
            action_offer_id TEXT NOT NULL,
            evaluation_snapshot_id TEXT NOT NULL,
            principal_id TEXT NOT NULL,
            pack_id TEXT NOT NULL,
            pack_version TEXT NOT NULL,
            pack_checksum TEXT NOT NULL,
            payload_type TEXT NOT NULL,
            payload_version INTEGER NOT NULL,
            payload TEXT NOT NULL,
            envelope_hash TEXT NOT NULL,
            prior_intent_id TEXT REFERENCES intent(intent_id),
            expected_state_version INTEGER NOT NULL,
            accepted_at INTEGER NOT NULL,
            status TEXT NOT NULL CHECK (status IN
                ('PENDING', 'CLAIMED', 'RETRY_WAIT', 'SUCCEEDED', 'STALE', 'FAILED')),
            attempt_count INTEGER NOT NULL DEFAULT 0,
            next_attempt_at INTEGER,
            lease_token TEXT,
            lease_until INTEGER,
            last_error TEXT,
            completed_at INTEGER
        );
        CREATE TABLE IF NOT EXISTS intent_audit (
            audit_id INTEGER PRIMARY KEY AUTOINCREMENT,
            intent_id TEXT NOT NULL REFERENCES intent(intent_id),
            from_status TEXT,
            to_status TEXT NOT NULL,
            at INTEGER NOT NULL,
            reason TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS domain_event (
            event_id TEXT PRIMARY KEY,
            intent_id TEXT NOT NULL REFERENCES intent(intent_id),
            sequence INTEGER NOT NULL,
            subject_id TEXT NOT NULL,
            event_type TEXT NOT NULL,
            pack_id TEXT NOT NULL,
            pack_version TEXT NOT NULL,
            pack_checksum TEXT NOT NULL,
            payload_version INTEGER NOT NULL,
            payload TEXT NOT NULL,
            occurred_at INTEGER NOT NULL,
            state_version INTEGER NOT NULL,
            UNIQUE (intent_id, sequence),
            UNIQUE (subject_id, state_version)
        );
        CREATE TABLE IF NOT EXISTS reevaluation_request (
            subject_id TEXT PRIMARY KEY,
            expected_state_version INTEGER NOT NULL,
            pack_id TEXT NOT NULL,
            pack_version TEXT NOT NULL,
            due_at INTEGER NOT NULL
        );
        """
    )
    return db


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"))


def audit(db, intent_id, before, after, now, reason):
    db.execute(
        "INSERT INTO intent_audit(intent_id, from_status, to_status, at, reason) VALUES (?, ?, ?, ?, ?)",
        (intent_id, before, after, now, reason),
    )


def seed(db, subject_id="contact:1", version=7, value=None):
    value = value or {"followUp": "due", "interactions": 2}
    db.execute(
        "INSERT OR REPLACE INTO projected_state VALUES (?, ?, ?)",
        (subject_id, version, canonical(value)),
    )
    db.commit()


def accept(db, intent_id, expected_version=7, payload=None, now=100):
    payload = payload or {"note": "Spoke to Ada"}
    envelope = {
        "subject_id": "contact:1",
        "action_id": "crm.RecordInteraction",
        "action_offer_id": "offer:44",
        "evaluation_snapshot_id": "snapshot:7",
        "principal_id": "user:gareth",
        "pack_id": "crm",
        "pack_version": "1.0.0",
        "pack_checksum": "sha256:crm-pack-1",
        "payload_type": "crm.RecordInteractionInput",
        "payload_version": 1,
        "payload": payload,
        "expected_state_version": expected_version,
    }
    digest = hashlib.sha256(canonical(envelope).encode()).hexdigest()
    existing = db.execute("SELECT envelope_hash FROM intent WHERE intent_id = ?", (intent_id,)).fetchone()
    if existing:
        if existing["envelope_hash"] != digest:
            raise ValueError("Intent ID reused with different envelope")
        return "existing"
    current = db.execute(
        "SELECT version FROM projected_state WHERE subject_id = ?", (envelope["subject_id"],)
    ).fetchone()
    if not current or current["version"] != expected_version:
        raise ValueError("Action Offer is no longer current")
    with db:
        db.execute(
            """INSERT INTO intent VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, 'PENDING', 0,
                NULL, NULL, NULL, NULL, NULL
            )""",
            (
                intent_id,
                envelope["subject_id"],
                envelope["action_id"],
                envelope["action_offer_id"],
                envelope["evaluation_snapshot_id"],
                envelope["principal_id"],
                envelope["pack_id"],
                envelope["pack_version"],
                envelope["pack_checksum"],
                envelope["payload_type"],
                envelope["payload_version"],
                canonical(payload),
                digest,
                expected_version,
                now,
            ),
        )
        audit(db, intent_id, None, "PENDING", now, "accepted")
    return "accepted"


def claim(db, intent_id, token, now=110, lease_seconds=30):
    row = db.execute("SELECT * FROM intent WHERE intent_id = ?", (intent_id,)).fetchone()
    claimable = row and (
        row["status"] == "PENDING"
        or (row["status"] == "RETRY_WAIT" and row["next_attempt_at"] <= now)
        or (row["status"] == "CLAIMED" and row["lease_until"] <= now)
    )
    if not claimable:
        return False
    with db:
        changed = db.execute(
            """UPDATE intent
               SET status = 'CLAIMED', attempt_count = attempt_count + 1,
                   lease_token = ?, lease_until = ?, next_attempt_at = NULL
               WHERE intent_id = ? AND status = ?
                   AND COALESCE(lease_until, 0) = COALESCE(?, 0)
                   AND COALESCE(next_attempt_at, 0) = COALESCE(?, 0)""",
            (token, now + lease_seconds, intent_id, row["status"], row["lease_until"], row["next_attempt_at"]),
        ).rowcount
        if changed:
            audit(db, intent_id, row["status"], "CLAIMED", now, f"lease:{token}")
    return bool(changed)


def fail(db, intent_id, token, error, now, max_attempts=3):
    row = db.execute("SELECT * FROM intent WHERE intent_id = ?", (intent_id,)).fetchone()
    if not row or row["status"] != "CLAIMED" or row["lease_token"] != token:
        return False
    after = "FAILED" if row["attempt_count"] >= max_attempts else "RETRY_WAIT"
    with db:
        db.execute(
            """UPDATE intent SET status = ?, next_attempt_at = ?, lease_token = NULL,
                   lease_until = NULL, last_error = ?, completed_at = ? WHERE intent_id = ?""",
            (after, None if after == "FAILED" else now + 10, error, now if after == "FAILED" else None, intent_id),
        )
        audit(db, intent_id, "CLAIMED", after, now, error)
    return True


def succeed(db, intent_id, token, events, now=120):
    if not events:
        raise ValueError("Successful handling must emit at least one Event")
    db.execute("BEGIN IMMEDIATE")
    try:
        row = db.execute("SELECT * FROM intent WHERE intent_id = ?", (intent_id,)).fetchone()
        if not row or row["status"] != "CLAIMED" or row["lease_token"] != token:
            db.rollback()
            return False
        current = db.execute(
            "SELECT * FROM projected_state WHERE subject_id = ?", (row["subject_id"],)
        ).fetchone()
        if current["version"] != row["expected_state_version"]:
            db.execute(
                """UPDATE intent SET status = 'STALE', lease_token = NULL, lease_until = NULL,
                       completed_at = ? WHERE intent_id = ?""",
                (now, intent_id),
            )
            audit(db, intent_id, "CLAIMED", "STALE", now, "Projected State version changed")
            db.commit()
            return "stale"
        state = json.loads(current["value"])
        for sequence, event in enumerate(events, 1):
            version = current["version"] + sequence
            db.execute(
                "INSERT INTO domain_event VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (
                    f"{intent_id}:event:{sequence}", intent_id, sequence, row["subject_id"],
                    event["type"], row["pack_id"], row["pack_version"], row["pack_checksum"],
                    event.get("payload_version", 1), canonical(event["payload"]), now, version,
                ),
            )
            state.update(event["payload"])
        final_version = current["version"] + len(events)
        db.execute(
            "UPDATE projected_state SET version = ?, value = ? WHERE subject_id = ? AND version = ?",
            (final_version, canonical(state), row["subject_id"], current["version"]),
        )
        db.execute(
            "INSERT OR REPLACE INTO reevaluation_request VALUES (?, ?, ?, ?, ?)",
            (row["subject_id"], final_version, row["pack_id"], row["pack_version"], now),
        )
        db.execute(
            """UPDATE intent SET status = 'SUCCEEDED', lease_token = NULL, lease_until = NULL,
                   completed_at = ? WHERE intent_id = ?""",
            (now, intent_id),
        )
        audit(db, intent_id, "CLAIMED", "SUCCEEDED", now, f"{len(events)} Event(s) committed")
        db.commit()
        return "succeeded"
    except Exception:
        db.rollback()
        raise


def show(db, label):
    print(f"\n=== {label} ===")
    for table in ("projected_state", "intent", "intent_audit", "domain_event", "reevaluation_request"):
        rows = [dict(row) for row in db.execute(f"SELECT * FROM {table}")]
        print(f"{table}: {json.dumps(rows, indent=2)}")


def demo():
    db = connect(reset=True)
    seed(db)
    assert accept(db, "intent:happy") == "accepted"
    assert accept(db, "intent:happy") == "existing"
    assert claim(db, "intent:happy", "worker:a")
    assert not claim(db, "intent:happy", "worker:b")
    assert succeed(
        db,
        "intent:happy",
        "worker:a",
        [{"type": "crm.InteractionRecorded", "payload": {"followUp": "complete", "interactions": 3}}],
    ) == "succeeded"
    show(db, "duplicate acceptance and atomic success")

    db = connect(reset=True)
    seed(db)
    accept(db, "intent:stale")
    claim(db, "intent:stale", "worker:a")
    db.execute("UPDATE projected_state SET version = 8 WHERE subject_id = 'contact:1'")
    db.commit()
    assert succeed(db, "intent:stale", "worker:a", [{"type": "ignored", "payload": {}}]) == "stale"
    show(db, "stale Intent emits no Event")

    db = connect(reset=True)
    seed(db)
    accept(db, "intent:retry")
    for attempt, now in enumerate((110, 130, 150), 1):
        token = f"worker:{attempt}"
        assert claim(db, "intent:retry", token, now)
        assert fail(db, "intent:retry", token, "temporary dependency failure", now + 1)
    assert db.execute("SELECT status FROM intent").fetchone()["status"] == "FAILED"
    show(db, "bounded retry reaches terminal failure")

    db = connect(reset=True)
    seed(db)
    accept(db, "intent:lease")
    assert claim(db, "intent:lease", "dead-worker", 110, lease_seconds=5)
    assert claim(db, "intent:lease", "replacement-worker", 116)
    show(db, "expired lease is safely reclaimed")
    print(f"\nScratch DB: {DB}\nPROTOTYPE only. Wipe freely.")


if __name__ == "__main__":
    if len(sys.argv) == 1 or sys.argv[1] == "demo":
        demo()
    else:
        raise SystemExit("Usage: python3 prototypes/intent_event_execution.py [demo]")
