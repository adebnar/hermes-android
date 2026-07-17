package com.hermes.client.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinkMapperTest {
    @Test fun tab_routes_map() {
        assertEquals("sessions", deepLinkRouteFor("hermes://tab/sessions"))
        assertEquals("activity", deepLinkRouteFor("hermes://tab/activity"))
        assertEquals("you", deepLinkRouteFor("hermes://tab/you"))
    }

    @Test fun chat_id_maps() {
        assertEquals("chat/abc-123", deepLinkRouteFor("hermes://chat/abc-123"))
    }

    @Test fun scheme_is_case_insensitive() {
        assertEquals("sessions", deepLinkRouteFor("HERMES://tab/sessions"))
    }

    @Test fun unknown_or_malformed_is_null() {
        assertNull(deepLinkRouteFor("hermes://tab/nope"))
        assertNull(deepLinkRouteFor("hermes://chat"))       // no id
        assertNull(deepLinkRouteFor("hermes://chat/a/b"))   // two segments
        assertNull(deepLinkRouteFor("hermes://bogus/x"))    // unknown host
        assertNull(deepLinkRouteFor("http://tab/sessions")) // wrong scheme
        assertNull(deepLinkRouteFor(""))
        assertNull(deepLinkRouteFor("not a uri at all ::: %%%"))
    }
}
