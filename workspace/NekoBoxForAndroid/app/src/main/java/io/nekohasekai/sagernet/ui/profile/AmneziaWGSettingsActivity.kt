package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.wireguard.AmneziaWGBean
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type

class AmneziaWGSettingsActivity : ProfileSettingsActivity<AmneziaWGBean>() {

    override fun createEntity() = AmneziaWGBean()

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, "name"))
    private val serverAddress = pbm.add(PreferenceBinding(Type.Text, "serverAddress"))
    private val serverPort = pbm.add(PreferenceBinding(Type.TextToInt, "serverPort"))
    private val localAddress = pbm.add(PreferenceBinding(Type.Text, "localAddress"))
    private val privateKey = pbm.add(PreferenceBinding(Type.Text, "privateKey"))
    private val peerPublicKey = pbm.add(PreferenceBinding(Type.Text, "peerPublicKey"))
    private val peerPreSharedKey = pbm.add(PreferenceBinding(Type.Text, "peerPreSharedKey"))
    private val peerPersistentKeepalive = pbm.add(PreferenceBinding(Type.Text, "peerPersistentKeepalive"))
    private val mtu = pbm.add(PreferenceBinding(Type.TextToInt, "mtu"))
    private val reserved = pbm.add(PreferenceBinding(Type.Text, "reserved"))
    private val jc = pbm.add(PreferenceBinding(Type.TextToInt, "jc"))
    private val jmin = pbm.add(PreferenceBinding(Type.TextToInt, "jmin"))
    private val jmax = pbm.add(PreferenceBinding(Type.TextToInt, "jmax"))
    private val s1 = pbm.add(PreferenceBinding(Type.TextToInt, "s1"))
    private val s2 = pbm.add(PreferenceBinding(Type.TextToInt, "s2"))
    private val h1 = pbm.add(PreferenceBinding(Type.Text, "h1"))
    private val h2 = pbm.add(PreferenceBinding(Type.Text, "h2"))
    private val s3 = pbm.add(PreferenceBinding(Type.TextToInt, "s3"))
    private val s4 = pbm.add(PreferenceBinding(Type.TextToInt, "s4"))
    private val h3 = pbm.add(PreferenceBinding(Type.Text, "h3"))
    private val h4 = pbm.add(PreferenceBinding(Type.Text, "h4"))
    private val i1 = pbm.add(PreferenceBinding(Type.Text, "i1"))
    private val i2 = pbm.add(PreferenceBinding(Type.Text, "i2"))
    private val i3 = pbm.add(PreferenceBinding(Type.Text, "i3"))
    private val i4 = pbm.add(PreferenceBinding(Type.Text, "i4"))
    private val i5 = pbm.add(PreferenceBinding(Type.Text, "i5"))
    private val headerProtectionKey = pbm.add(PreferenceBinding(Type.Text, "headerProtectionKey"))
    private val contentPaddingAddition = pbm.add(PreferenceBinding(Type.Text, "contentPaddingAddition"))
    private val rekeyAfterTime = pbm.add(PreferenceBinding(Type.Text, "rekeyAfterTime"))
    private val rekeyTimeout = pbm.add(PreferenceBinding(Type.Text, "rekeyTimeout"))
    private val rejectAfterTime = pbm.add(PreferenceBinding(Type.Text, "rejectAfterTime"))
    private val keepaliveTimeout = pbm.add(PreferenceBinding(Type.Text, "keepaliveTimeout"))
    private val maxHandshakeAttempts = pbm.add(PreferenceBinding(Type.Text, "maxHandshakeAttempts"))

    override fun AmneziaWGBean.init() {
        pbm.writeToCacheAll(this)
    }

    override fun AmneziaWGBean.serialize() {
        pbm.fromCacheAll(this)
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.amneziawg_preferences)
        pbm.setPreferenceFragment(this)

        (serverPort.preference as EditTextPreference)
            .setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        (privateKey.preference as EditTextPreference).summaryProvider = PasswordSummaryProvider
        (peerPersistentKeepalive.preference as EditTextPreference)
            .setOnBindEditTextListener(EditTextPreferenceModifiers.UnsignedRange)
        (mtu.preference as EditTextPreference).setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        (jc.preference as EditTextPreference).setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        (jmin.preference as EditTextPreference).setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        (jmax.preference as EditTextPreference).setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        (s1.preference as EditTextPreference).setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        (s2.preference as EditTextPreference).setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        (s3.preference as EditTextPreference).setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        (s4.preference as EditTextPreference).setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        (headerProtectionKey.preference as EditTextPreference).summaryProvider = PasswordSummaryProvider
        listOf(
            contentPaddingAddition,
            rekeyAfterTime,
            rekeyTimeout,
            rejectAfterTime,
            keepaliveTimeout,
            maxHandshakeAttempts,
        ).forEach {
            (it.preference as EditTextPreference)
                .setOnBindEditTextListener(EditTextPreferenceModifiers.UnsignedRange)
        }
    }

}
