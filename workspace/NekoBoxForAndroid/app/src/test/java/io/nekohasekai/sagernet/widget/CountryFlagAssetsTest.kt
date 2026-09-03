package io.nekohasekai.sagernet.widget

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CountryFlagAssetsTest {
    private val flagsDirectory = File("src/main/assets/flags/1x1")

    @Test
    fun flagIconsBundleContainsAlpha2SquareVectors() {
        val flags = flagsDirectory.listFiles { file ->
            file.extension == "svg" && file.nameWithoutExtension.matches(Regex("[a-z]{2}"))
        }.orEmpty()

        assertTrue("Expected the complete flag-icons country set", flags.size >= 250)
        listOf("nl", "us").forEach { code ->
            val file = File(flagsDirectory, "$code.svg")
            assertTrue("Missing $code flag", file.isFile)
            val source = file.readText()
            assertTrue("$code is not an SVG", source.startsWith("<svg"))
            assertTrue("$code does not have a square view box", "viewBox=\"0 0 512 512\"" in source)
        }
    }
}
