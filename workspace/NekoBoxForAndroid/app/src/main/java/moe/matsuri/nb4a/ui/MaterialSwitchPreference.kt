package moe.matsuri.nb4a.ui

import android.content.Context
import android.util.AttributeSet
import androidx.core.content.res.TypedArrayUtils
import androidx.preference.SwitchPreferenceCompat
import io.nekohasekai.sagernet.R

open class MaterialSwitchPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = TypedArrayUtils.getAttr(
        context,
        androidx.preference.R.attr.switchPreferenceCompatStyle,
        android.R.attr.switchPreferenceStyle,
    ),
    defStyleRes: Int = 0,
) : SwitchPreferenceCompat(context, attrs, defStyleAttr, defStyleRes) {

    init {
        widgetLayoutResource = R.layout.preference_widget_material_switch
        isSingleLineTitle = false
    }
}
