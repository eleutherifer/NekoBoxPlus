package moe.matsuri.nb4a.utils

import io.nekohasekai.sagernet.SpoofApp
import org.junit.Assert.assertEquals
import org.junit.Test

class HwidGeneratorTest {
    @Test
    fun `preserves existing Happ and generic identifier`() {
        assertEquals("f7f41cbf74c2181e", HwidGenerator.generate("android-id", SpoofApp.NONE))
        assertEquals("f7f41cbf74c2181e", HwidGenerator.generate("android-id", SpoofApp.HAPP))
    }

    @Test
    fun `formats v2RayTun identifier as uppercase hex`() {
        assertEquals("F7F41CBF74C2181E", HwidGenerator.generate("android-id", SpoofApp.V2RAY_TUN))
    }

    @Test
    fun `formats Incy identifier as uppercase grouped hex`() {
        assertEquals(
            "F7F41CBF-74C2-181E-016F-62BB938B4423",
            HwidGenerator.generate("android-id", SpoofApp.INCY),
        )
    }
}
