package io.nekohasekai.sagernet.routing

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.database.SubscriptionBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionRoutingTest {
    @Test
    fun routingStateIsEmptyAndDisabledByDefault() {
        val subscription = SubscriptionBean().apply { initializeDefaultValues() }

        assertFalse(subscription.routingEnabled)
        assertEquals("", subscription.routingPayload)
        assertEquals("", subscription.routingFormat)
        assertEquals("", subscription.autoRoutingUrl)
        assertEquals(0L, subscription.routingLastUpdated)
    }

    @Test
    fun extractorUsesDocumentedPriority() {
        val result = SubscriptionRoutingExtractor.extract(
            autoroutingHeader = "https://example.com/header.json",
            routingHeader = "incy://routing/onadd/static",
            body = "://autorouting/onadd/https://example.com/body.json",
        )

        assertEquals(
            ProviderRoutingSource.Value("https://example.com/header.json", auto = true),
            result,
        )
    }

    @Test
    fun extractorFindsBodyRoutingAmongProfiles() {
        val result = SubscriptionRoutingExtractor.extract(
            autoroutingHeader = "",
            routingHeader = "",
            body = """
                vless://id@example.com:443#Example
                ://routing/onadd/encoded
            """.trimIndent(),
        )

        assertEquals(ProviderRoutingSource.Value("://routing/onadd/encoded", auto = false), result)
    }

    @Test
    fun explicitOffIsDistinctFromMissing() {
        val explicitOff = SubscriptionRoutingExtractor.extract("", "off", "")
        val removedRouting = SubscriptionRoutingExtractor.extract("", "", "vless://server")

        assertTrue(explicitOff is ProviderRoutingSource.Off)
        assertTrue(removedRouting is ProviderRoutingSource.Missing)
        assertTrue(explicitOff.disablesStoredRouting())
        assertTrue(removedRouting.disablesStoredRouting())
    }

    @Test
    fun intervalsAcceptOnlyDocumentedValues() {
        SubscriptionRoutingIntervals.allowed.forEach {
            assertEquals(it, SubscriptionRoutingIntervals.normalize(it))
        }
        assertEquals(86_400, SubscriptionRoutingIntervals.normalize(1))
    }

    @Test
    fun subscriptionRoutingStateRoundTripsThroughGroupBlob() {
        val original = SubscriptionBean().apply {
            initializeDefaultValues()
            routingEnabled = true
            routingPayload = """{"Name":"Provider"}"""
            routingFormat = RoutingProfileFormat.V2RAY_TUN.name
            autoRoutingUrl = "https://example.com/routing.json"
            routingUpdateInterval = 259_200
            routingLastUpdated = 1234L
        }
        val output = ByteBufferOutput(1024, -1)
        original.serializeToBuffer(output)

        val restored = SubscriptionBean().apply {
            deserializeFromBuffer(ByteBufferInput(output.toBytes()))
            initializeDefaultValues()
        }

        assertTrue(restored.routingEnabled)
        assertEquals(original.routingPayload, restored.routingPayload)
        assertEquals(RoutingProfileFormat.V2RAY_TUN.name, restored.routingFormat)
        assertEquals(259_200, restored.routingUpdateInterval)
        assertEquals(1234L, restored.routingLastUpdated)
    }
}
