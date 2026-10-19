package com.aipaca.android.ui.destinations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationsTest {

    @Test
    fun routesAreUnique() {
        val routes = Destinations.all.map { it.route }
        assertEquals(routes.size, routes.toSet().size)
    }

    @Test
    fun startDestinationIsChat() {
        assertEquals(Destinations.Chat, Destinations.start)
    }

    @Test
    fun menuItemsDoNotContainChat() {
        assertFalse(Destinations.menuItems.contains(Destinations.Chat))
    }

    @Test
    fun menuItemsDoNotContainSearch() {
        assertFalse(Destinations.menuItems.contains(Destinations.Search))
    }

    @Test
    fun searchStaysRoutable() {
        assertTrue(Destinations.all.contains(Destinations.Search))
    }
}
