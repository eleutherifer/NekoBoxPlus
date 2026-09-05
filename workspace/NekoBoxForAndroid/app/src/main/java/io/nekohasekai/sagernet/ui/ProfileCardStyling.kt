package io.nekohasekai.sagernet.ui

import android.content.res.ColorStateList
import androidx.core.view.ViewCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.shouldHighlightAsInsecure
import io.nekohasekai.sagernet.ktx.dp2px

internal fun MaterialCardView.bindProfileSecurity(
    profile: ProxyEntity,
    defaultStrokeColor: ColorStateList?,
    defaultStrokeWidth: Int,
    insecureStrokeWidth: Int = dp2px(2),
) {
    if (
        profile.shouldHighlightAsInsecure(
            DataStore.globalAllowInsecure,
            DataStore.dontHighlightInsecureProfiles,
        )
    ) {
        setStrokeColor(MaterialColors.getColor(this, R.attr.colorError))
        strokeWidth = insecureStrokeWidth
        ViewCompat.setStateDescription(this, context.getString(R.string.profile_insecure))
    } else {
        if (defaultStrokeColor != null) {
            setStrokeColor(defaultStrokeColor)
        } else {
            setStrokeColor(MaterialColors.getColor(this, R.attr.colorSurfaceVariant))
        }
        strokeWidth = defaultStrokeWidth
        ViewCompat.setStateDescription(this, null)
    }
}
