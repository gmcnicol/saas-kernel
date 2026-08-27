#!/usr/bin/env python3
"""Run: python3 prototypes/authorisation_envelope_tui.py"""

from pprint import pformat

from authorisation_envelope_model import ACTION, accept, execute, initial_state, inspect


BOLD = "\x1b[1m"
DIM = "\x1b[2m"
RESET = "\x1b[0m"


def render(state, result):
    print("\033[2J\033[H", end="")
    print(f"{BOLD}AUTHORISATION ENVELOPE PROTOTYPE{RESET}")
    print(f"{DIM}Diagnostic view exposes internal applicability; product response would not.{RESET}\n")
    print(f"{BOLD}Inputs{RESET}")
    print(pformat({key: state[key] for key in (
        "request_tenant", "subject_tenant", "state_version", "now",
        "policy_version", "cedar_permits", "intent",
    )}, sort_dicts=False))
    print(f"\n{BOLD}Composed read{RESET}\n{pformat(inspect(state), sort_dicts=False)}")
    print(f"\n{BOLD}Last result{RESET}\n{result}")
    print(
        f"\n{BOLD}[a]{RESET} accept  {BOLD}[x]{RESET} execute + Cedar recheck  "
        f"{BOLD}[f]{RESET} execute, freeze acceptance auth\n"
        f"{BOLD}[r]{RESET} cross-tenant request  {BOLD}[p]{RESET} revoke invoke  "
        f"{BOLD}[v]{RESET} hide email  {BOLD}[s]{RESET} change Projected State  "
        f"{BOLD}[t]{RESET} pass escalation time\n"
        f"{BOLD}[0]{RESET} reset  {BOLD}[q]{RESET} quit"
    )


def main():
    state = initial_state()
    result = "Ready"
    while True:
        render(state, result)
        command = input("> ").strip().lower()
        if command == "q":
            return
        if command == "0":
            state, result = initial_state(), "Reset"
        elif command == "a":
            result = accept(state)
        elif command == "x":
            result = execute(state, recheck_cedar=True)
        elif command == "f":
            result = execute(state, recheck_cedar=False)
        elif command == "r":
            state["request_tenant"] = "other" if state["request_tenant"] == "acme" else "acme"
            result = "Request tenant toggled"
        elif command == "p":
            permit = f"invoke:{ACTION}"
            state["cedar_permits"].symmetric_difference_update({permit})
            state["policy_version"] += 1
            result = "Cedar invoke permit toggled"
        elif command == "v":
            state["cedar_permits"].symmetric_difference_update({"view:email"})
            state["policy_version"] += 1
            result = "Email field permit toggled"
        elif command == "s":
            state["subject"]["follow_up"] = "DONE"
            state["state_version"] += 1
            result = "Projected State changed"
        elif command == "t":
            state["now"] = 201
            result = "Evaluation time advanced"
        else:
            result = "Unknown key"


if __name__ == "__main__":
    main()
