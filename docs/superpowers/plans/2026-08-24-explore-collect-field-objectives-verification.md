# EXPLORE + COLLECT Field Objectives — Verification Record

Companion verification record for `2026-08-24-explore-collect-field-objectives.md`.

## Implementation source verification

- Implementation source head before the temporary Android workflow: `a19929ace14be21e0a8a780e8b76be39cda3eb65`.
- Core CI #482:
  - run id: `32726908015`
  - job id: `97430142834`
  - PR merge SHA checked out by Actions: `0ad212b7e96914597f7a643ad32523d12a3c7894`
  - exact result: `RESULT 432/432 passed`
- The suite explicitly passed `P2 field quests reconnect after failure converge and reward once over real TCP`, plus HUNT, quest BOSS, PvP, arc/scenario, persistence/hash, SOLO and LAN regressions.

## Compatibility invariants

- `PROTOCOL_VERSION = 5`.
- `GameplayWireCommand.QuestAction` encode/decode subtype remains `9`.
- `GameplayWireCommand.DuelAction` encode/decode subtype remains `10`.
- `WorldStateCodec.CURRENT_VERSION = 11`.
- `CombatEngine.kt` on the feature branch and `main` are byte-identical: blob `f9b3e0c8fb1941ed462580c5dfd2f3a1b73260f0`.
- `QuestBossFactory.kt` remains byte-identical to baseline `00267140668b90c75a3268c3b72e3b21ebefea80`: blob `8c4c98a5a2cd36c8347d030f6be6347cc2b60572`.
- `QuestBossCoordinator.kt` remains byte-identical to baseline `00267140668b90c75a3268c3b72e3b21ebefea80`: blob `00104fd636316ea1ca3e8770476f0c9274ddf22e`.
- Compare `926a89794f9fc1ac9be91c2496e98372cb7f42fe` -> `a19929ace14be21e0a8a780e8b76be39cda3eb65` contains no changes to `GameplayWireCommand.kt`, `WireCodec.kt`, `Protocol.kt` or `WorldStateCodec.kt`.

## Android current-source verification

- Build-tested feature source head: `158ff57774864230565ec296ae7efe718c7f06dc`.
- PR merge SHA checked out by Actions: `e4e368483acceb22ae43c19ae25e59986ddba01d`.
- Android verify run #6:
  - run id: `32727215797`
  - job id: `97431111617`
  - `bash tools/run-core-tests.sh` -> exact result `RESULT 432/432 passed`
  - `gradle --no-daemon --stacktrace :app:assembleDebug` -> exact result `BUILD SUCCESSFUL in 45s`
  - non-empty `app/build/outputs/apk/debug/app-debug.apk` verified
  - APK SHA-256: `1a0f9159a67c8f492fe3676cdaf0dd237b8c17c6c6a28fda8cfe3ef55f049057`
- Android build emitted only the already-known Kotlin Java-type mismatch warnings in `GameSessionCoordinator.kt` and `LanShellSessionCoordinator.kt`; no build errors occurred.

## Source-head Core CI during Android verification

- Core CI #483:
  - source head: `158ff57774864230565ec296ae7efe718c7f06dc`
  - run id: `32727216991`
  - job id: `97431114527`
  - PR merge SHA: `e4e368483acceb22ae43c19ae25e59986ddba01d`
  - exact result: `RESULT 432/432 passed`

## Post-build cleanup evidence

- Temporary workflow deletion head: `9b967047b213868d1ef17ac61373509377530817`.
- Compare `158ff57774864230565ec296ae7efe718c7f06dc` -> `9b967047b213868d1ef17ac61373509377530817` contains exactly one changed file: removal of `.github/workflows/pr-current-source-android-verify.yml`.
- That compare contains no file under `app/` or `core/`.
- Core CI #484 on cleanup head:
  - run id: `32727434003`
  - job id: `97431795019`
  - PR merge SHA: `de5d0d8cc892c2b7dd385e3c30e9da70b6491193`
  - exact result: `RESULT 432/432 passed`

The final documentation-only verification commit is intentionally followed by one more exact-head Core CI. PR #4 must remain open and unmerged.