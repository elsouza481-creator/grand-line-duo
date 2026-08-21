import json
import subprocess
import sys
import unittest
from pathlib import Path

SELECTOR = Path(__file__).with_name("select_issue.py")


def run_selector(issues):
    completed = subprocess.run(
        [sys.executable, str(SELECTOR)],
        input=json.dumps(issues),
        text=True,
        capture_output=True,
        check=False,
    )
    return completed.returncode, completed.stdout.strip(), completed.stderr.strip()


class SelectIssueTests(unittest.TestCase):
    def test_selects_lowest_ready_issue_number(self):
        issues = [
            {"number": 12, "labels": [{"name": "agent:ready"}]},
            {"number": 3, "labels": [{"name": "agent:ready"}]},
            {"number": 7, "labels": [{"name": "agent:ready"}]},
        ]
        code, stdout, stderr = run_selector(issues)
        self.assertEqual(code, 0, stderr)
        self.assertEqual(stdout, "3")

    def test_ignores_stopped_and_blocked_issues(self):
        issues = [
            {"number": 1, "labels": [{"name": "agent:ready"}, {"name": "agent:stop"}]},
            {"number": 2, "labels": [{"name": "agent:ready"}, {"name": "agent:blocked"}]},
            {"number": 9, "labels": [{"name": "agent:ready"}]},
        ]
        code, stdout, stderr = run_selector(issues)
        self.assertEqual(code, 0, stderr)
        self.assertEqual(stdout, "9")

    def test_returns_empty_output_when_nothing_is_eligible(self):
        issues = [
            {"number": 2, "labels": [{"name": "bug"}]},
            {"number": 4, "labels": [{"name": "agent:blocked"}]},
        ]
        code, stdout, stderr = run_selector(issues)
        self.assertEqual(code, 0, stderr)
        self.assertEqual(stdout, "")

    def test_rejects_non_array_input(self):
        completed = subprocess.run(
            [sys.executable, str(SELECTOR)],
            input=json.dumps({"number": 1}),
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("expected a JSON array", completed.stderr)


if __name__ == "__main__":
    unittest.main()
