# QR-Scan Pairing in Setup — Design

**Wave:** Quick-wins (from `docs/ideas/2026-07-16-competitive-refresh.md`, self-hosted onboarding). **Branch:** `feature/qr-scan-pairing` (off `dev`).

**Goal:** Add a "Scan QR" path to the setup screen so a user pairs by scanning a QR that encodes the gateway URL + credentials, instead of hand-typing a long `ts.net` URL + password. Fully client-only.

**Positioning:** The self-hosted onboarding today is a manual paste of `http://100.x.x.x:9119` + username/password (or a loopback token). Scanning kills the error-prone paste. The QR is produced out-of-band for now (a dashboard QR *generator* is a separate fork/SPA follow-up, explicitly out of scope here).

**Constraints:** Kotlin / Compose / Material3 / Hilt, per-tenant accent. **Client-only** — no gateway changes. Standing repo constraints (no AI attribution; gitleaks before every push; tenant isolation; `main` only via approved PR).

**Key auth fact (from the gateway audit):** the loopback session token is **useless off-device** — any non-loopback bind forces the auth gate on, so a phone authenticates with **username + password** (`POST /auth/password-login`, already implemented via `GatedAuth.probeLogin`). A pairing QR's primary payload is therefore **URL + username + password**; `token` is optional (loopback/adb cases only).

---

## Scope (locked)

- **In:** an in-app QR *scanner* on the setup screen; a versioned JSON payload; prefill + auto-Test + explicit Save; CAMERA permission; a new scanner dependency.
- **Out:** dashboard QR *generation* (fork/SPA follow-up); mDNS/LAN auto-discovery (needs gateway `zeroconf` + non-loopback bind); Tailscale auto-discovery; a `hermes://` deep-link scheme (separate roadmap item).

---

## Architecture

Three units: a pure payload parser, a ViewModel entry point, and the setup-screen UI (scanner + permission glue). The parser and the ViewModel apply-logic are pure/unit-tested; the camera/ZXing launch is Android glue verified on-device.

### 1. Scanner library — ZXing embedded

`com.journeyapps:zxing-android-embedded` (pulls `com.google.zxing:core`). Chosen because it is **offline and Google-Play-Services-free** (the app is currently GMS-free; a self-hosted/privacy audience may run no-GMS ROMs), small, and integrates via a one-shot `ScanContract`/`ScanOptions` ActivityResult. Requires the `CAMERA` permission.

*Alternatives considered:* CameraX + ML Kit barcode (in-Compose scanner, but more code + a larger bundled model); GMS `play-services-code-scanner` (no CAMERA permission, but requires Play Services — rejected to keep no-GMS support).

### 2. Payload — versioned JSON — `ui/setup/PairingPayload.kt` (new)

```kotlin
@Serializable
data class PairingPayload(
    val v: Int = 0,
    val url: String = "",
    val token: String = "",
    val username: String = "",
    val password: String = "",
)

/** Parse a scanned QR string; null if it isn't a valid v1 Hermes pairing code. */
fun parsePairingPayload(raw: String): PairingPayload?
```
Rules: parse `raw` as JSON with a lenient `Json { ignoreUnknownKeys = true }`; return null when it isn't an object, `v != 1`, or `url` is blank; otherwise the payload. Never throws — malformed input → null. Example valid payload:
```json
{ "v": 1, "url": "https://andrews-macbook.tailc63a9b.ts.net", "username": "andrew", "password": "…" }
```

### 3. ViewModel — `ui/setup/SetupViewModel.kt` (modify)

Add a `scanError: String?` field to `SetupUiState` and an entry point:
```kotlin
fun applyPairing(raw: String) {
    val p = parsePairingPayload(raw)
    if (p == null) {
        _state.value = _state.value.copy(scanError = "Not a Hermes pairing code")
        return
    }
    _state.value = _state.value.copy(
        url = p.url, token = p.token, username = p.username, password = p.password,
        scanError = null, testResult = null,
    )
    test() // reuse the existing probe; sets testResult to "Connected"/"Unreachable"
}
```
`test()` and `save()` are unchanged. The scan populates the same fields the user could type, so the existing verify/persist path is reused end to end. (A `clearScanError()` may be added to dismiss the error on edit.)

