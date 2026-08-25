#!/usr/bin/env python3
"""Run: python3 prototypes/a2ui_datastar_server.py"""

import argparse
import json
from html import escape
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlparse

from a2ui_datastar_adapter import CATALOGUE, VERSION, InvalidSurface, adapt, datastar_patch, translate_action


OFFER_ID = "offer:crm:snooze-seven-days"
ACTION_OFFERS = {
    OFFER_ID: {
        "actionId": "crm.SnoozeFollowUp",
        "principalId": "user:gareth",
        "subjectId": "contact:ada",
        "evaluationSnapshotId": "snapshot:7",
        "stateVersion": 7,
    }
}


def messages(case):
    offer_id = "offer:forged" if case == "forged" else OFFER_ID
    component = "Video" if case == "unknown" else "Button"
    return [
        {"version": VERSION, "createSurface": {"surfaceId": "follow-up", "catalogId": CATALOGUE}},
        {"version": VERSION, "updateComponents": {"surfaceId": "follow-up", "components": [
            {"id": "root", "component": "Column", "children": ["title", "summary", "label", "snooze"]},
            {"id": "title", "component": "Text", "variant": "h2", "text": "Ada Lovelace"},
            {"id": "summary", "component": "Text", "text": {"path": "/followUp"}},
            {"id": "label", "component": "Text", "text": "Snooze seven days"},
            {"id": "snooze", "component": component, "child": "label", "action": {
                "event": {"name": "invokeActionOffer", "context": {"actionOfferId": offer_id}}
            }},
        ]}},
        {"version": VERSION, "updateDataModel": {
            "surfaceId": "follow-up", "path": "/", "value": {"followUp": "Follow-up due today"}
        }},
    ]


def page(case):
    source = messages(case)
    try:
        result = adapt(source, ACTION_OFFERS)
        rendered = result["html"]
        verdict = f'Accepted {result["component_count"]} components. Adapter owns {len(result["glue"])} glue concerns.'
        glue = "".join(f"<li>{item}</li>" for item in result["glue"])
    except InvalidSurface as error:
        rendered = '<section class="surface rejected"><h2>Surface rejected</h2><p>No HTML or actions emitted.</p></section>'
        verdict = escape(str(error))
        glue = "<li>Validation stopped adapter before rendering.</li>"
    source_json = json.dumps(source, indent=2).replace("&", "&amp;").replace("<", "&lt;")
    return f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
  <title>A2UI to Datastar adapter prototype</title>
  <script type="module" src="https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.2/bundles/datastar.js"></script>
  <style>
    :root {{ font-family: ui-sans-serif, system-ui, sans-serif; color-scheme: light dark; }}
    body {{ max-width: 1100px; margin: auto; padding: 2rem 1rem 5rem; }}
    nav {{ display: flex; gap: .5rem; flex-wrap: wrap; margin: 1rem 0; }}
    nav a, button {{ border: 1px solid currentColor; border-radius: .35rem; padding: .65rem .9rem; color: inherit; background: Canvas; font: inherit; }}
    nav a[aria-current=true] {{ outline: 3px solid Highlight; }}
    .grid {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 400px), 1fr)); gap: 1rem; }}
    .panel, .surface {{ border: 1px solid color-mix(in srgb, CanvasText 35%, Canvas); border-radius: .6rem; padding: 1rem; }}
    .surface button {{ display: grid; gap: .25rem; text-align: left; cursor: pointer; }}
    .surface button p {{ margin: 0; font-weight: 700; }}
    .surface button small {{ opacity: .7; }}
    .rejected, .error {{ border-left: .4rem solid #b91c1c; }}
    pre {{ max-height: 32rem; overflow: auto; font-size: .76rem; white-space: pre-wrap; }}
    code {{ overflow-wrap: anywhere; }}
  </style>
</head>
<body>
  <p><strong>THROWAWAY PROTOTYPE</strong></p>
  <h1>Can A2UI remain default?</h1>
  <p>Bounded proof: A2UI 0.9.1 input, three-component catalogue, Datastar HTML/SSE output, existing Action Offer only.</p>
  <nav aria-label="Test case">
    {nav_link(case, "valid", "Valid surface")}
    {nav_link(case, "unknown", "Unknown component")}
    {nav_link(case, "forged", "Forged offer")}
  </nav>
  <div class="grid">
    <main class="panel"><h2>Rendered surface</h2>{rendered}<section id="adapter-result"><p>Invoke Action Offer to inspect translation.</p></section></main>
    <aside class="panel"><h2>Adapter verdict</h2><p>{verdict}</p><h3>Unavoidable glue</h3><ol>{glue}</ol></aside>
  </div>
  <details class="panel"><summary>A2UI messages</summary><pre><code>{source_json}</code></pre></details>
</body>
</html>""".encode()


def nav_link(current, case, label):
    selected = ' aria-current="true"' if current == case else ""
    return f'<a href="/?case={case}"{selected}>{label}</a>'


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path != "/":
            self.send_error(404)
            return
        case = parse_qs(parsed.query).get("case", ["valid"])[0]
        case = case if case in {"valid", "unknown", "forged"} else "valid"
        self.respond(page(case), "text/html; charset=utf-8")

    def do_POST(self):
        prefix = "/actions/"
        if not self.path.startswith(prefix):
            self.send_error(404)
            return
        offer_id = unquote(self.path[len(prefix):])
        try:
            intent = translate_action(offer_id, ACTION_OFFERS)
            body = escape(json.dumps(intent, indent=2))
            element = f'<section id="adapter-result"><h3>Intent request</h3><pre>{body}</pre><p>Adapter returned request only. Business state unchanged.</p></section>'
            self.respond(datastar_patch(element), "text/event-stream", sse=True)
        except InvalidSurface as error:
            element = f'<section id="adapter-result" class="error"><h3>Rejected</h3><p>{escape(str(error))}</p></section>'
            self.respond(datastar_patch(element), "text/event-stream", status=409, sse=True)

    def respond(self, body, content_type, status=200, sse=False):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        if sse:
            self.send_header("Cache-Control", "no-cache")
            self.send_header("X-Accel-Buffering", "no")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        print(format % args)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8080)
    arguments = parser.parse_args()
    server = ThreadingHTTPServer((arguments.host, arguments.port), Handler)
    print(f"PROTOTYPE: http://{arguments.host}:{arguments.port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
