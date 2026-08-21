#!/usr/bin/env python3
"""Pure policy helpers for the GRAND LINE DUO autonomous issue queue."""

from __future__ import annotations

import json
import re
import sys
import unicodedata
from typing import Any

READY_LABEL = "agent:ready"
REQUIRED_SECTIONS = (
    "## Desired outcome",
    "## Acceptance criteria",
    "## Constraints",
)


def _label_names(issue: dict[str, Any]) -> set[str]:
    names: set[str] = set()
    for label in issue.get("labels") or []:
        if isinstance(label, str):
            names.add(label)
        elif isinstance(label, dict) and isinstance(label.get("name"), str):
            names.add(label["name"])
    return names


def select_issue(issues: list[dict[str, Any]]) -> dict[str, Any] | None:
    """Return the lowest-number open queue item carrying agent:ready."""
    eligible = [issue for issue in issues if READY_LABEL in _label_names(issue)]
    if not eligible:
        return None
    return min(eligible, key=lambda issue: int(issue["number"]))


def validate_issue(issue: dict[str, Any]) -> list[str]:
    """Return deterministic validation errors for an autonomous task Issue."""
    body = issue.get("body") or ""
    errors: list[str] = []
    for section in REQUIRED_SECTIONS:
        pattern = rf"(?im)^{re.escape(section)}\s*$"
        if re.search(pattern, body) is None:
            errors.append(f"missing required section: {section}")
    return errors


def slugify(title: str, max_length: int = 60) -> str:
    """Create an ASCII branch-safe slug without exceeding max_length."""
    normalized = unicodedata.normalize("NFKD", title)
    ascii_text = normalized.encode("ascii", "ignore").decode("ascii").lower()
    slug = re.sub(r"[^a-z0-9]+", "-", ascii_text).strip("-")
    slug = slug[:max_length].rstrip("-")
    return slug or "task"


def _read_json_from_stdin() -> Any:
    payload = sys.stdin.read()
    if not payload.strip():
        raise ValueError("expected JSON on stdin")
    return json.loads(payload)


def _main(argv: list[str]) -> int:
    if len(argv) != 2 or argv[1] not in {"select", "validate"}:
        print("usage: agent_queue.py {select|validate}", file=sys.stderr)
        return 2

    try:
        payload = _read_json_from_stdin()
    except (ValueError, json.JSONDecodeError) as exc:
        print(f"invalid input: {exc}", file=sys.stderr)
        return 2

    if argv[1] == "select":
        if not isinstance(payload, list):
            print("invalid input: select expects a JSON array", file=sys.stderr)
            return 2
        selected = select_issue(payload)
        if selected is not None:
            print(json.dumps(selected, ensure_ascii=False, separators=(",", ":")))
        return 0

    if not isinstance(payload, dict):
        print("invalid input: validate expects a JSON object", file=sys.stderr)
        return 2
    errors = validate_issue(payload)
    if errors:
        print("\n".join(errors))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(_main(sys.argv))
