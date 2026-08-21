import json
import subprocess
import sys
import unittest
from pathlib import Path

from tools.agent_queue import select_issue, slugify, validate_issue


class AgentQueuePolicyTests(unittest.TestCase):
    def test_select_issue_chooses_lowest_number_ready_issue(self):
        issues = [
            {"number": 9, "title": "Later", "body": "", "labels": [{"name": "agent:ready"}]},
            {"number": 3, "title": "First", "body": "", "labels": [{"name": "agent:ready"}]},
            {"number": 1, "title": "Blocked", "body": "", "labels": [{"name": "agent:blocked"}]},
        ]

        selected = select_issue(issues)

        self.assertEqual(3, selected["number"])

    def test_select_issue_ignores_non_ready_issues(self):
        issues = [
            {"number": 1, "title": "Blocked", "body": "", "labels": [{"name": "agent:blocked"}]},
            {"number": 2, "title": "Working", "body": "", "labels": [{"name": "agent:working"}]},
        ]

        self.assertIsNone(select_issue(issues))

    def test_validate_issue_reports_missing_required_sections(self):
        issue = {
            "number": 7,
            "title": "Incomplete task",
            "body": "## Desired outcome\nShip it\n",
            "labels": [{"name": "agent:ready"}],
        }

        errors = validate_issue(issue)

        self.assertEqual(
            [
                "missing required section: ## Acceptance criteria",
                "missing required section: ## Constraints",
            ],
            errors,
        )

    def test_validate_issue_accepts_complete_task(self):
        issue = {
            "number": 8,
            "title": "Complete task",
            "body": (
                "## Desired outcome\nAdd a safe queue.\n\n"
                "## Acceptance criteria\n- Lowest issue wins.\n\n"
                "## Constraints\n- Never write directly to main.\n"
            ),
            "labels": [{"name": "agent:ready"}],
        }

        self.assertEqual([], validate_issue(issue))

    def test_slugify_is_ascii_lowercase_hyphenated_and_bounded(self):
        title = "Árvore de Progressão Infinita — GRAND LINE DUO!!! " + ("Muito " * 30)

        slug = slugify(title)

        self.assertTrue(slug.startswith("arvore-de-progressao-infinita-grand-line-duo"))
        self.assertLessEqual(len(slug), 60)
        self.assertRegex(slug, r"^[a-z0-9]+(?:-[a-z0-9]+)*$")


if __name__ == "__main__":
    unittest.main()
