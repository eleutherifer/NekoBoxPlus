package moe.matsuri.nb4a.proxy.direct

import android.os.Bundle
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity

class DirectSettingsActivity :
    ProfileSettingsActivity<DirectBean>(),
    OnPreferenceDataStoreChangeListener {

    override fun createEntity() = DirectBean()

    override fun DirectBean.init() {
        DataStore.profileName = name
    }

    override fun DirectBean.serialize() {
        name = DataStore.profileName
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key != Key.PROFILE_DIRTY) {
            DataStore.dirty = true
        }
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.direct_preferences)
    }
}
