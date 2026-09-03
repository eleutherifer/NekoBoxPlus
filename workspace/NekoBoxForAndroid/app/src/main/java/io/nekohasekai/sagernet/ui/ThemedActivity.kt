package io.nekohasekai.sagernet.ui

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.color.DynamicColors
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.utils.CustomTheme
import io.nekohasekai.sagernet.utils.CustomThemePreview
import io.nekohasekai.sagernet.utils.Theme

abstract class ThemedActivity : AppCompatActivity {
    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    var themeResId = 0
    var uiMode = 0
    open val isDialog = false

    override fun onCreate(savedInstanceState: Bundle?) {
        CustomThemePreview.reconcile()
        if (!isDialog) {
            Theme.apply(this)
        } else {
            Theme.applyDialog(this)
        }
        CustomTheme.applyOverrideIfNeeded(this)
        if (Theme.isMaterialYou() || CustomTheme.useDynamicColors()) {
            DynamicColors.applyToActivityIfAvailable(this)
        }
        Theme.applyNightTheme()

        super.onCreate(savedInstanceState)

        uiMode = resources.configuration.uiMode
        
        if (!isDialog) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowCompat.setDecorFitsSystemWindows(window, false)
            }

            val useLightSystemBars = !Theme.usingNightMode()
            val insetController = WindowCompat.getInsetsController(window, window.decorView)
            insetController.isAppearanceLightStatusBars = useLightSystemBars
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                insetController.isAppearanceLightNavigationBars = useLightSystemBars
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            findViewById<View>(R.id.appbar)?.apply {
                updatePadding(top = bars.top)
            }
            applyHeaderColors()
            insets
        }
    }

    internal fun applyHeaderColors() {
        if (!Theme.isCustom() || !DataStore.customThemeHeaderPrimary) return
        val backgroundColor = getColorAttr(R.attr.colorPrimary)
        val contentColor = getColorAttr(R.attr.colorOnPrimary)
        findViewById<View>(R.id.appbar)?.setBackgroundColor(backgroundColor)
        findViewById<ViewGroup>(R.id.quick_toolbar)?.apply {
            setBackgroundColor(backgroundColor)
            tintImageDescendants(contentColor)
        }
        findViewById<Toolbar>(R.id.toolbar)?.apply {
            setBackgroundColor(backgroundColor)
            setTitleTextColor(contentColor)
            navigationIcon?.setTint(contentColor)
            overflowIcon?.setTint(contentColor)
            tintImageDescendants(contentColor)
            for (index in 0 until menu.size()) {
                menu.getItem(index).icon?.setTint(contentColor)
            }
            findViewById<SearchView.SearchAutoComplete>(
                androidx.appcompat.R.id.search_src_text
            )?.apply {
                setTextColor(contentColor)
                setHintTextColor(ColorUtils.setAlphaComponent(contentColor, 0x99))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    textCursorDrawable = textCursorDrawable?.mutate()?.apply {
                        setTint(contentColor)
                    }
                }
            }
        }
    }

    private fun ViewGroup.tintImageDescendants(color: Int) {
        for (index in 0 until childCount) {
            when (val child = getChildAt(index)) {
                is ImageView -> child.imageTintList = ColorStateList.valueOf(color)
                is ViewGroup -> child.tintImageDescendants(color)
            }
        }
    }

    override fun setTheme(resId: Int) {
        super.setTheme(resId)

        themeResId = resId
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (newConfig.uiMode != uiMode) {
            uiMode = newConfig.uiMode
            ActivityCompat.recreate(this)
        }
    }

    fun snackbar(@StringRes resId: Int): Snackbar = snackbar("").setText(resId)
    fun snackbar(text: CharSequence): Snackbar = snackbarInternal(text).apply {
        view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text).apply {
            maxLines = 10
        }
    }

    internal open fun snackbarInternal(text: CharSequence): Snackbar = throw NotImplementedError()

}
