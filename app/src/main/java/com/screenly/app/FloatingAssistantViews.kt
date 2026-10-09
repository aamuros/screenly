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


// Compact-panel presentation state never holds screenshot pixels.
internal data class AssistantChatEntry(val fromUser: Boolean, val content: String)
internal data class AssistantPanelState(
    val action: AssistantAction,
    val messages: List<AssistantChatEntry> = emptyList(),
    val detail: String = "",
    val items: List<AccessibleScreenAssistant.VisibleItem> = emptyList(),
    val selectedItemIndex: Int? = null,
    val guidance: AccessibleScreenAssistant.Guidance? = null,
    val processing: Boolean = false,
    val captureStatus: String = "",
    val processingStatus: String = "",
    val accessibilityStatus: String = ""
)
internal class AssistantPanelActions(
    val close: () -> Unit,
    val submit: (String) -> Unit,
    val refresh: () -> Unit,
    val selectItem: (Int) -> Unit,
    val checkScreen: () -> Unit,
    val cancelGuide: () -> Unit,
    val clearHistory: () -> Unit,
    val clearScreenshots: () -> Unit,
    val openPermissions: () -> Unit,
    val manualPicker: () -> Unit
)

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
                    minimumHeight = dp(context, 80)
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
                }, LinearLayout.LayoutParams(dp(context, 30), dp(context, 30)))
                item.addView(TextView(context).apply {
                    text = context.getString(action.label())
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(context, 7) })
                row.addView(item, LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ))
            }
            panel.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (rowIndex > 0) topMargin = dp(context, 4) })
        }
        return scroll(context, panel)
    }

    /** Feature cards keep the target app visible, with scrolling capped to a phone-sized overlay. */
    fun feature(context: Context, state: AssistantPanelState, actions: AssistantPanelActions): View {
        val container = panel(context).apply {
            setPadding(dp(context, 14), dp(context, 14), dp(context, 14), dp(context, 14))
            background = GradientDrawable().apply {
                setColor(Color.rgb(36, 36, 36))
                cornerRadius = dp(context, 20).toFloat()
            }
        }
        container.addView(header(context, state.action, actions.close))
        when (state.action) {
            AssistantAction.ASK_AI -> askContent(context, container, state, actions)
            AssistantAction.EXPLAIN -> explainContent(context, container, state, actions)
            AssistantAction.GUIDE_ME -> guideContent(context, container, state, actions)
            AssistantAction.PRIVACY -> privacyContent(context, container, state, actions)
        }
        return object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val limit = dp(context, 300)
                super.onMeasure(widthMeasureSpec,
                    View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST))
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = false
            isFocusableInTouchMode = true
            addView(container)
        }
    }

    private fun header(context: Context, action: AssistantAction, close: () -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply {
                setImageResource(action.icon())
                contentDescription = null
            }, LinearLayout.LayoutParams(dp(context, 24), dp(context, 24)))
            addView(TextView(context).apply {
                text = context.getString(action.label())
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(context, 8)
            })
            addView(TextView(context).apply {
                text = "×"
                textSize = 26f
                gravity = Gravity.CENTER
                contentDescription = context.getString(R.string.assistant_close)
                setTextColor(Color.WHITE)
                isClickable = true
                isFocusable = true
                setOnClickListener { close() }
            }, LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)))
        }

    private fun askContent(
        context: Context, root: LinearLayout, state: AssistantPanelState,
        actions: AssistantPanelActions
    ) {
        root.addView(secondary(context, R.string.assistant_ask_intro), gap(context, 8))
        for (message in state.messages.takeLast(12)) {
            root.addView(TextView(context).apply {
                text = (if (message.fromUser) "You: " else "Screenly: ") + message.content
                textSize = 13f
                setTextColor(Color.WHITE)
                setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8))
                background = rounded(context, if (message.fromUser) 63 else 49, 10)
            }, gap(context, 7))
        }
        prompt(context, root, R.string.assistant_ask_hint, state.processing, actions.submit)
        root.addView(secondary(context, R.string.assistant_capture_send), gap(context, 8))
        if (state.messages.isNotEmpty()) root.addView(actionText(context,
            R.string.assistant_recapture, state.processing, actions.refresh), gap(context, 6))
        status(context, root, state)
    }

    private fun explainContent(
        context: Context, root: LinearLayout, state: AssistantPanelState,
        actions: AssistantPanelActions
    ) {
        root.addView(secondary(context, R.string.assistant_explain_intro), gap(context, 10))
        if (state.detail.isNotBlank()) root.addView(TextView(context).apply {
            text = state.detail
            textSize = 13f
            setTextColor(Color.WHITE)
        }, gap(context, 8))
        for ((count, item) in state.items.take(8).withIndex()) {
            root.addView(TextView(context).apply {
                text = "${count + 1}. ${item.title}"
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding(dp(context, 11), dp(context, 10), dp(context, 11), dp(context, 10))
                background = rounded(context, 55, 10)
                isClickable = true
                isFocusable = true
                contentDescription = item.title
                setOnClickListener { actions.selectItem(item.index) }
            }, gap(context, 4))
            if (state.selectedItemIndex == item.index) {
                root.addView(TextView(context).apply {
                    text = item.detail
                    textSize = 12f
                    setTextColor(Color.rgb(176, 176, 176))
                    setPadding(dp(context, 6), dp(context, 4), dp(context, 6), dp(context, 4))
                })
            }
        }
        root.addView(actionText(context,
            R.string.assistant_refresh, state.processing, actions.refresh), gap(context, 10))
        status(context, root, state)
    }

    private fun guideContent(
        context: Context, root: LinearLayout, state: AssistantPanelState,
        actions: AssistantPanelActions
    ) {
        val guide = state.guidance
        if (guide == null || guide.phase == AccessibleScreenAssistant.GuidancePhase.CANCELLED) {
            root.addView(secondary(context, R.string.assistant_guide_intro), gap(context, 8))
            prompt(context, root, R.string.assistant_guide_hint,
                state.processing, actions.submit)
            root.addView(secondary(context, R.string.assistant_guide_description),
                gap(context, 10))
            root.addView(actionText(context,
                R.string.assistant_manual_picker, state.processing, actions.manualPicker), gap(context, 6))
        } else {
            root.addView(TextView(context).apply {
                text = context.getString(R.string.assistant_guide_step, guide.step)
                textSize = 13f
                setTextColor(Color.rgb(176, 176, 176))
            }, gap(context, 10))
            root.addView(TextView(context).apply {
                text = guide.instruction
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            }, gap(context, 14))
            root.addView(TextView(context).apply {
                text = guide.status
                textSize = 12f
                setTextColor(Color.rgb(176, 176, 176))
            }, gap(context, 8))
            if (guide.phase != AccessibleScreenAssistant.GuidancePhase.COMPLETED) {
                root.addView(primaryButton(context, R.string.assistant_check_screen,
                    state.processing, actions.checkScreen), gap(context, 12))
            }
            root.addView(actionText(context,
                R.string.assistant_manual_picker, state.processing, actions.manualPicker), gap(context, 6))
            root.addView(actionText(context,
                R.string.assistant_cancel, state.processing, actions.cancelGuide), gap(context, 6))
        }
        status(context, root, state)
    }

    private fun privacyContent(
        context: Context, root: LinearLayout, state: AssistantPanelState,
        actions: AssistantPanelActions
    ) {
        root.addView(secondary(context, R.string.assistant_privacy_intro), gap(context, 7))
        root.addView(section(context, R.string.assistant_privacy_ai, state.processingStatus),
            gap(context, 9))
        root.addView(section(context, R.string.assistant_privacy_capture,
            context.getString(R.string.assistant_capture_on_demand) + "\n" + state.captureStatus),
            gap(context, 6))
        root.addView(section(context, R.string.assistant_privacy_data,
            context.getString(R.string.assistant_data_notice)), gap(context, 6))
        root.addView(section(context, R.string.assistant_privacy_permissions, state.accessibilityStatus),
            gap(context, 6))
        root.addView(actionText(context,
            R.string.assistant_clear_history, false, actions.clearHistory), gap(context, 4))
        root.addView(actionText(context,
            R.string.assistant_clear_screenshots, false, actions.clearScreenshots), gap(context, 4))
        root.addView(actionText(context,
            R.string.assistant_open_permissions, false, actions.openPermissions), gap(context, 4))
        status(context, root, state)
    }

    private fun section(context: Context, title: Int, body: String) =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = context.getString(title)
                typeface = Typeface.DEFAULT_BOLD
                textSize = 14f
                setTextColor(Color.WHITE)
            })
            addView(TextView(context).apply {
                text = body
                textSize = 12f
                setTextColor(Color.rgb(176, 176, 176))
            }, gap(context, 3))
        }

    private fun prompt(
        context: Context, root: LinearLayout, hint: Int,
        disabled: Boolean, onSend: (String) -> Unit
    ) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(context, 8), dp(context, 6), dp(context, 6), dp(context, 6))
            background = rounded(context, 56, 13)
        }
        val input = android.widget.EditText(context).apply {
            setHint(hint)
            setHintTextColor(Color.rgb(176, 176, 176))
            setTextColor(Color.WHITE)
            textSize = 14f
            minLines = 2
            maxLines = 3
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setBackgroundColor(Color.TRANSPARENT)
            isEnabled = !disabled
        }
        row.addView(input, LinearLayout.LayoutParams(0, dp(context, 84), 1f))
        row.addView(TextView(context).apply {
            text = "↑"
            contentDescription = context.getString(R.string.assistant_send)
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            isClickable = true
            isFocusable = true
            isEnabled = !disabled
            setOnClickListener {
                val value = input.text?.toString()?.trim().orEmpty()
                if (value.isNotEmpty()) onSend(value)
            }
        }, LinearLayout.LayoutParams(dp(context, 42), dp(context, 42)))
        root.addView(row, gap(context, 9))
    }

    private fun status(context: Context, root: LinearLayout, state: AssistantPanelState) {
        val msg = if (state.processing) context.getString(R.string.assistant_processing)
            else state.captureStatus
        if (msg.isNotBlank()) root.addView(TextView(context).apply {
            text = msg
            textSize = 12f
            setTextColor(Color.rgb(176, 176, 176))
        }, gap(context, 9))
    }

    private fun secondary(context: Context, value: Int) = TextView(context).apply {
        text = context.getString(value)
        textSize = 13f
        setTextColor(Color.rgb(176, 176, 176))
    }

    private fun primaryButton(context: Context, label: Int, disabled: Boolean, onClick: () -> Unit) =
        actionText(context, label, disabled, onClick).apply {
            background = rounded(context, 68, 12)
            gravity = Gravity.CENTER
            minimumHeight = dp(context, 48)
            setTextColor(Color.WHITE)
        }

    private fun actionText(context: Context, label: Int, disabled: Boolean, onClick: () -> Unit) =
        TextView(context).apply {
            text = context.getString(label)
            textSize = 13f
            setTextColor(Color.rgb(220, 220, 220))
            minHeight = dp(context, 42)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            isEnabled = !disabled
            setOnClickListener { onClick() }
        }

    private fun rounded(context: Context, shade: Int, radius: Int) = GradientDrawable().apply {
        setColor(Color.rgb(shade, shade, shade))
        cornerRadius = dp(context, radius).toFloat()
    }

    private fun gap(context: Context, top: Int) =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(context, top) }

    private fun panel(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
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
