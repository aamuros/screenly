package com.screenly.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal enum class AssistantAction { ASK_AI, EXPLAIN, GUIDE_ME, PRIVACY }

/** Artwork defines the appearance; native controls provide real tap targets. */
internal object FloatingAssistantViews {
    fun menu(context: Context, onAction: (AssistantAction) -> Unit): View {
        val actions = listOf(AssistantAction.ASK_AI, AssistantAction.EXPLAIN,
            AssistantAction.GUIDE_ME, AssistantAction.PRIVACY)
        val panel = panel(context)
        for (rowIndex in 0..1) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (columnIndex in 0..1) {
                val action = actions[rowIndex * 2 + columnIndex]
                val item = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    minimumHeight = dp(context, 100)
                    isClickable = true
                    isFocusable = true
                    contentDescription = context.getString(action.label())
                    background = RippleDrawable(
                        ColorStateList.valueOf(Color.argb(55, 255, 255, 255)),
                        null, GradientDrawable().apply {
                            setColor(Color.WHITE)
                            cornerRadius = dp(context, 14).toFloat()
                        }
                    )
                    setOnClickListener { onAction(action) }
                }
                item.addView(ImageView(context).apply {
                    setImageResource(action.icon())
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(context, 42), dp(context, 42)))
                item.addView(TextView(context).apply {
                    text = context.getString(action.label())
                    textSize = 16f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(context, 10) })
                row.addView(item, LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ))
            }
            panel.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (rowIndex > 0) topMargin = dp(context, 6) })
        }
        return scroll(context, panel)
    }

    fun infoPanel(context: Context, title: String, message: String, onClear: (() -> Unit)? = null, onDismiss: () -> Unit): View {
        val panel = panel(context).apply {
            setPadding(dp(context, 20), dp(context, 20), dp(context, 20), dp(context, 16))
        }
        panel.addView(TextView(context).apply {
            text = title
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        panel.addView(TextView(context).apply {
            text = message
            textSize = 14f
            setTextColor(Color.rgb(235, 235, 235))
            setLineSpacing(dp(context, 3).toFloat(), 1f)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(context, 14) })
        if (onClear != null) panel.addView(TextView(context).apply {
            setText(R.string.clear_assistant_session)
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            isClickable = true
            isFocusable = true
            minimumHeight = dp(context, 48)
            setOnClickListener { onClear() }
        })
        panel.addView(TextView(context).apply {
            setText(R.string.assistant_close)
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            isClickable = true
            isFocusable = true
            minimumHeight = dp(context, 48)
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 12).toFloat()
                setColor(Color.rgb(81, 81, 81))
            }
            setOnClickListener { onDismiss() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(context, 18) })
        return scroll(context, panel)
    }

    private fun panel(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14))
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(66, 66, 66), Color.rgb(31, 31, 31))).apply {
            cornerRadius = dp(context, 26).toFloat()
            setStroke(dp(context, 1), Color.rgb(100, 100, 100))
        }
    }

    private fun scroll(context: Context, panel: View): ScrollView = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        isFillViewport = true
        addView(panel)
    }

    private fun AssistantAction.label(): Int = when (this) {
        AssistantAction.ASK_AI -> R.string.assistant_action_ask
        AssistantAction.EXPLAIN -> R.string.assistant_action_explain
        AssistantAction.GUIDE_ME -> R.string.assistant_action_guide
        AssistantAction.PRIVACY -> R.string.assistant_action_privacy
    }

    private fun AssistantAction.icon(): Int = when (this) {
        AssistantAction.ASK_AI -> R.drawable.ic_assistant_ask
        AssistantAction.EXPLAIN -> R.drawable.ic_assistant_explain
        AssistantAction.GUIDE_ME -> R.drawable.ic_assistant_guide
        AssistantAction.PRIVACY -> R.drawable.ic_assistant_privacy
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
