#!/usr/bin/env python3
"""Select the lowest-numbered eligible autonomous development issue."""

import json
import sys

READY = "agent:ready"
DISQUALIFYING = {"agent:blocked", "agent:stop", "agent:working", "agent:done"}


def label_names(issue):
    labels = issue.get("labels", [])
    names = set()
    for label in labels:
        if isinstance(label, str):
            names.add(label)
        elif isinstance(label, dict) and isinstance(label.get("name"), str):
            names.add(label["name"])
    return names


def select_issue_number(issues):
    candidates = []
    for issue in issues:
        if not isinstance(issue, dict):
            continue
        number = issue.get("number")
        if not isinstance(number, int):
            continue
        labels = label_names(issue)
        if READY in labels and labels.isdisjoint(DISQUALIFYING):
            candidates.append(number)
    return min(candidates) if candidates else None


def main():
    try:
        payload = json.load(sys.stdin)
    except json.JSONDecodeError as exc:
        print(f"invalid JSON: {exc}", file=sys.stderr)
        return 2

    if not isinstance(payload, list):
        print("expected a JSON array", file=sys.stderr)
        return 2

    selected = select_issue_number(payload)
    if selected is not None:
        print(selected)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
