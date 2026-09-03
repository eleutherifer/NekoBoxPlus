package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProxyEntityBeanTest {

    @Test
    fun validProfileExposesItsBean() {
        val bean = SOCKSBean().apply {
            initializeDefaultValues()
        }
        val entity = ProxyEntity().putBean(bean)

        assertSame(bean, entity.beanOrNull())
        assertSame(bean, entity.requireBean())
    }

    @Test
    fun nullProfileCanBeDetectedWithoutCrashing() {
        val entity = ProxyEntity(type = ProxyEntity.TYPE_SOCKS)

        assertTrue(entity.beanOrNull() == null)
    }

    @Test
    fun requireBeanReportsNullProfileWithoutCallingDisplayType() {
        val entity = ProxyEntity(type = ProxyEntity.TYPE_SOCKS)

        try {
            entity.requireBean()
            fail("Expected requireBean() to reject a null profile")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("type ${ProxyEntity.TYPE_SOCKS}"))
        }
    }
}
