"""PROTOTYPE: bounded A2UI 0.9.1 to Datastar adapter logic.

Question: is validation, catalogue rendering, SSE framing, and Action Offer
translation small enough to keep A2UI in the default Presentation Pack?
"""

from html import escape
from urllib.parse import quote


VERSION = "v0.9.1"
CATALOGUE = "https://a2ui.org/specification/v0_9_1/catalogs/basic/catalog.json"
COMPONENTS = {"Column", "Text", "Button"}


class InvalidSurface(ValueError):
    pass


def adapt(messages, action_offers):
    surface_id = None
    components = {}
    data = {}

    for message in messages:
        if message.get("version") != VERSION:
            raise InvalidSurface(f"Only {VERSION} is supported")
        operations = [name for name in ("createSurface", "updateComponents", "updateDataModel") if name in message]
        if len(operations) != 1:
            raise InvalidSurface("Each message needs exactly one supported operation")
        operation = operations[0]
        payload = message[operation]
        if operation == "createSurface":
            if surface_id is not None or payload.get("catalogId") != CATALOGUE:
                raise InvalidSurface("Surface must declare the supported basic catalogue once")
            surface_id = required_string(payload, "surfaceId")
        elif surface_id is None or payload.get("surfaceId") != surface_id:
            raise InvalidSurface("Updates must follow createSurface and target that surface")
        elif operation == "updateComponents":
            for component in payload.get("components", []):
                component_id = required_string(component, "id")
                kind = required_string(component, "component")
                if component_id in components:
                    raise InvalidSurface(f"Duplicate component: {component_id}")
                if kind not in COMPONENTS:
                    raise InvalidSurface(f"Unsupported component: {kind}")
                components[component_id] = component
        else:
            if payload.get("path") != "/" or not isinstance(payload.get("value"), dict):
                raise InvalidSurface("Prototype accepts one root data-model object")
            data = payload["value"]

    if surface_id is None or not components:
        raise InvalidSurface("Surface and components are required")

    referenced = set()
    for component in components.values():
        kind = component["component"]
        if kind == "Column":
            children = component.get("children")
            if not isinstance(children, list) or not children:
                raise InvalidSurface("Column needs children")
            referenced.update(children)
        elif kind == "Button":
            referenced.add(required_string(component, "child"))
            validate_action(component.get("action"), action_offers)
        elif kind == "Text" and not isinstance(component.get("text"), (str, dict)):
            raise InvalidSurface("Text needs literal text or a data-model path")

    missing = referenced - components.keys()
    if missing:
        raise InvalidSurface(f"Missing component references: {', '.join(sorted(missing))}")
    roots = [component_id for component_id in components if component_id not in referenced]
    if len(roots) != 1:
        raise InvalidSurface("Surface needs exactly one root component")

    html = render(roots[0], components, data, action_offers, set())
    return {
        "surface_id": surface_id,
        "html": html,
        "component_count": len(components),
        "glue": [
            "A2UI message and version validation",
            "constrained catalogue validation and HTML rendering",
            "data-model path resolution",
            "A2UI event to current Action Offer lookup",
            "Datastar action attributes and SSE framing",
        ],
    }


def required_string(value, key):
    result = value.get(key) if isinstance(value, dict) else None
    if not isinstance(result, str) or not result:
        raise InvalidSurface(f"{key} must be a non-empty string")
    return result


def validate_action(action, action_offers):
    event = action.get("event") if isinstance(action, dict) else None
    if not isinstance(event, dict) or event.get("name") != "invokeActionOffer":
        raise InvalidSurface("Button action must invoke an Action Offer")
    context = event.get("context")
    offer_id = context.get("actionOfferId") if isinstance(context, dict) else None
    if offer_id not in action_offers:
        raise InvalidSurface("Button references no current Action Offer")


def render(component_id, components, data, action_offers, ancestors):
    if component_id in ancestors:
        raise InvalidSurface("Component graph contains a cycle")
    component = components[component_id]
    kind = component["component"]
    ancestors = ancestors | {component_id}
    if kind == "Column":
        children = "".join(render(child, components, data, action_offers, ancestors) for child in component["children"])
        return f'<section id="{escape(component_id)}" class="surface">{children}</section>'
    if kind == "Text":
        value = resolve_text(component["text"], data)
        tag = "h2" if component.get("variant") == "h2" else "p"
        return f'<{tag} id="{escape(component_id)}">{escape(value)}</{tag}>'

    offer_id = component["action"]["event"]["context"]["actionOfferId"]
    offer = action_offers[offer_id]
    label = render(component["child"], components, data, action_offers, ancestors)
    endpoint = quote(offer_id, safe="")
    return (
        f'<button id="{escape(component_id)}" data-on:click="@post(\'/actions/{endpoint}\')" '
        f'data-indicator="fetching">{label}<small>{escape(offer["actionId"])}</small></button>'
    )


def resolve_text(text, data):
    if isinstance(text, str):
        return text
    path = text.get("path") if isinstance(text, dict) else None
    if not isinstance(path, str) or not path.startswith("/"):
        raise InvalidSurface("Data binding needs an absolute path")
    value = data
    for part in path.strip("/").split("/"):
        if not isinstance(value, dict) or part not in value:
            raise InvalidSurface(f"Unknown data-model path: {path}")
        value = value[part]
    if not isinstance(value, (str, int, float, bool)):
        raise InvalidSurface("Text data binding must resolve to a scalar")
    return str(value)


def translate_action(offer_id, action_offers):
    offer = action_offers.get(offer_id)
    if not offer:
        raise InvalidSurface("Action Offer is no longer current")
    return {
        "actionOfferId": offer_id,
        "actionId": offer["actionId"],
        "principalId": offer["principalId"],
        "subjectId": offer["subjectId"],
        "evaluationSnapshotId": offer["evaluationSnapshotId"],
        "expectedStateVersion": offer["stateVersion"],
        "payload": {},
    }


def datastar_patch(element):
    compact = " ".join(element.split())
    return f"event: datastar-patch-elements\ndata: elements {compact}\n\n".encode()
