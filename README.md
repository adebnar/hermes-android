# Hermes for Android

A native Android client for the **Hermes agent gateway** — a phone-friendly companion to
the Hermes Desktop app. It connects to a remote Hermes gateway over your private network
(Tailscale) and gives you full chat plus the management surface: sessions, models,
profiles, scheduled jobs, usage analytics, messaging integrations, and settings.

Built with Kotlin and Jetpack Compose (Material 3).

---

## Features

- **Chat** — streaming responses, model picker, slash commands (`/…`) with an inline
  command palette, `@` file mentions/path completion, image attachments, tool-call
  approval and clarification prompts.
- **Sessions** — grouped by workspace, with pinning and a session-admin view.
- **Models & profiles** — switch the active model or tenant profile on the fly; the active
  profile is shown in the chat top bar.
- **Cron** — list, create, edit, pause/resume, run-now, and delete scheduled jobs, with
  next-run times and run history.
- **Usage** — daily token chart and per-model breakdown.
- **Messaging** — enable/disable platform integrations with a guided setup flow for the
  required credentials.
- **Settings** — appearance (System/Light/Dark + tool-call verbosity), memory, MCP
  servers, and API keys/environment variables. Configuration edits are written to the
  live gateway config.
- **Reliability** — automatic reconnect with offline banner and manual retry; expired
  tokens route back to the setup screen.

---

## Architecture

| Layer | Notes |
|-------|-------|
| UI | Jetpack Compose + Material 3, `ModalNavigationDrawer` wrapping a Navigation-Compose `NavHost` |
| State | MVVM — `ViewModel` + `StateFlow`, `collectAsStateWithLifecycle` |
| DI | Hilt |
| Networking | OkHttp — REST over HTTP and a WebSocket JSON-RPC stream to the gateway |
| Local storage | DataStore (device-local prefs: token, theme, tool-call display) |

Package layout under `app/src/main/java/com/hermes/client/`:

```
data/        network clients, repositories, DTOs
di/          Hilt modules
domain/      app models (ChatMessage, Role, …)
ui/          one package per screen (chat, sessions, cron, usage,
             messaging, models, profiles, settings, setup, admin, tools, nav, theme)
```

The launcher icon is an adaptive icon at `app/src/main/res/mipmap-anydpi-v26/`: the
Hermes Agent mascot (black line art on white, matching the desktop app). The foreground
is a PNG mipmap (`mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher_foreground.png`) rather than a
vector — the source artwork is too path-dense for the runtime vector parser, which fails
to inflate it and falls back to the default system icon. The background is a solid-white
vector in `app/src/main/res/drawable/`.

---

## Requirements

- Android Studio (bundled JBR / JDK 21)
- Android SDK with `compileSdk` 36 / build-tools 36.x
- A reachable Hermes gateway and a session token

Toolchain is pinned in `gradle/libs.versions.toml`: AGP 8.13.2, Kotlin 2.2.21,
Gradle 8.14.5. `minSdk` 26, `targetSdk` 36.

---

## Building

### Debug

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

### Release (signed)

Release signing reads a **gitignored** `keystore.properties` at the repo root:

```properties
storeFile=keystore/hermes-release.jks
storePassword=********
keyAlias=hermes
keyPassword=********
```

If that file is absent (fresh clone, CI without secrets) the release build still
succeeds but is left unsigned. To create a keystore:

```bash
keytool -genkeypair -v \
  -keystore keystore/hermes-release.jks \
  -alias hermes -keyalg RSA -keysize 2048 -validity 10000
```

Then build:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

The keystore, `keystore.properties`, and all `*.apk`/`*.aab`/`*.jks` artifacts are
gitignored — signing secrets never enter the repo.

### Install

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

---

## First-run setup

On first launch the app shows a **Setup** screen. Enter:

1. **Gateway URL** — the base URL of your Hermes gateway (e.g. its Tailscale address).
2. **Session token** — sent as the `X-Hermes-Session-Token` header.

Credentials are stored locally via DataStore. An expired/invalid token (HTTP 401) routes
you back to Setup automatically.

---

## Tests

```bash
./gradlew :app:testDebugUnitTest          # JVM unit tests
./gradlew :app:connectedDebugAndroidTest  # instrumented (device/emulator)
```

---

## License

Private project — all rights reserved.
