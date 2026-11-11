package com.bassforge.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.LinearLayout
import com.bassforge.R
import com.bassforge.util.ThemeManager
import com.bassforge.util.NeoSurface
import com.bassforge.util.dp

/**
 * Container drawn as a raised / inset / glass slab. It reserves [SHADOW_DP] of padding on
 * every side so its soft shadows are not clipped by the parent.
 */
class NeoCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : LinearLayout(context, attrs, defStyle) {

    private companion object {
        const val SHADOW_DP = 14f
        const val RADIUS_DP = 26f
    }

    var cardStyle = 0
        set(v) { field = v; invalidate() }

    private val rect = RectF()

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.NeoCard)
            cardStyle = a.getInt(R.styleable.NeoCard_neoStyle, 0)
            a.recycle()
        }
        val sp = dp(SHADOW_DP).toInt()
        setPadding(paddingLeft + sp, paddingTop + sp, paddingRight + sp, paddingBottom + sp)
    }

    override fun onDraw(canvas: Canvas) {
        val sp = dp(SHADOW_DP)
        rect.set(sp, sp, width - sp, height - sp)
        val r = dp(RADIUS_DP)
        val p = ThemeManager.p
        when (cardStyle) {
            0 -> NeoSurface.raised(canvas, rect, r, p, 0f)
            1 -> NeoSurface.raised(canvas, rect, r, p, 1f)
            2 -> NeoSurface.glass(canvas, rect, r, p)
        }
        super.onDraw(canvas)
    }
}
