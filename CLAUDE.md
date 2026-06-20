# Hermes for Android — Project Standards

These rules are **mandatory** for any agent or contributor working in this repo. They keep
`master` production-grade and the app free of leaked secrets and known-vulnerable
dependencies. An agent (Claude Code or otherwise) MUST follow them exactly.

---

## Branch workflow

| Branch | Role | Rule |
|--------|------|------|
| `master` | Production / stable | **Protected.** Never commit or push directly. Changes land via PR only. |
| `develop` | Beta / integration | New work lands here first. Safe to push. |
| `feature/*` | Short-lived work | Branch off `develop`; open a PR into `develop`. |

Releases: tag `vX.Y.Z` on `master` (stable) or `vX.Y.Z-beta.N` on `develop` (beta
pre-release). Tag pushes trigger the signed-APK release workflow.

---

## Mandatory gates before pushing

### 1. Approval to push to `master` / `main` — REQUIRED
- **NEVER push to `master` without explicit, per-push approval from the user in the
  conversation.** Approval for one push does NOT carry to the next.
- The default target for any push is `develop` or a `feature/*` branch.
- Reaching `master` is done through a **pull request** with passing checks — never a direct
  push. `master` is branch-protected on GitHub to enforce this.

### 2. Secret / password scan before EVERY push — REQUIRED
Run before any `git push`:
```bash
gitleaks git --no-banner --redact      # scans the commits being pushed
```
If gitleaks reports **any** finding, STOP — do not push. Remove the secret, rotate it, and
rewrite history if it was already committed. Never commit `keystore.properties`, `*.jks`,
`local.properties`, `.env`, API tokens, the Hermes session token, or anything under
`~/.secrets`.

### 3. Dependency / vulnerability check before pushing to `master` — REQUIRED
Before any change reaches `master`, confirm there are no known-vulnerable dependencies:
```bash
# Authoritative — GitHub's GHSA advisory database:
gh api repos/adebnar/hermes-android/dependabot/alerts \
  --jq '[.[] | select(.state=="open")] | map({pkg:.dependency.package.name, sev:.security_advisory.severity})'
# Optional local scan (if installed):
osv-scanner --recursive .
```
Any **open HIGH or CRITICAL** alert blocks the release. Upgrade or replace the dependency
first.

---

## Automated enforcement (already wired)

- **`.githooks/pre-push`** — runs the secret scan on every push and the dependency check on
  `master` pushes. Enable once per clone:
  ```bash
  git config core.hooksPath .githooks
  ```
- **GitHub Actions** — `ci.yml` (build + unit test), `codeql.yml` (code scanning),
  `security.yml` (gitleaks + dependency submission for the Dependabot graph).
- **GitHub repo settings** — Dependabot alerts + automated security fixes, secret scanning
  with push protection, and `master` branch protection (PR + passing checks required).

---

## Build & signing

- Build with a **JDK 21** toolchain via `JAVA_HOME`. Do **not** hardcode
  `org.gradle.java.home` — it breaks CI and clones.
- Release signing reads the gitignored `keystore.properties`; signing secrets never enter
  git.
- Full build, release, token, and setup docs live in [README.md](README.md).

---

## Agent etiquette

- No AI/assistant attribution in commits, files, or PRs (no "Co-Authored-By", "Generated
  with", "Claude", etc.).
- Run the build and unit tests before claiming work is complete.
- Prefer small, reviewable commits with clear messages.
