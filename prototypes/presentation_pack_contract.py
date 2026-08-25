"""PROTOTYPE: smallest presentation-neutral envelope and Presentation Pack seam.

Question: can one authorised Kernel envelope drive default Datastar, distinct
mobile, and optional A2UI experiences without carrying presentation choices?
"""

from html import escape
from urllib.parse import quote

from a2ui_datastar_adapter import CATALOGUE, VERSION, adapt


ENVELOPE_VERSION = "presentation-envelope/v1"
ACTION_LABELS = {
    "crm.SnoozeFollowUp": "Snooze seven days",
    "crm.RecordInteraction": "Record interaction",
    "crm.CompleteFollowUp": "Complete follow-up",
    "ledgerling.ChaseClient": "Chase client",
    "ledgerling.RecordRecordsReceived": "Record records received",
}

OFFER_RECORDS = {
    "offer:crm:snooze-seven-days": {
        "actionId": "crm.SnoozeFollowUp", "principalId": "user:gareth",
        "subjectId": "contact:ada", "evaluationSnapshotId": "snapshot:crm:7", "stateVersion": 7,
    },
    "offer:crm:record-interaction": {
        "actionId": "crm.RecordInteraction", "principalId": "user:gareth",
        "subjectId": "contact:ada", "evaluationSnapshotId": "snapshot:crm:7", "stateVersion": 7,
    },
    "offer:crm:complete-follow-up": {
        "actionId": "crm.CompleteFollowUp", "principalId": "user:gareth",
        "subjectId": "contact:ada", "evaluationSnapshotId": "snapshot:crm:7", "stateVersion": 7,
    },
    "offer:ledgerling:record-received": {
        "actionId": "ledgerling.RecordRecordsReceived", "principalId": "user:gareth",
        "subjectId": "client:ada", "evaluationSnapshotId": "snapshot:ledgerling:12", "stateVersion": 12,
    },
    "offer:ledgerling:chase-client": {
        "actionId": "ledgerling.ChaseClient", "principalId": "user:gareth",
        "subjectId": "client:ada", "evaluationSnapshotId": "snapshot:ledgerling:12", "stateVersion": 12,
    },
}


def crm_envelope():
    return {
        "version": ENVELOPE_VERSION,
        "subject": {"id": "contact:ada", "type": "crm.Contact"},
        "evaluation": {
            "snapshotId": "snapshot:crm:7",
            "evaluatedAt": "2026-08-14T14:00:00Z",
            "semanticPack": {"id": "crm", "version": "1.0.0"},
        },
        "state": {
            "type": "crm.Contact",
            "fields": {
                "crm.Contact.name": "Ada Lovelace",
                "crm.Contact.email": "ada@example.test",
                "crm.FollowUp.dueAt": "2026-08-14",
            },
        },
        "facts": [
            {"id": "crm.FollowUpDue", "type": "crm.FollowUpDue", "value": True},
        ],
        "actionOffers": [
            {
                "id": "offer:crm:snooze-seven-days",
                "actionId": "crm.SnoozeFollowUp",
                "inputType": "crm.SnoozeFollowUpInput",
            },
            {
                "id": "offer:crm:record-interaction",
                "actionId": "crm.RecordInteraction",
                "inputType": "crm.RecordInteractionInput",
            },
            {
                "id": "offer:crm:complete-follow-up",
                "actionId": "crm.CompleteFollowUp",
                "inputType": "crm.CompleteFollowUpInput",
            },
        ],
    }


