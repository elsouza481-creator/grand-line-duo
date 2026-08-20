# Android APK build

The Android module is intentionally thin: it compiles the verified Kotlin core directly and adds a platform-only launcher UI for local-LAN hosting/discovery.

## Automated build (recommended)

Push this project to GitHub and run **Build Android APK** from the Actions tab. The workflow runs the core reliability suite first, then assembles the debug APK and uploads:

- `GRAND-LINE-DUO-debug.apk`
- `GRAND-LINE-DUO-debug.apk.sha256`

## Local build prerequisites

- JDK 17+
- Gradle 9.5+
- Android SDK Platform 37
- Android SDK Build Tools 36.0.0+

Then run:

```bash
gradle :app:assembleDebug
```

## Current shell behavior

- **Criar Aventura** starts the existing authoritative TCP host.
- P1 broadcasts a versioned/checksummed UDP discovery advertisement.
- **Encontrar Tripulação** listens on LAN, finds P1 and performs the existing protocol handshake as P2.
- Both devices only show connected state after the real handshake succeeds.
- Leaving the Activity closes sockets/executors cleanly.

This shell is an infrastructure APK, not the final art pass. The final anime 2D HD assets remain a later layer over the same core/network foundation.
