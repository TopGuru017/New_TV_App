package com.example.new_tv_app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.FocusFinder
import android.view.View
import android.widget.LinearLayout

/**
 * TV column: DPAD up/down only move within this column; left/right use default search to leave the column.
 */
class FocusColumnLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    init {
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
    }

    override fun focusSearch(focused: View, direction: Int): View? {
        when (direction) {
            View.FOCUS_UP, View.FOCUS_DOWN -> {
                if (!focused.isDescendantOf(this)) {
                    return super.focusSearch(focused, direction)
                }
                val next = FocusFinder.getInstance().findNextFocus(this, focused, direction)
                if (next != null && next.isDescendantOf(this)) return next
                return focused
            }
        }
        return super.focusSearch(focused, direction)
    }
}

internal fun View.isDescendantOf(ancestor: View): Boolean {
    var v: View? = this
    while (v != null) {
        if (v === ancestor) return true
        v = v.parent as? View
    }
    return false
}