### 4. UI — `ui/setup/SetupScreen.kt` (modify)

- A **"Scan QR"** button (per-tenant accent styling) above/beside the fields.
- A CAMERA permission launcher (`rememberLauncherForActivityResult(RequestPermission)`); on grant → launch the scanner; on denial → keep manual entry + a short inline rationale (no hard block).
- A ZXing scan launcher (`rememberLauncherForActivityResult(ScanContract())` with `ScanOptions().setDesiredBarcodeFormats(QR_CODE).setBeepEnabled(false)`); on a non-null result → `vm.applyPairing(result.contents)`.
- Surface `scanError` inline near the button; the prefilled fields + `testResult` show the outcome. The user reviews, then taps the existing **Save & continue**.

### 5. Files

| Action | Path | Responsibility |
|--------|------|----------------|
| Modify | `gradle/libs.versions.toml` | ZXing embedded version + library alias |
| Modify | `app/build.gradle.kts` | `implementation(libs.zxing.embedded)` |
| Modify | `app/src/main/AndroidManifest.xml` | `<uses-permission android:name="android.permission.CAMERA" />` |
| Create | `app/src/main/java/com/hermes/client/ui/setup/PairingPayload.kt` | payload model + pure `parsePairingPayload` |
| Modify | `app/src/main/java/com/hermes/client/ui/setup/SetupViewModel.kt` | `scanError` state + `applyPairing` |
| Modify | `app/src/main/java/com/hermes/client/ui/setup/SetupScreen.kt` | Scan-QR button, CAMERA permission, ZXing launcher |
| Create | `app/src/test/java/com/hermes/client/ui/setup/PairingPayloadTest.kt` | pure parser tests |
| Create | `app/src/test/java/com/hermes/client/ui/setup/SetupViewModelTest.kt` | `applyPairing` populate + scanError |

## Data flow

```
[Scan QR] → CAMERA permission → ZXing ScanContract → result.contents (raw string)
     → vm.applyPairing(raw) → parsePairingPayload(raw)
          null → scanError = "Not a Hermes pairing code"
          ok   → populate url/token/username/password → test() → testResult
     → user reviews prefilled fields → [Save & continue] → store.save(GatewayConfig(...))
```

## Error handling

- Malformed / non-Hermes / wrong-version / no-url QR → `scanError` message; fields untouched.
- Scan cancelled (`result.contents == null`) → no-op.
- CAMERA permission denied → manual entry remains fully usable; inline rationale; no crash.
- Unreachable after auto-Test → the existing `testResult = "Unreachable"` (user can fix the fields or re-scan).

## Testing

**Pure `PairingPayloadTest`:** valid gated payload (url+username+password); valid token payload (url+token); malformed JSON → null; missing/`v != 1` → null; blank `url` → null; unknown extra keys ignored.

**`SetupViewModelTest`:** `applyPairing(validJson)` populates url/username/password and clears `scanError`, and triggers the probe (mock `rest.statusFor`/`gatedAuth.probeLogin` → asserts `testResult`); `applyPairing(garbage)` sets `scanError` and leaves fields blank.

The CAMERA permission flow and the ZXing capture Activity are Android — no unit tests (repo style); verified on-device.

## On-device verification

Generate a QR encoding a valid `{v:1,url,username,password}` payload (any offline QR tool), open setup, tap **Scan QR**, grant camera, scan → confirm the fields prefill, the auto-Test runs, and **Save & continue** connects. Also: scan a random non-Hermes QR → "Not a Hermes pairing code"; deny the camera permission → manual entry still works.

## Build & gates

`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`: `:app:compileDebugKotlin`, `:app:testDebugUnitTest`, `:app:assembleBeta`. gitleaks before every push; PR into `dev`.
