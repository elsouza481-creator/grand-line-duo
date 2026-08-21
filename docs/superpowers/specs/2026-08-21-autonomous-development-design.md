# GRAND LINE DUO Autonomous Development Design

## Goal

Add a controlled autonomous-development subsystem around the existing GRAND LINE DUO repository so development can advance from a prioritized GitHub Issue backlog with minimal user intervention while protecting the `main` branch and requiring verification before integration.

## Scope

This design covers repository automation only. It does not change gameplay architecture, balance, art direction, networking behavior, or game content by itself.

The automation must:

- select one eligible development Issue at a time;
- prevent duplicate workers from claiming the same Issue;
- create an isolated branch for each task;
- run the repository's existing tests and Android build validation;
- open a Pull Request with a concise implementation summary and verification evidence;
- merge only after required checks pass and the change is not blocked by policy;
- advance the Issue state after merge;
- leave failed or ambiguous work in a blocked state instead of repeatedly retrying forever.

## Queue Model

GitHub Issues are the source of truth for work selection.

Required lifecycle labels:

- `agent:ready` — eligible for autonomous work;
- `agent:working` — currently claimed by one worker;
- `agent:blocked` — automation could not safely complete the task;
- `agent:done` — merged and complete.

Only Issues explicitly labeled `agent:ready` may be claimed. The worker handles at most one Issue per execution cycle.

Priority is deterministic: lowest Issue number first among Issues carrying `agent:ready`, unless a future priority label scheme is introduced deliberately.

## Claiming and Concurrency

The orchestration workflow uses GitHub Actions concurrency so only one autonomous-development cycle for this repository can hold the queue lock at a time.

When a cycle claims an Issue, it must atomically transition the Issue from `agent:ready` to `agent:working` before any code changes are attempted. If the Issue is no longer eligible when the worker rechecks it, the cycle exits without modifying code.

A task branch uses the format:

`agent/issue-<number>-<short-slug>`

No autonomous worker commits directly to `main`.

## Execution Contract

Each claimed Issue must contain enough information for an agent to act safely. The expected Issue body includes:

- desired outcome;
- acceptance criteria;
- important constraints;
- optional files or systems likely involved.

If the Issue is too vague, conflicts with repository rules, requires secrets or unavailable external services, or appears destructive, the worker must not guess. It marks the Issue `agent:blocked` and records the reason.

The agent should make the smallest coherent change that satisfies the acceptance criteria. Unrelated refactors are prohibited.

## Pull Request Policy

Successful implementation produces a Pull Request targeting `main`.

The PR body must include:

- source Issue reference;
- summary of changes;
- tests/build commands executed;
- pass/fail results;
- notable risks or limitations.

The PR must carry an automation-specific marker so the orchestration workflow can distinguish agent-created work from human-created PRs.

## Verification Gates

Before autonomous merge, all applicable repository checks must pass.

Minimum required verification:

1. core/unit test workflow passes;
2. Android project build succeeds for the configured verification target;
3. no required check is pending or failed;
4. the PR remains mergeable and still targets `main`;
5. the source Issue is still in `agent:working` state;
6. no blocking marker or manual stop label has been applied.

If any required verification fails, the PR remains unmerged and the Issue becomes `agent:blocked` with diagnostic context.

## Merge Safety

Autonomous merge is allowed only for PRs produced by this subsystem and only after all required checks pass.

The subsystem must never:

- force-push `main`;
- bypass failed required checks;
- merge a PR with merge conflicts;
- delete arbitrary repository files unless the Issue explicitly requires it and the change is clearly scoped;
- rewrite Git history;
- modify repository secrets;
- disable CI or branch-protection safeguards to make a build pass.

After a successful merge, the Issue transitions from `agent:working` to `agent:done` and is closed.

## Failure and Recovery

Failures are fail-closed.

A cycle must end in one of these states:

- no eligible Issue: clean exit;
- Issue claimed and PR created: wait for verification/merge flow;
- Issue blocked before PR: label `agent:blocked` and explain why;
- PR verification failed: keep PR open, label Issue `agent:blocked`, and record failure details;
- PR merged: label `agent:done` and close Issue.

No workflow may recursively trigger itself indefinitely. Automated commits and PR events must be guarded with actor, branch, label, or marker checks so a completed cycle cannot create an uncontrolled loop.

## Workflow Components

The implementation should be split into focused repository components:

1. **Queue/orchestrator workflow** — selects and claims one `agent:ready` Issue and starts an autonomous task run.
2. **Verification workflow integration** — reuses or extends the existing core and Android build checks instead of duplicating build logic unnecessarily.
3. **Merge/finalization workflow** — merges only verified agent PRs and finalizes Issue labels/state.
4. **Issue template** — creates sufficiently specified autonomous tasks with acceptance criteria and constraints.
5. **Operational documentation** — explains how to add work to the queue, stop automation, inspect blocked tasks, and resume them safely.

Where GitHub-native capabilities are insufficient to perform code generation by themselves, the design may integrate an authorized coding agent/service. That integration must use least-privilege credentials and must not expose secrets in logs or committed files.

## Stop Controls

The repository must provide a simple manual stop mechanism. A repository-level or Issue/PR-level stop marker should prevent new task claims and prevent autonomous merge of an affected task.

A stopped or blocked task must require an explicit state change before it can re-enter `agent:ready`.

## Testing Strategy

The automation itself must be tested without risking `main`.

Verification should include:

- syntax validation of all workflow YAML files;
- a dry-run/no-op path with no eligible Issues;
- queue-selection behavior with multiple candidate Issues;
- concurrency/duplicate-claim protection;
- blocked-task behavior;
- successful PR finalization path;
- failed-check path that demonstrably does not merge;
- confirmation that normal human PRs are ignored by autonomous merge logic.

Gameplay tests and Android build checks remain required for code changes produced by the autonomous worker.

## Rollout

Rollout is incremental:

1. install labels, Issue template, documentation, and non-destructive queue selection;
2. verify claiming and branch/PR creation on a low-risk test Issue;
3. enable verification-driven finalization;
4. enable autonomous merge only after the first stages are proven reliable.

Until stage 4 is deliberately enabled, the system may create verified PRs but must not merge them automatically.

## Success Criteria

The subsystem is successful when a properly specified `agent:ready` Issue can progress through claim, isolated implementation, verification, PR creation, safe merge, and Issue closure without the user repeatedly sending `continue`, while ambiguous or failing work stops safely in `agent:blocked` rather than damaging the repository or looping indefinitely.
