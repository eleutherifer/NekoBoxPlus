package io.nekohasekai.sagernet.ui.toolbar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileToolbarActionCatalogTest {
    @Test
    fun everyActionIdHasExactlyOneDescriptor() {
        assertEquals(
            ProfileToolbarActionId.entries.toSet(),
            ProfileToolbarActionCatalog.actions.map { it.id }.toSet(),
        )
        assertEquals(
            ProfileToolbarActionCatalog.actions.size,
            ProfileToolbarActionCatalog.actions.map { it.id }.distinct().size,
        )
    }

    @Test
    fun everyDescriptorHasTitleAndIcon() {
        assertTrue(ProfileToolbarActionCatalog.actions.all { it.titleRes != 0 && it.iconRes != 0 })
    }
}