def ledgerling_envelope():
    return {
        "version": ENVELOPE_VERSION,
        "subject": {"id": "client:ada", "type": "ledgerling.Client"},
        "evaluation": {
            "snapshotId": "snapshot:ledgerling:12",
            "evaluatedAt": "2026-08-14T14:00:00Z",
            "semanticPack": {"id": "ledgerling", "version": "1.0.0"},
        },
        "state": {
            "type": "ledgerling.Client",
            "fields": {
                "ledgerling.Client.name": "Ada & Co",
                "ledgerling.TaxObligation.dueAt": "2026-08-26",
                "ledgerling.DocumentRequest.status": "OUTSTANDING",
            },
        },
        "facts": [
            {"id": "ledgerling.FilingDueSoon", "type": "ledgerling.FilingDueSoon", "value": True},
            {"id": "ledgerling.RecordsOutstanding", "type": "ledgerling.RecordsOutstanding", "value": True},
        ],
        "actionOffers": [
            {
                "id": "offer:ledgerling:chase-client",
                "actionId": "ledgerling.ChaseClient",
                "inputType": "ledgerling.ChaseClientInput",
            },
            {
                "id": "offer:ledgerling:record-received",
                "actionId": "ledgerling.RecordRecordsReceived",
                "inputType": "ledgerling.RecordRecordsReceivedInput",
            }
        ],
    }


PACKS = {
    "crm-default": {
        "id": "crm.presentation.default",
        "version": "1.0.0",
        "semanticPack": "crm",
        "semanticPackVersion": "1.0.0",
        "envelopeVersion": ENVELOPE_VERSION,
        "render": lambda envelope: render_crm(envelope, mobile=False),
    },
    "crm-mobile": {
        "id": "crm.presentation.mobile",
        "version": "1.0.0",
        "semanticPack": "crm",
        "semanticPackVersion": "1.0.0",
        "envelopeVersion": ENVELOPE_VERSION,
        "render": lambda envelope: render_crm(envelope, mobile=True),
    },
    "crm-ai": {
        "id": "crm.presentation.a2ui",
        "version": "1.0.0",
        "semanticPack": "crm",
        "semanticPackVersion": "1.0.0",
        "envelopeVersion": ENVELOPE_VERSION,
        "render": lambda envelope: render_crm_a2ui(envelope),
    },
    "ledgerling-default": {
        "id": "ledgerling.presentation.default",
        "version": "1.0.0",
        "semanticPack": "ledgerling",
        "semanticPackVersion": "1.0.0",
        "envelopeVersion": ENVELOPE_VERSION,
        "render": lambda envelope: render_ledgerling(envelope),
    },
}


def validate(envelope):
    if envelope.get("version") != ENVELOPE_VERSION:
        raise ValueError("Unsupported Presentation Envelope version")
    required = {"version", "subject", "evaluation", "state", "facts", "actionOffers"}
    if set(envelope) != required:
        raise ValueError("Presentation Envelope fields do not match its versioned contract")
    offer_ids = [offer["id"] for offer in envelope.get("actionOffers", [])]
    if len(offer_ids) != len(set(offer_ids)):
        raise ValueError("Duplicate Action Offer")
    if any(set(offer) != {"id", "actionId", "inputType"} for offer in envelope.get("actionOffers", [])):
        raise ValueError("Presentation Action Offer exposes internal authority data")
    if any(set(fact) != {"id", "type", "value"} for fact in envelope.get("facts", [])):
        raise ValueError("Authorised Fact does not match presentation contract")
    return envelope


def render_pack(pack_key, envelope):
    envelope = validate(envelope)
    pack = PACKS[pack_key]
    semantic_pack = envelope["evaluation"]["semanticPack"]
    if pack["envelopeVersion"] != envelope["version"]:
        raise ValueError("Presentation Pack does not support this envelope version")
    if pack["semanticPack"] != semantic_pack["id"] or pack["semanticPackVersion"] != semantic_pack["version"]:
        raise ValueError("Presentation Pack does not support this Semantic Pack")
    return {
        "manifest": {key: pack[key] for key in ("id", "version", "envelopeVersion", "semanticPack", "semanticPackVersion")},
        "html": pack["render"](envelope),
    }


