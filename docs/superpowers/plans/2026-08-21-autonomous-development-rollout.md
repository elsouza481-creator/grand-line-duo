# GRAND LINE DUO Autonomous Development Rollout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the safe first rollout of the autonomous-development subsystem so well-specified `agent:ready` Issues can be selected, validated, claimed, and delegated to a coding agent without direct writes to `main`, while ambiguous or unavailable-agent cases fail closed.

**Architecture:** Keep policy decisions in a small Python standard-library helper with unit tests and keep GitHub side effects in narrowly scoped Actions workflows. GitHub Issues remain the queue, a repository variable provides a global pause switch, lifecycle labels encode state, and Copilot cloud agent delegation is optional behind a user-token secret; if delegation cannot start, the Issue becomes `agent:blocked` with diagnostics. Autonomous merge stays disabled in this rollout.

**Tech Stack:** GitHub Actions, GitHub CLI (`gh`), Python 3 standard library/unittest, existing Kotlin/Gradle/Android verification workflows.

**Spec:** `docs/superpowers/specs/2026-08-21-autonomous-development-design.md`

## Global Constraints

- Only Issues explicitly labeled `agent:ready` may be claimed.
- At most one Issue is claimed per queue execution.
- Queue priority is the lowest eligible Issue number first.
- Autonomous workers never commit directly to `main`.
- Failed or ambiguous work transitions to `agent:blocked` instead of retrying forever.
- Existing core/unit tests and Android build validation remain required for gameplay/code changes.
- No workflow may force-push `main`, bypass failed checks, rewrite history, modify secrets, or disable CI safeguards.
- Autonomous merge remains disabled during this rollout stage.
- The repository-wide pause switch is `vars.AUTONOMOUS_DEVELOPMENT_PAUSED == 'true'`.
- Copilot delegation uses the optional `COPILOT_AGENT_TOKEN` repository secret and must fail closed when it is missing or rejected.

---

### Task 1: Queue policy helper with TDD

**Files:**
- Create: `tools/agent_queue.py`
- Create: `tools/test_agent_queue.py`

**Interfaces:**
- Consumes: JSON array from `gh issue list --json number,title,body,labels`.
- Produces: `select_issue(issues) -> dict | None`, `validate_issue(issue) -> list[str]`, `slugify(title) -> str`, and CLI commands `select` / `validate`.

- [ ] **Step 1: Write failing unit tests**

Create `tools/test_agent_queue.py` with tests that prove: lowest-number ready Issue wins; non-ready Issues are ignored; missing required body sections are reported; a valid issue returns no validation errors; slug generation is lowercase, hyphenated, ASCII-safe, and length-bounded.

- [ ] **Step 2: Run tests and verify RED**

Run: `python3 -m unittest tools/test_agent_queue.py -v`

Expected: FAIL because `tools.agent_queue` does not exist yet.

- [ ] **Step 3: Implement minimal helper**

Create `tools/agent_queue.py` using only Python standard library. Required body headings are `## Desired outcome`, `## Acceptance criteria`, and `## Constraints`. Label extraction must accept the GitHub JSON label-object shape (`{"name": "agent:ready"}`). `select_issue` must sort by integer issue number and return the first issue whose labels contain `agent:ready`.

The CLI must support:

```bash
printf '%s' "$ISSUES_JSON" | python3 tools/agent_queue.py select
printf '%s' "$ISSUE_JSON" | python3 tools/agent_queue.py validate
```

`select` prints a compact JSON object for the selected Issue or exits 0 with no output when none is eligible. `validate` prints one validation error per line and exits 1 when invalid; it exits 0 with no output when valid.

- [ ] **Step 4: Run tests and verify GREEN**

Run: `python3 -m unittest tools/test_agent_queue.py -v`

Expected: all tests PASS.

- [ ] **Step 5: Commit**

Commit message: `feat: add autonomous queue policy helper`

---

### Task 2: Automation policy CI

**Files:**
- Create: `.github/workflows/autonomous-policy-ci.yml`

**Interfaces:**
- Consumes: changes under `tools/agent_queue.py`, `tools/test_agent_queue.py`, `.github/workflows/autonomous-*.yml`, `.github/ISSUE_TEMPLATE/**`, and `.github/agents/**`.
- Produces: required observable check `Autonomous policy tests / policy-tests` for pull requests touching automation files.

- [ ] **Step 1: Create workflow configuration**

Add a `pull_request` and `push` workflow targeting `main` that checks out the repository, sets up Python 3.12, runs `python3 -m unittest tools/test_agent_queue.py -v`, and parses every `autonomous-*.yml` file with a short Python script using `yaml` only if available; because PyYAML is not guaranteed, the workflow must instead validate GitHub workflow syntax structurally by loading files as text and asserting non-empty `name:`, `on:`, and `jobs:` top-level markers. The GitHub Actions platform itself remains the authoritative YAML parser when the workflow is loaded.

- [ ] **Step 2: Verify workflow file is self-consistent**

Run locally or in CI:

```bash
python3 -m unittest tools/test_agent_queue.py -v
python3 - <<'PY'
from pathlib import Path
for path in Path('.github/workflows').glob('autonomous-*.yml'):
    text = path.read_text()
    for marker in ('name:', 'on:', 'jobs:'):
        assert marker in text, (path, marker)
print('workflow structure ok')
PY
```

Expected: PASS.

- [ ] **Step 3: Commit**

Commit message: `ci: verify autonomous development policy`

---

### Task 3: Issue template, agent profile, and operator controls

**Files:**
- Create: `.github/ISSUE_TEMPLATE/autonomous-task.yml`
- Create: `.github/agents/grand-line-builder.agent.md`
- Create: `.github/copilot-instructions.md`
- Create: `docs/AUTONOMOUS_DEVELOPMENT.md`

