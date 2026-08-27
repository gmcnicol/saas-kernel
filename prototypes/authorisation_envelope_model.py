"""PROTOTYPE: compose RLS, applicability, Cedar, filtering, and rechecks.

Question: should an accepted Intent retain its acceptance-time Cedar decision,
or must execution require a current Cedar permit as well as current business
applicability?
"""

ACTION = "crm.SnoozeFollowUp"


def inspect(state):
    """Return each layer separately so principal-independent applicability stays visible."""
    row_visible = state["request_tenant"] == state["subject_tenant"]
    if not row_visible:
        return {"row_visible": False, "fields": {}, "applicable": [], "offers": []}

    fields = {
        name: value
        for name, value in state["subject"].items()
        if f"view:{name}" in state["cedar_permits"]
    }
    applicable = (
        [ACTION]
        if state["subject"]["follow_up"] == "OPEN"
        and state["now"] < state["subject"]["escalates_at"]
        else []
    )
    offers = [action for action in applicable if f"invoke:{action}" in state["cedar_permits"]]
    return {
        "row_visible": True,
        "fields": fields,
        "applicable": applicable,
        "offers": offers,
    }


def accept(state):
    view = inspect(state)
    if ACTION not in view["offers"]:
        return "REJECTED: no current Action Offer"
    state["intent"] = {
        "action": ACTION,
        "expected_state_version": state["state_version"],
        "evaluation_time": state["now"],
        "policy_version": state["policy_version"],
    }
    return "PENDING"


def execute(state, recheck_cedar):
    intent = state["intent"]
    if not intent:
        return "NO_INTENT"
    view = inspect(state)
    if not view["row_visible"]:
        return "DENIED: RLS"
    if state["state_version"] != intent["expected_state_version"]:
        return "STALE: Projected State changed"
    if ACTION not in view["applicable"]:
        return "NOT_APPLICABLE: current evaluation"
    if recheck_cedar and ACTION not in view["offers"]:
        return "DENIED: current Cedar policy"
    return "SUCCEEDED"


def initial_state():
    return {
        "request_tenant": "acme",
        "subject_tenant": "acme",
        "subject": {
            "name": "Ada",
            "email": "ada@example.test",
            "tax_id": "SECRET-123",
            "follow_up": "OPEN",
            "escalates_at": 200,
        },
        "state_version": 7,
        "now": 100,
        "policy_version": 1,
        "cedar_permits": {"view:name", "view:email", f"invoke:{ACTION}"},
        "intent": None,
    }
