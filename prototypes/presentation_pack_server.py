#!/usr/bin/env python3
"""Run: python3 prototypes/presentation_pack_server.py --host 0.0.0.0"""

import argparse
import json
from html import escape
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlparse

from a2ui_datastar_adapter import datastar_patch
from presentation_pack_contract import PACKS, crm_envelope, intent_request, ledgerling_envelope, render_pack


def envelope_for(pack_key):
    return ledgerling_envelope() if pack_key.startswith("ledgerling") else crm_envelope()


def page(pack_key):
    envelope = envelope_for(pack_key)
    result = render_pack(pack_key, envelope)
    manifest = escape(json.dumps(result["manifest"], indent=2))
    contract = escape(json.dumps(envelope, indent=2))
    nav = "".join(nav_link(pack_key, key, label) for key, label in (
        ("crm-default", "CRM default"),
        ("crm-mobile", "CRM mobile"),
        ("crm-ai", "CRM AI + A2UI"),
        ("ledgerling-default", "Ledgerling default"),
    ))
    return f'''<!doctype html><html lang="en"><head><meta charset="utf-8">
      <meta name="viewport" content="width=device-width, initial-scale=1">
      <title>Presentation Pack contract prototype</title>
      <script type="module" src="https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.2/bundles/datastar.js"></script>
      <style>
        :root {{ font-family: ui-sans-serif, system-ui, sans-serif; color-scheme: light dark; }}
        body {{ max-width: 1120px; margin: auto; padding: 2rem 1rem 5rem; }}
        nav, .actions, .mobile-actions {{ display: flex; flex-wrap: wrap; gap: .6rem; }}
        nav {{ margin: 1rem 0; }} nav a, button {{ border: 1px solid currentColor; border-radius: .4rem; padding: .7rem 1rem; color: inherit; background: Canvas; font: inherit; }}
        nav a[aria-current=true] {{ outline: 3px solid Highlight; }}
        .grid {{ display: grid; grid-template-columns: minmax(0, 1.3fr) minmax(320px, .7fr); gap: 1rem; }}
        .panel, .experience, .surface {{ border: 1px solid color-mix(in srgb, CanvasText 32%, Canvas); border-radius: .7rem; padding: 1.1rem; }}
        .experience {{ min-height: 20rem; }} .desktop header {{ display: flex; justify-content: space-between; gap: 2rem; }}
        .eyebrow {{ text-transform: uppercase; letter-spacing: .09em; font-size: .75rem; opacity: .7; }}
        .due {{ text-align: right; }} button {{ cursor: pointer; }} button.primary {{ width: 100%; font-weight: 700; padding: 1rem; }}
        .mobile {{ max-width: 25rem; margin: auto; }} .mobile-actions {{ display: grid; margin: 2rem 0; }}
        .ledger dl {{ display: flex; gap: 2rem; }} .ledger dl div {{ flex: 1; }} dt {{ opacity: .7; }} dd {{ margin: .35rem 0; font-weight: 700; }}
        pre {{ max-height: 28rem; overflow: auto; font-size: .72rem; white-space: pre-wrap; }}
        #intent-result {{ margin-top: 1rem; }}
        @media (max-width: 760px) {{ .grid {{ grid-template-columns: 1fr; }} }}
      </style></head><body>
      <p><strong>THROWAWAY PROTOTYPE</strong></p><h1>One envelope, different experiences</h1>
      <p>Kernel output contains authorised semantics and Action Offers. Presentation Pack owns everything visible.</p>
      <nav aria-label="Presentation Pack">{nav}</nav>
      <div class="grid"><main class="panel"><p class="eyebrow">Rendered by {escape(result["manifest"]["id"])}</p>
        {result["html"]}<section id="intent-result"><p>No Intent request yet.</p></section></main>
        <aside class="panel"><h2>Pack manifest</h2><pre>{manifest}</pre><h2>Exact Kernel envelope</h2><pre>{contract}</pre></aside></div>
      </body></html>'''.encode()


def nav_link(current, key, label):
    selected = ' aria-current="true"' if current == key else ""
    return f'<a href="/?pack={key}"{selected}>{label}</a>'


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path != "/":
            self.send_error(404)
            return
        pack_key = parse_qs(parsed.query).get("pack", ["crm-default"])[0]
        pack_key = pack_key if pack_key in PACKS else "crm-default"
        self.respond(page(pack_key), "text/html; charset=utf-8")

    def do_POST(self):
        if not self.path.startswith("/actions/"):
            self.send_error(404)
            return
        offer_id = unquote(self.path.removeprefix("/actions/"))
        envelope = ledgerling_envelope() if offer_id.startswith("offer:ledgerling") else crm_envelope()
        try:
            intent = escape(json.dumps(intent_request(envelope, offer_id), indent=2))
            element = f'<section id="intent-result"><h3>Same Intent request</h3><pre>{intent}</pre></section>'
            self.respond(datastar_patch(element), "text/event-stream", sse=True)
        except ValueError as error:
            element = f'<section id="intent-result"><h3>Rejected</h3><p>{escape(str(error))}</p></section>'
            self.respond(datastar_patch(element), "text/event-stream", sse=True)

    def respond(self, body, content_type, sse=False):
        self.send_response(200)
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