**Interfaces:**
- Consumes: user-created development tasks and repository conventions.
- Produces: Issues containing the exact required headings; a repository-scoped coding-agent profile; operating instructions for queueing, pausing, blocking, and resuming work.

- [ ] **Step 1: Add Issue form**

The form must require Desired outcome, Acceptance criteria, and Constraints fields and render them under the exact Markdown headings consumed by `validate_issue`. It should instruct maintainers to add `agent:ready` only when the task is sufficiently specified.

- [ ] **Step 2: Add GRAND LINE DUO coding-agent profile**

Create `.github/agents/grand-line-builder.agent.md` with repository-specific instructions: Android/mobile target, exactly two human players, LAN/Wi-Fi/hotspot-first coop, preserve host-authoritative networking and persistence, prefer the smallest coherent change, run `bash tools/run-core-tests.sh`, run Android assembly when app/build files change, never weaken tests to pass, and never commit generated APK artifacts.

- [ ] **Step 3: Add Copilot repository instructions**

Create `.github/copilot-instructions.md` summarizing project structure (`core/`, `app/`, `tools/`), JDK 17, Gradle 9.5, Android SDK 37 verification target, and required test/build commands.

- [ ] **Step 4: Add operations documentation**

Document lifecycle labels (`agent:ready`, `agent:working`, `agent:blocked`, `agent:done`, `agent:stop`), the global pause variable, the optional `COPILOT_AGENT_TOKEN` prerequisite, the no-auto-merge rollout policy, and the exact recovery procedure for a blocked issue.

- [ ] **Step 5: Commit**

Commit message: `docs: add autonomous task contract and controls`

---

### Task 4: Safe queue claim and Copilot delegation workflow

**Files:**
- Create: `.github/workflows/autonomous-queue.yml`

**Interfaces:**
- Consumes: open Issues labeled `agent:ready`, `vars.AUTONOMOUS_DEVELOPMENT_PAUSED`, optional `secrets.COPILOT_AGENT_TOKEN`, and `tools/agent_queue.py`.
- Produces: one of three outcomes per run: clean no-op, claimed/delegated Issue (`agent:working`), or blocked Issue (`agent:blocked` + diagnostic comment).

- [ ] **Step 1: Add triggers, permissions, and concurrency**

Use `workflow_dispatch` plus an hourly schedule. Set concurrency group `grand-line-duo-autonomous-queue` with `cancel-in-progress: false`. Grant only `contents: read` and `issues: write` to `GITHUB_TOKEN`; the Copilot assignment call must explicitly use `COPILOT_AGENT_TOKEN`.

- [ ] **Step 2: Implement paused/no-work paths**

Exit successfully before any mutations when the global pause variable equals `true`. Fetch candidate Issues with `gh issue list --state open --label agent:ready --json number,title,body,labels --limit 100`, then pipe the result to `python3 tools/agent_queue.py select`. Exit successfully if no Issue is selected.

- [ ] **Step 3: Recheck and validate before claim**

Fetch the selected Issue again immediately before mutation. Confirm it is still open, still has `agent:ready`, and does not have `agent:stop`. Pipe it to `python3 tools/agent_queue.py validate`. Invalid Issues must have `agent:ready` removed, `agent:blocked` added, and receive a comment listing validation errors.

- [ ] **Step 4: Claim exactly one Issue**

For a valid Issue, remove `agent:ready`, add `agent:working`, and comment that the autonomous queue claimed the task. Because workflow concurrency serializes runs, no second queue cycle may mutate another Issue until the current cycle finishes.

- [ ] **Step 5: Delegate to Copilot or fail closed**

If `COPILOT_AGENT_TOKEN` is empty, replace `agent:working` with `agent:blocked` and comment that a user-to-server Copilot token is required. If present, call GitHub's REST Issue assignee endpoint with `assignees: ["copilot-swe-agent[bot]"]` and `agent_assignment` targeting this repository and `main`, using the repository custom agent `grand-line-builder` when supported. On any non-2xx response, transition to `agent:blocked` and record the HTTP/API diagnostic without printing the token.

- [ ] **Step 6: Keep merge disabled**

The workflow must not merge PRs, update `main`, or mark an Issue `agent:done`. This rollout ends after successful delegation.

- [ ] **Step 7: Commit**

Commit message: `feat: add safe autonomous issue queue`

---

### Task 5: Pull request, verification, and rollout checkpoint

**Files:**
- Modify only if CI finds defects in the files above.

**Interfaces:**
- Consumes: feature branch `agent/autonomous-development-rollout`.
- Produces: reviewable PR into `main` with automation-policy CI evidence and no autonomous merge.

- [ ] **Step 1: Run policy/unit verification**

Run: `python3 -m unittest tools/test_agent_queue.py -v`

Expected: all tests PASS.

- [ ] **Step 2: Verify existing gameplay/core suite remains untouched**

Run: `bash tools/run-core-tests.sh`

Expected: existing core/gameplay suite PASS.

- [ ] **Step 3: Open PR to `main`**

PR title: `feat: bootstrap safe autonomous development queue`

The PR body must state that this is rollout stages 1-2 only, autonomous merge is intentionally disabled, and `COPILOT_AGENT_TOKEN` is an optional one-time repository secret required for delegation.

- [ ] **Step 4: Inspect CI**

Confirm autonomous policy tests pass. If Android/core workflows are not triggered because gameplay files did not change, note that explicitly rather than treating absence as a failure.

- [ ] **Step 5: Do not auto-merge this rollout PR**

Leave the PR ready for review/merge after checks. Stage 3/4 finalization and autonomous merge will be a separate change after queue/delegation behavior is proven.
