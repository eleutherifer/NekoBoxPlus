package io.nekohasekai.sagernet.widget

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProfileCardMarqueeLayoutTest {

    @Test
    fun composeCardsUseContinuousMarquee() {
        val source = File(
            "src/main/java/io/nekohasekai/sagernet/ui/compose/ProfileCard.kt"
        ).readText()

        assertTrue(source.contains(".basicMarquee("))
        assertTrue(source.contains("iterations = Int.MAX_VALUE"))
        assertTrue(source.contains("repeatDelayMillis = 1_200"))
    }

    @Test
    fun migratedCardVisualsMatchLegacySizingAndOptionalBorders() {
        val profileSource = File(
            "src/main/java/io/nekohasekai/sagernet/ui/compose/ProfileCard.kt"
        ).readText()
        val routeSource = File(
            "src/main/java/io/nekohasekai/sagernet/ui/compose/RouteItemCard.kt"
        ).readText()
        val menuSource = File("src/main/res/menu/add_profile_menu.xml").readText()

        assertTrue(routeSource.contains("style = MaterialTheme.typography.bodyMedium"))
        assertTrue(profileSource.contains("MaterialTheme.colorScheme.surface"))
        assertTrue(profileSource.contains("MaterialTheme.colorScheme.outlineVariant"))
        assertTrue(menuSource.contains("android:id=\"@+id/action_profile_card_borders\""))
        assertTrue(
            menuSource.indexOf("</group>") <
                menuSource.indexOf("android:id=\"@+id/action_profile_card_borders\"")
        )
    }

    @Test
    fun composeMarqueeUsesFixedGapAndSingleLineText() {
        val source = File(
            "src/main/java/io/nekohasekai/sagernet/ui/compose/ProfileCard.kt"
        ).readText()

        assertTrue(source.contains("spacing = MarqueeSpacing(24.dp)"))
        assertTrue(source.contains("maxLines = 1"))
    }
}
