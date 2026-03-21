package com.example.new_tv_app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.FocusFinder
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView

/**
 * Settings detail stack: only the visible [ScrollView] is active; up/down stay inside it.
 */
class FocusDetailColumnLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun focusSearch(focused: View, direction: Int): View? {
        when (direction) {
            View.FOCUS_UP, View.FOCUS_DOWN -> {
                val scroll = visibleScrollChild() ?: return focused
                if (!focused.isDescendantOf(scroll)) {
                    return super.focusSearch(focused, direction)
                }
                val next = FocusFinder.getInstance().findNextFocus(scroll, focused, direction)
                if (next != null && next.isDescendantOf(scroll)) return next
                return focused
            }
        }
        return super.focusSearch(focused, direction)
    }

    private fun visibleScrollChild(): ScrollView? {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == VISIBLE && c is ScrollView) return c
        }
        return null
    }
}
