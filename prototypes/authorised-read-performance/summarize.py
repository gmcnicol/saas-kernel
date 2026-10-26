#!/usr/bin/env python3
"""THROWAWAY opt-in regression signal, not a portable CI/release certification."""
import json
import pathlib
import re
import sys


def fields(line):
    return dict(re.findall(r"([a-zA-Z0-9_]+)=([^\s]+)", line))


def summarize(root):
    out = {}
    for mode in ("sdk", "sdk-profile", "scalar", "batch"):
        path = root / f"kit-{mode}-measured.log"
        if not path.exists():
            continue
        rows = [fields(line) for line in path.read_text().splitlines() if "PRIVATE_READ_BENCH" in line]
        row = next((r for r in rows if r.get("directory_rows") == "96"), None)
        if row is None:
            continue
        work = [fields(line) for line in path.read_text().splitlines() if "PRIVATE_READ_WORK" in line]
        item = {k: float(row[k]) for k in ("bounded_page_p50_ms", "bounded_page_p95_ms", "fenced_context_p50_ms", "fenced_context_p95_ms", "page_decisions")}
        for shape in ("page-96", "fenced-context-96"):
            w = next(w for w in work if w["shape"] == shape)
            n = int(w["samples"])
            item[shape] = {
                "samples": n,
                "checks_per_op": int(w["checks"]) / n,
                "invocations_per_op": int(w["invocations"]) / n,
                "java_thread_bytes_per_op": int(w["java_thread_bytes_per_op"]),
                **{k: float(w[k]) / n for k in ("prep_ms", "encoding_ms", "evaluator_ms", "decoding_ms", "total_ms")},
            }
        item["sample_targets_met"] = item["bounded_page_p95_ms"] < 200 and item["fenced_context_p95_ms"] < 100
        item["logical_work_preserved"] = item["page-96"]["checks_per_op"] == 340 and item["fenced-context-96"]["checks_per_op"] == 144
        out[mode] = item
    return out


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        assert fields("PRIVATE_READ_WORK shape=page-96 checks=2720 samples=8") == {"shape": "page-96", "checks": "2720", "samples": "8"}
        print("parser self-check passed")
    else:
        result = summarize(pathlib.Path(sys.argv[1]))
        print(json.dumps({"note": "8 sequential observations; p95 is maximum sample. Shared-machine result, not production readiness. SDK evaluator_ms includes codec unless sdk-profile.", "results": result}, indent=2))
        if "--gate" in sys.argv:
            sys.exit(0 if all(m in result and result[m]["sample_targets_met"] and result[m]["logical_work_preserved"] for m in ("scalar", "batch")) else 1)
