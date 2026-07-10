package com.hermes.client.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovalTierTest {
    @Test fun choice_wire_values() {
        assertEquals("once", ApprovalChoice.ONCE.wire)
        assertEquals("session", ApprovalChoice.SESSION.wire)
        assertEquals("always", ApprovalChoice.ALWAYS.wire)
        assertEquals("deny", ApprovalChoice.DENY.wire)
    }

    @Test fun allow_permanent_true_is_standard() {
        assertEquals(ApprovalTier.STANDARD, tierFor(allowPermanent = true))
    }

    @Test fun allow_permanent_false_is_elevated() {
        assertEquals(ApprovalTier.ELEVATED, tierFor(allowPermanent = false))
    }

    @Test fun standard_offers_once_session_always() {
        assertEquals(
            listOf(ApprovalChoice.ONCE, ApprovalChoice.SESSION, ApprovalChoice.ALWAYS),
            allowedScopes(ApprovalTier.STANDARD),
        )
    }

    @Test fun elevated_offers_only_once_and_session() {
        assertEquals(
            listOf(ApprovalChoice.ONCE, ApprovalChoice.SESSION),
            allowedScopes(ApprovalTier.ELEVATED),
        )
    }
}
