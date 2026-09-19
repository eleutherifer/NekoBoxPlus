package moe.matsuri.nb4a.proxy.byedpi

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity

class ByeDPISettingsActivity : ProfileSettingsActivity<ByeDPIBean>() {

    companion object {
        private const val KEY_CLI_STRATEGY = "byeDpiCliStrategy"
    }

    override fun createEntity() = ByeDPIBean()

    override fun ByeDPIBean.init() {
        DataStore.profileName = name
        DataStore.profileCacheStore.putString(KEY_CLI_STRATEGY, cliStrategy)
    }

    override fun ByeDPIBean.serialize() {
        name = DataStore.profileName
        cliStrategy = DataStore.profileCacheStore.getString(KEY_CLI_STRATEGY) ?: ""
    }

    override fun PreferenceFragmentCompat.createPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.byedpi_preferences)
    }
}
