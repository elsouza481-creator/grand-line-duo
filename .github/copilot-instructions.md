# GRAND LINE DUO repository instructions

GRAND LINE DUO is an Android/mobile, top-down open-world RPG with exactly two human players and local-first cooperative play over Wi-Fi/hotspot.

## Repository layout

- `core/` contains gameplay/domain logic intended to remain independently testable.
- `app/` contains the Android application layer and presentation/integration code.
- `tools/` contains repository verification and development utilities.
- `.github/workflows/` contains CI/build automation.

## Project invariants

- Keep cooperative sessions host-authoritative.
- Preserve reconnection and persisted player/world state.
- Do not introduce an internet dependency for normal LAN gameplay.
- Keep changes narrowly scoped to the assigned issue.
- Do not weaken tests, CI, branch safety, or persistence/networking guarantees to make a change pass.
- Never commit secrets, tokens, APKs, or build output.
- Never commit directly to `main` from autonomous work.

## Verification environment

- JDK: 17
- Gradle verification version: 9.5.0
- Android SDK verification target: 37 / configured project compile target
- Core/gameplay tests: `bash tools/run-core-tests.sh`
- Android debug assembly: `gradle --no-daemon --stacktrace :app:assembleDebug`

Run the core/gameplay suite for behavior changes. Run Android assembly when application or build configuration files change. Include verification commands and results in the pull request description.