def render_crm(envelope, mobile):
    fields = envelope["state"]["fields"]
    offers = {offer["actionId"]: offer for offer in envelope["actionOffers"]}
    name = escape(fields["crm.Contact.name"])
    email = escape(fields["crm.Contact.email"])
    due = escape(fields["crm.FollowUp.dueAt"])
    due_label = "Due today" if fact_value(envelope, "crm.FollowUpDue") else "Not due"
    buttons = "".join(offer_button(offer, mobile) for offer in offers.values())
    if mobile:
        return f'''<article class="experience mobile"><p class="eyebrow">{due_label}</p><h2>{name}</h2>
          <a href="mailto:{email}">{email}</a><div class="mobile-actions">{buttons}</div>
          <details><summary>Contact details</summary><p>Follow-up date: {due}</p></details></article>'''
    return f'''<article class="experience desktop"><header><div><p class="eyebrow">Contact</p><h2>{name}</h2>
          <p>{email}</p></div><p class="due">{due_label}<br><strong>{due}</strong></p></header>
          <section><h3>Available actions</h3><div class="actions">{buttons}</div></section></article>'''


def render_ledgerling(envelope):
    fields = envelope["state"]["fields"]
    buttons = "".join(offer_button(offer, False) for offer in envelope["actionOffers"])
    statuses = ", ".join(label for fact, label in (
        ("ledgerling.FilingDueSoon", "Filing due soon"),
        ("ledgerling.RecordsOutstanding", "Records outstanding"),
    ) if fact_value(envelope, fact))
    return f'''<article class="experience ledger"><p class="eyebrow">{statuses}</p>
      <h2>{escape(fields["ledgerling.Client.name"])}</h2>
      <dl><div><dt>Filing due</dt><dd>{escape(fields["ledgerling.TaxObligation.dueAt"])}</dd></div>
      <div><dt>Records</dt><dd>{escape(fields["ledgerling.DocumentRequest.status"].title())}</dd></div></dl>
      <div class="actions">{buttons}</div></article>'''


def render_crm_a2ui(envelope):
    fields = envelope["state"]["fields"]
    components = [
        {"id": "root", "component": "Column", "children": ["title", "summary"]},
        {"id": "title", "component": "Text", "variant": "h2", "text": "Suggested next steps"},
        {"id": "summary", "component": "Text", "text": {"path": "/summary"}},
    ]
    for index, offer in enumerate(envelope["actionOffers"]):
        label_id = f"label-{index}"
        action_id = f"action-{index}"
        components[0]["children"].extend([label_id, action_id])
        components.extend([
            {"id": label_id, "component": "Text", "text": ACTION_LABELS[offer["actionId"]]},
            {"id": action_id, "component": "Button", "child": label_id, "action": {
                "event": {"name": "invokeActionOffer", "context": {"actionOfferId": offer["id"]}}
            }},
        ])
    messages = [
        {"version": VERSION, "createSurface": {"surfaceId": "crm-ai", "catalogId": CATALOGUE}},
        {"version": VERSION, "updateComponents": {"surfaceId": "crm-ai", "components": components}},
        {"version": VERSION, "updateDataModel": {"surfaceId": "crm-ai", "path": "/", "value": {
            "summary": f'{fields["crm.Contact.name"]} has a follow-up due today.' if fact_value(envelope, "crm.FollowUpDue") else f'{fields["crm.Contact.name"]} has no follow-up due.'
        }}},
    ]
    offers = {item["id"]: item for item in envelope["actionOffers"]}
    return '<article class="experience ai"><p class="eyebrow">Application-owned AI, validated A2UI</p>' + adapt(messages, offers)["html"] + "</article>"


def offer_button(offer, prominent):
    endpoint = quote(offer["id"], safe="")
    css = ' class="primary"' if prominent else ""
    return f'<button{css} data-on:click="@post(\'/actions/{endpoint}\')">{ACTION_LABELS[offer["actionId"]]}</button>'


def fact_value(envelope, fact_id):
    fact = next((item for item in envelope["facts"] if item["id"] == fact_id), None)
    return fact["value"] if fact else None


def intent_request(envelope, offer_id):
    visible = next((item for item in envelope["actionOffers"] if item["id"] == offer_id), None)
    offer = OFFER_RECORDS.get(offer_id)
    if not visible or not offer:
        raise ValueError("No current Action Offer")
    return {
        "actionOfferId": offer_id,
        "actionId": offer["actionId"],
        "principalId": offer["principalId"],
        "subjectId": offer["subjectId"],
        "evaluationSnapshotId": offer["evaluationSnapshotId"],
        "expectedStateVersion": offer["stateVersion"],
        "payload": {},
    }
