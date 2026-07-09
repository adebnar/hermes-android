# Known Issues

## New chats show "Untitled" in non-default profiles — pending upstream gateway fix

**Symptom:** A newly-created chat in a **non-default profile** stays titled "Untitled" in
the app, even though the desktop client (same gateway) shows an AI-generated title.

**Cause — this is a gateway bug, not an app bug.** The gateway's auto-titler persists the
generated title to the launch/default profile's database instead of the session's *own*
profile database, so `set_session_title` updates 0 rows and the title is silently dropped
for any chat in a non-launch profile. No client change can recover a title the gateway
never saved.

**Fix:** Filed upstream as **[NousResearch/hermes-agent#61156](https://github.com/NousResearch/hermes-agent/pull/61156)**
(persist the title to the session's own profile DB). Once it merges and the gateway is
updated, titles appear automatically — no further app change needed.

**App side:** The `session.title` listener added in **0.1.46** (`SessionsViewModel`) is the
mechanism that surfaces the title **live** once the gateway persists it. It is correct but
has no visible effect until the gateway fix lands, so 0.1.46 alone does **not** resolve the
"Untitled" symptom for non-default-profile chats. The profile-on-create fix in **0.1.45**
(new chats persisting under the active profile) is a separate, working fix and is unaffected.
