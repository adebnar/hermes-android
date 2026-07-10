package com.hermes.client.ui.chat

/** The scoped decision the gateway understands: approval.respond {choice}. */
enum class ApprovalChoice(val wire: String) {
    ONCE("once"), SESSION("session"), ALWAYS("always"), DENY("deny")
}

/** Risk tier, derived from the gateway's `allow_permanent` bit (false = Tirith-flagged). */
enum class ApprovalTier { STANDARD, ELEVATED }

fun tierFor(allowPermanent: Boolean): ApprovalTier =
    if (allowPermanent) ApprovalTier.STANDARD else ApprovalTier.ELEVATED

/** Allow-scopes offered per tier (DENY is always available separately). */
fun allowedScopes(tier: ApprovalTier): List<ApprovalChoice> = when (tier) {
    ApprovalTier.STANDARD -> listOf(ApprovalChoice.ONCE, ApprovalChoice.SESSION, ApprovalChoice.ALWAYS)
    ApprovalTier.ELEVATED -> listOf(ApprovalChoice.ONCE, ApprovalChoice.SESSION)
}
