# GRAND LINE DUO autonomous development

This repository uses GitHub Issues as the controlled queue for autonomous development. The first rollout intentionally stops after safe issue selection, validation, claiming, and coding-agent delegation. Autonomous merge is disabled until the queue and verification flow are proven.

## Lifecycle labels

- `agent:ready` — eligible for autonomous work.
- `agent:working` — claimed and delegated.
- `agent:blocked` — automation could not safely continue.
- `agent:done` — reserved for a later verified-finalization stage.
- `agent:stop` — manual stop marker for a specific task.

The queue handles at most one issue per execution and chooses the lowest-number open `agent:ready` issue.

## Queueing work

Create an issue with the **Autonomous development task** form. The issue must contain:

- Desired outcome
- Acceptance criteria
- Constraints

The form marks new tasks `agent:ready` once the label exists in the repository. The queue workflow also ensures lifecycle labels exist each time it runs.

Only use `agent:ready` when the task is scoped, testable, non-destructive, and does not depend on unavailable credentials or external services.

## Global pause

Set the repository Actions variable `AUTONOMOUS_DEVELOPMENT_PAUSED` to `true` to prevent new claims. Any other value, including an unset variable, leaves the queue enabled.

The pause is checked before issue mutations. It does not merge or alter already-open pull requests.

## Coding-agent delegation

GitHub Copilot cloud agent delegation is optional in this rollout and uses the repository secret:

`COPILOT_AGENT_TOKEN`

The token must be a user token accepted by GitHub for Copilot issue assignment and must have the permissions required by GitHub's Copilot cloud-agent API. The workflow never prints the token.

When the secret is absent or GitHub rejects delegation, the queue fails closed: it removes `agent:working`, applies `agent:blocked`, and comments a diagnostic on the issue.

Repository-wide Copilot instructions live in `.github/copilot-instructions.md`. The specialized agent profile is `.github/agents/grand-line-builder.agent.md`.

## Per-issue stop

Add `agent:stop` to an issue to prevent it from being claimed. If a queued issue has `agent:stop`, remove `agent:ready` or leave it stopped until the task is intentionally resumed.

## Blocked tasks

For an `agent:blocked` issue:

1. Read the latest automation comment and resolve the stated cause.
2. Make sure Desired outcome, Acceptance criteria, and Constraints are complete.
3. Remove `agent:stop` if the stop condition is no longer needed.
4. Remove `agent:blocked`.
5. Add `agent:ready` to place the task back in the queue.

Do not repeatedly requeue a task whose underlying blocker has not changed.

## Verification and merge policy

This rollout does **not** auto-merge pull requests. Existing verification remains authoritative for product code:

- core/gameplay verification: `bash tools/run-core-tests.sh`
- Android build verification: JDK 17, Gradle 9.5.0, Android SDK 37 target, `:app:assembleDebug`

The next rollout stage may add verified finalization only after safe queue/delegation behavior has been demonstrated on low-risk tasks.

## Security boundaries

Autonomous workflows must never force-push `main`, bypass failed checks, rewrite history, modify repository secrets, disable CI safeguards, or commit secrets/build artifacts. Ambiguous or failing work is blocked instead of guessed through.
