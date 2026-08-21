# Autonomous Queue Stage 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the first safe rollout stage of the autonomous-development design: issue specification, deterministic queue selection, dry-run/live claim workflow, and operational documentation without autonomous code generation or merge.

**Architecture:** A small Python selector receives the JSON returned by `gh issue list`, ignores stopped/blocked items, and deterministically returns the lowest eligible issue number. A manually dispatched GitHub Actions workflow creates required lifecycle labels, queries `agent:ready` issues, runs the selector, and either reports the candidate in dry-run mode or atomically transitions it to `agent:working`. A form-based issue template and runbook make tasks sufficiently specified and document stop/recovery controls.

**Tech Stack:** GitHub Actions, GitHub CLI, Python 3 standard library, GitHub Issue Forms.

**Spec:** `docs/superpowers/specs/2026-08-21-autonomous-development-design.md`

## Global Constraints

- No autonomous worker commits directly to `main`.
- Only Issues explicitly labeled `agent:ready` may be claimed.
- The worker handles at most one Issue per execution cycle.
- Priority is deterministic: lowest Issue number first among eligible Issues.
- Fail closed on ambiguity or stop markers.
- Stage 1 must not autonomously merge pull requests.

---

### Task 1: Deterministic queue selector

**Files:**
- Create: `tools/agent_queue/select_issue.py`
- Create: `tools/agent_queue/test_select_issue.py`
- Create: `.github/workflows/agent-queue-ci.yml`

**Interfaces:**
- Consumes: JSON array from stdin containing issue objects with `number` and `labels`.
- Produces: selected issue number on stdout, or an empty string when no candidate is eligible.

- [ ] Write tests for selecting the lowest `agent:ready` issue, ignoring `agent:stop` and `agent:blocked`, and returning no selection for an empty eligible set.
- [ ] Run the tests and confirm they fail because the selector does not yet exist.
- [ ] Implement `select_issue.py` with standard-library JSON parsing only.
- [ ] Run the tests and confirm they pass.
- [ ] Add a focused CI workflow that runs the selector tests on pull requests and pushes touching the queue tooling/workflow.

### Task 2: Safe claim workflow and issue contract

**Files:**
- Create: `.github/workflows/agent-queue.yml`
- Create: `.github/ISSUE_TEMPLATE/agent-task.yml`
- Create: `docs/AUTONOMOUS_DEVELOPMENT.md`

**Interfaces:**
- Consumes: open GitHub Issues labeled `agent:ready`.
- Produces: dry-run candidate report or a single transition from `agent:ready` to `agent:working`.

- [ ] Add a manual `workflow_dispatch` workflow with `dry_run` defaulting to true and repository-level concurrency.
- [ ] Ensure lifecycle labels exist before selection.
- [ ] Query open `agent:ready` issues, invoke `select_issue.py`, re-fetch the selected issue before mutation, and abort if its labels changed.
- [ ] In live mode only, add `agent:working` and remove `agent:ready`; never merge or create code changes.
- [ ] Add an Issue Form requiring desired outcome, acceptance criteria, constraints, and affected systems.
- [ ] Document queueing, dry-run, live claim, stop controls, blocked-task recovery, and the deliberate absence of autonomous merge in stage 1.

### Task 3: Verification and handoff

**Files:**
- Review all files above.

- [ ] Run the Python unit suite.
- [ ] Validate the workflow structure and permissions by inspection and GitHub Actions CI.
- [ ] Open a pull request to `main` with implementation summary and verification evidence.
- [ ] Do not merge unless checks are green and the change remains limited to stage 1.