package com.bassforge.ui

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import com.bassforge.R
import com.bassforge.util.ThemeManager
import com.bassforge.util.Themed

/** Text that follows the theme: role 0 = main text, 1 = dim, 2 = accent. */
class NeoLabel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : AppCompatTextView(context, attrs, defStyle), Themed {

    var role = 0
        set(v) { field = v; applyTheme() }

    init {
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.NeoLabel)
            role = a.getInt(R.styleable.NeoLabel_neoRole, 0)
            a.recycle()
        }
        applyTheme()
    }

    override fun applyTheme() {
        val p = ThemeManager.p
        setTextColor(
            when (role) {
                1 -> p.textDim
                2 -> p.accent
                else -> p.text
            }
        )
    }
}
