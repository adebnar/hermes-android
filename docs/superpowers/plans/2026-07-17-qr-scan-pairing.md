# QR-Scan Pairing in Setup — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Scan QR" path to the setup screen: scan a QR encoding the gateway URL + credentials, prefill the existing fields, auto-run the existing Test, then Save. Fully client-only.

**Architecture:** A pure JSON payload parser (`parsePairingPayload`), a `SetupViewModel.applyPairing` entry point that reuses the existing `test()`/`save()`, and a ZXing scan launcher on `SetupScreen`. Parser + ViewModel logic are pure/unit-tested; the camera/ZXing launch is Android glue verified on-device.

**Tech Stack:** Kotlin, Jetpack Compose, Material3, Hilt, kotlinx.serialization (existing), ZXing embedded (new).

**Spec:** `docs/superpowers/specs/2026-07-17-qr-scan-pairing-design.md`

## Global Constraints

- Client-only: no gateway changes. Scanned payload maps onto `GatewayConfig(baseUrl, token, username, password)` via the existing `store.save`.
- Primary payload is **URL + username + password** (the loopback token is useless off-device); `token` optional.
- Scanner = **ZXing embedded** (`com.journeyapps:zxing-android-embedded`) — offline, GMS-free. Needs the `CAMERA` permission (ZXing's `CaptureActivity` handles the runtime request itself; a denied permission yields a null scan result → manual entry still works).
- Setup is pre-connection → **no tenant accent**; use default Material styling for the Scan button.
- Tests: pure JUnit (`PairingPayloadTest`, `SetupViewModelTest`). No Compose/instrumentation tests.
- No AI/assistant attribution in commits or files.
- Build env: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
- Branch: `feature/qr-scan-pairing` (off `dev`; spec committed). All commits land here.

---

### Task 1: Pairing payload parser (pure)

**Files:**
- Create: `app/src/main/java/com/hermes/client/ui/setup/PairingPayload.kt`
- Test: `app/src/test/java/com/hermes/client/ui/setup/PairingPayloadTest.kt`

**Interfaces:**
- Produces: `@Serializable data class PairingPayload(v, url, token, username, password)` and `fun parsePairingPayload(raw: String): PairingPayload?` (null unless valid v1 with non-blank url; never throws).

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/hermes/client/ui/setup/PairingPayloadTest.kt`:
```kotlin
package com.hermes.client.ui.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingPayloadTest {
    @Test fun parses_gated_payload() {
        val p = parsePairingPayload("""{"v":1,"url":"https://h.ts.net","username":"a","password":"p"}""")!!
        assertEquals("https://h.ts.net", p.url)
        assertEquals("a", p.username)
        assertEquals("p", p.password)
        assertEquals("", p.token)
    }

    @Test fun parses_token_payload() {
        val p = parsePairingPayload("""{"v":1,"url":"http://127.0.0.1:9119","token":"tok"}""")!!
        assertEquals("tok", p.token)
        assertEquals("", p.username)
    }

    @Test fun ignores_unknown_keys() {
        val p = parsePairingPayload("""{"v":1,"url":"http://h","extra":"x"}""")!!
        assertEquals("http://h", p.url)
    }

    @Test fun rejects_malformed_json() {
        assertNull(parsePairingPayload("not json"))
        assertNull(parsePairingPayload("{bad"))
        assertNull(parsePairingPayload("\"https://h\"")) // a bare string, not an object
    }

    @Test fun rejects_wrong_or_missing_version() {
        assertNull(parsePairingPayload("""{"v":2,"url":"http://h"}"""))
        assertNull(parsePairingPayload("""{"url":"http://h"}""")) // v defaults to 0
    }

    @Test fun rejects_blank_url() {
        assertNull(parsePairingPayload("""{"v":1,"url":""}"""))
        assertNull(parsePairingPayload("""{"v":1}"""))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.hermes.client.ui.setup.PairingPayloadTest"`
Expected: FAIL — `PairingPayload`/`parsePairingPayload` unresolved.

- [ ] **Step 3: Write the implementation**

`app/src/main/java/com/hermes/client/ui/setup/PairingPayload.kt`:
```kotlin
package com.hermes.client.ui.setup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Credentials carried by a Hermes pairing QR. Primary payload is url + username + password. */
@Serializable
data class PairingPayload(
    val v: Int = 0,
    val url: String = "",
    val token: String = "",
    val username: String = "",
    val password: String = "",
)

private val pairingJson = Json { ignoreUnknownKeys = true }

/**
 * Parse a scanned QR string. Returns null (never throws) unless it is a valid v1 Hermes pairing
 * object with a non-blank url — so a random/non-Hermes QR is rejected cleanly.
 */
fun parsePairingPayload(raw: String): PairingPayload? =
    runCatching { pairingJson.decodeFromString<PairingPayload>(raw) }
        .getOrNull()
        ?.takeIf { it.v == 1 && it.url.isNotBlank() }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.hermes.client.ui.setup.PairingPayloadTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/setup/PairingPayload.kt \
        app/src/test/java/com/hermes/client/ui/setup/PairingPayloadTest.kt
git commit -m "feat: add pairing-QR payload parser"
```

---

### Task 2: SetupViewModel.applyPairing

**Files:**
- Modify: `app/src/main/java/com/hermes/client/ui/setup/SetupViewModel.kt`
- Test: `app/src/test/java/com/hermes/client/ui/setup/SetupViewModelTest.kt`

**Interfaces:**
- Consumes: `parsePairingPayload` (Task 1); existing `test()`, `SetupUiState`, injected `store`/`rest`/`gatedAuth`.
- Produces: `SetupUiState.scanError: String?`; `fun applyPairing(raw: String)`; `fun clearScanError()`.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/hermes/client/ui/setup/SetupViewModelTest.kt`:
```kotlin
package com.hermes.client.ui.setup

import com.hermes.client.data.auth.CredentialStore
import com.hermes.client.data.network.GatedAuth
import com.hermes.client.data.network.HermesRestApi
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {
    private val store = mockk<CredentialStore>(relaxed = true)
    private val rest = mockk<HermesRestApi>(relaxed = true)
    private val gatedAuth = mockk<GatedAuth>(relaxed = true)

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        every { store.load() } returns null
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = SetupViewModel(store, rest, gatedAuth)

    @Test fun applyPairing_populates_fields_from_valid_payload() {
        val vm = vm()
        vm.applyPairing("""{"v":1,"url":"https://h.ts.net","username":"a","password":"p"}""")
        val s = vm.state.value
        assertEquals("https://h.ts.net", s.url)
        assertEquals("a", s.username)
        assertEquals("p", s.password)
        assertNull(s.scanError)
    }

    @Test fun applyPairing_sets_scanError_and_leaves_fields_blank_on_garbage() {
        val vm = vm()
        vm.applyPairing("not a hermes code")
        val s = vm.state.value
        assertEquals("Not a Hermes pairing code", s.scanError)
        assertEquals("", s.url)
        assertEquals("", s.password)
    }

    @Test fun clearScanError_clears_it() {
        val vm = vm()
        vm.applyPairing("garbage")
        vm.clearScanError()
        assertNull(vm.state.value.scanError)
    }
}
```
(Assertions are on the synchronous state changes; the `test()` probe `applyPairing` triggers runs in `viewModelScope` and is not asserted here — its behavior is unchanged and already exercised by the existing Test button.)

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.hermes.client.ui.setup.SetupViewModelTest"`
Expected: FAIL — `scanError`/`applyPairing`/`clearScanError` unresolved.

- [ ] **Step 3: Modify the ViewModel**

In `SetupViewModel.kt`, add `scanError` to the state:
```kotlin
data class SetupUiState(
    val url: String = "",
    val token: String = "",
    val username: String = "",
    val password: String = "",
    val testResult: String? = null,
    val saved: Boolean = false,
    val scanError: String? = null,
)
```
And add these two functions to the `SetupViewModel` class (e.g. after `save()`):
```kotlin
    /** Apply a scanned pairing QR: prefill the fields and auto-run the existing probe. */
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
        test()
    }

    fun clearScanError() {
        if (_state.value.scanError != null) _state.value = _state.value.copy(scanError = null)
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.hermes.client.ui.setup.SetupViewModelTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/setup/SetupViewModel.kt \
        app/src/test/java/com/hermes/client/ui/setup/SetupViewModelTest.kt
git commit -m "feat: SetupViewModel.applyPairing populates fields from a scanned QR"
```

---

### Task 3: ZXing dependency + CAMERA permission + Scan-QR button

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/hermes/client/ui/setup/SetupScreen.kt`
- Test: none new (Android glue). Verified by compile + full suite + assembleBeta + Task 4.

**Interfaces:**
- Consumes: `SetupViewModel.applyPairing` + `SetupUiState.scanError` (Task 2).

- [ ] **Step 1: Add the ZXing version + library alias**

In `gradle/libs.versions.toml`, add under `[versions]` (e.g. after the existing entries):
```toml
zxingEmbedded = "4.3.0"
```
and under `[libraries]`:
```toml
zxing-embedded = { module = "com.journeyapps:zxing-android-embedded", version.ref = "zxingEmbedded" }
```

- [ ] **Step 2: Add the dependency**

In `app/build.gradle.kts`, in the `dependencies { }` block (next to the other `implementation(libs.*)` lines, e.g. after `implementation(libs.markdown.m3)`):
```kotlin
    implementation(libs.zxing.embedded)
```

- [ ] **Step 3: Add the CAMERA permission**

In `app/src/main/AndroidManifest.xml`, add after the `ACCESS_NETWORK_STATE` line:
```xml
    <uses-permission android:name="android.permission.CAMERA" />
```

- [ ] **Step 4: Add the Scan-QR button + launcher to SetupScreen**

In `app/src/main/java/com/hermes/client/ui/setup/SetupScreen.kt`, add imports:
```kotlin
import androidx.activity.compose.rememberLauncherForActivityResult
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
```
Inside `SetupScreen`, after `val state by vm.state.collectAsStateWithLifecycle()`, register the scan launcher:
```kotlin
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        // Null contents = the user cancelled or denied the camera; manual entry stays usable.
        result.contents?.let { vm.applyPairing(it) }
    }
```
Add the button + error text directly under the `Text("Connect to Hermes", …)` title (making scan the primary path), before the URL field:
```kotlin
        OutlinedButton(
            onClick = {
                scanLauncher.launch(
                    ScanOptions().apply {
                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        setPrompt("Scan the Hermes pairing QR")
                        setBeepEnabled(false)
                        setOrientationLocked(false)
                    },
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Scan QR") }
        state.scanError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
```
(The existing URL/username/password/token fields, Test/Save row, and `testResult` remain unchanged below.)

- [ ] **Step 5: Compile**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (Resolves the ZXing dependency + `ScanContract`/`ScanOptions`.)

- [ ] **Step 6: Run the full unit-test suite**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 0 failures (includes Tasks 1–2 suites).

- [ ] **Step 7: Assemble the beta variant**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleBeta`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/AndroidManifest.xml \
        app/src/main/java/com/hermes/client/ui/setup/SetupScreen.kt
git commit -m "feat: add Scan-QR pairing button to setup (ZXing + CAMERA)"
```

---

### Task 4: On-device verification

**Files:** none (manual, on the emulator or a connected device).

No automated Compose/camera test (per Global Constraints); this manual pass is the acceptance gate for the scanner glue.

- [ ] **Step 1: Prepare a test QR**

Encode this JSON as a QR with any offline QR tool (replace with a reachable gateway + real credentials):
```json
{"v":1,"url":"http://10.0.2.2:9119","username":"andrew","password":"<password>"}
```
(For an emulator, `10.0.2.2` reaches the host; or use a `token` payload against a loopback-reachable gateway.)

- [ ] **Step 2: Install the beta build**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:installBeta`
Expected: `Installed on 1 device`.

- [ ] **Step 3: Verify the happy path**

Launch the app to the setup screen → tap **Scan QR** → grant camera → scan the QR. Confirm: the URL/username/password fields **prefill**, the auto-Test shows "Connected", and **Save & continue** connects into the app.

- [ ] **Step 4: Verify the error + denial paths**

Scan a random non-Hermes QR → confirm **"Not a Hermes pairing code"** appears and the fields are untouched. Re-open Scan QR and **deny** the camera permission (or cancel) → confirm no crash and the manual URL/username/password entry still works.

- [ ] **Step 5: Record the outcome**

No commit. Note pass/fail per step in the PR description.

---

## Notes for the executor

- ZXing embedded's `CaptureActivity` is declared in the library manifest (merged automatically) and requests the CAMERA permission itself — no manual activity declaration and no separate Compose permission launcher are needed. A cancelled/denied scan returns `result.contents == null`, handled as a no-op.
- Do NOT add dashboard QR generation, mDNS discovery, Tailscale discovery, or a `hermes://` scheme — all explicit anti-scope.
- The scanned password persists only through the existing `CredentialStore`/`EncryptedCredentialStore`; do not log the payload.
