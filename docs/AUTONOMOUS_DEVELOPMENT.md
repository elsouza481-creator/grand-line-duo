# GRAND LINE DUO Autonomous Development Operations

This document covers stage 1 of the autonomous-development rollout. Stage 1 manages a safe Issue queue only. It does **not** generate code, create implementation branches, open pull requests, or merge changes automatically.

## Lifecycle labels

- `agent:ready` — eligible for queue selection.
- `agent:working` — claimed by the queue and no longer eligible for another worker.
- `agent:blocked` — cannot proceed safely until a human resolves the blocker.
- `agent:done` — reserved for completed and merged autonomous work in later rollout stages.
- `agent:stop` — manual stop marker. An Issue carrying this label is never eligible even if it also has `agent:ready`.

The queue workflow creates or refreshes these labels before every manual run.

## Adding work to the queue

Create an Issue with the **Autonomous development task** form. The form requires a desired outcome, acceptance criteria, and constraints so an agent has enough information to work without inventing requirements.

One Issue should represent one focused branch/PR-sized change. Avoid combining unrelated gameplay, UI, networking, and infrastructure changes into a single task.

## Checking the next task safely

Open **Actions → Autonomous development queue → Run workflow** and keep `dry_run` enabled. The workflow:

1. ensures lifecycle labels exist;
2. lists open Issues carrying `agent:ready`;
3. excludes any Issue carrying `agent:stop`, `agent:blocked`, `agent:working`, or `agent:done`;
4. chooses the lowest Issue number deterministically;
5. re-fetches the Issue before any possible mutation;
6. reports the candidate without changing labels.

If no Issue is eligible, the run exits cleanly.

## Claiming one task

Run the same workflow with `dry_run` disabled. After the same selection and revalidation steps, the workflow changes exactly one Issue from `agent:ready` to `agent:working`.

The workflow has repository-level concurrency enabled, so two queue runs cannot intentionally claim different snapshots at the same time. Revalidation immediately before mutation provides an additional fail-closed guard.

## Stopping work

Add `agent:stop` to an Issue before it is claimed. The selector will ignore it even if `agent:ready` remains present.

For a task already carrying `agent:working`, add `agent:stop` and remove `agent:working` only after the active worker has been stopped or its branch state has been inspected. Later rollout stages must also check the stop marker before PR creation or merge.

## Blocking and resuming

When a task cannot be completed safely, remove `agent:working`, add `agent:blocked`, and record the reason in the Issue.

To resume after the blocker is resolved:

1. update the Issue body/comments with the resolution;
2. remove `agent:blocked` and `agent:stop` if present;
3. add `agent:ready` again;
4. run the queue in dry-run mode first to confirm selection.

## Verification

Queue-selection behavior is covered by `tools/agent_queue/test_select_issue.py`. The `Autonomous queue CI` workflow runs these tests whenever queue tooling, workflows, or the task template change.

The selector deliberately uses only the Python standard library. It accepts the JSON array produced by `gh issue list --json number,labels`, prints the lowest eligible Issue number, and prints nothing when no Issue is eligible.

## Rollout boundary

Stage 1 ends at safe task claiming. Autonomous code generation, isolated task branches, PR creation, verification-driven finalization, and autonomous merge remain disabled until later stages are implemented and proven separately. This preserves the fail-closed rollout required by the design specification.
