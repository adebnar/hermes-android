# Security Policy

## Reporting a vulnerability

Please report security issues **privately** via GitHub's **"Report a vulnerability"**
(Security → Advisories) on this repository, or by email to the maintainer. Do not open public
issues for vulnerabilities. We aim to acknowledge within 72 hours.

## Security posture

Hermes for Android connects a phone to a **self-hosted** Hermes dashboard/gateway, so it
handles sensitive credentials and an authenticated channel to systems that can read mail,
files, and more. Practices in this codebase:

- **Credentials at rest** are stored in `EncryptedSharedPreferences` (AES-256, Android
  Keystore-backed) via `EncryptedCredentialStore`. The gateway URL, session token, and
  username/password never hit plaintext storage.
- **Two auth modes.** A loopback-bound dashboard authenticates with a session token
  (`X-Hermes-Session-Token`). A network-bound (gated) dashboard requires a password provider —
  the app logs in (`POST /auth/password-login`), holds a rotating session cookie, and mints a
  single-use WebSocket ticket per connection. The token/cookies are sent over the configured
  transport only.
- **Cleartext is permitted by design** (`usesCleartextTraffic="true"`) for self-hosted
  dashboards on a **private network** — Tailscale is WireGuard-encrypted and a LAN is trusted,
  so confidentiality/integrity come from the network layer. For public-internet exposure,
  front the dashboard with HTTPS (Tailscale Serve / a reverse proxy) and use an `https://` URL.
- **No secrets in the repo.** `keystore.properties`, `keystore/`, `*.jks`/`*.keystore`,
  `local.properties`, and `.env` are git-ignored. Release signing reads the gitignored
  `keystore.properties`; signing material never enters git.
- **Diagnostics redaction.** The optional in-app diagnostic log (Settings → Diagnostics) is
  off by default and masks the session token before anything is recorded or shared.
- **Dependency & supply-chain hygiene.** Toolchain and libraries are pinned via the Gradle
  version catalog. CI runs `gitleaks` secret scanning (pre-push hook + workflow), CodeQL code
  scanning, and a Dependabot dependency-graph submission scoped to the app's **shipped**
  (release runtime) dependencies — so advisories focus on what actually reaches the APK rather
  than the build toolchain.
- **Least privilege** — only the permissions the feature set needs.

## Scope

The app trusts the dashboard/gateway the user configures. Securing the dashboard and the
Hermes agent (authentication, TLS, network exposure) is the operator's responsibility — see
the [README setup guide](README.md#first-run-setup) and the upstream Hermes docs.
