package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCardActionPolicyTest {
    @Test
    fun normalProfileListShowsUrlTest() {
        assertTrue(
            ProfileCardActionPolicy.shouldShowUrlTest(
                selectMode = false,
                notificationSwitchPopup = false,
                batchSelection = false,
            )
        )
    }

    @Test
    fun notificationSwitchPopupShowsUrlTest() {
        assertTrue(
            ProfileCardActionPolicy.shouldShowUrlTest(
                selectMode = true,
                notificationSwitchPopup = true,
                batchSelection = false,
            )
        )
    }

    @Test
    fun ordinaryProfileSelectorHidesUrlTest() {
        assertFalse(
            ProfileCardActionPolicy.shouldShowUrlTest(
                selectMode = true,
                notificationSwitchPopup = false,
                batchSelection = false,
            )
        )
    }

    @Test
    fun batchSelectionAlwaysHidesUrlTest() {
        assertFalse(
            ProfileCardActionPolicy.shouldShowUrlTest(
                selectMode = false,
                notificationSwitchPopup = true,
                batchSelection = true,
            )
        )
    }
}
