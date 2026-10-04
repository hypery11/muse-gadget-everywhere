package ai.muse.gadgeteverywhere

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.EditText

/** Shared visual kit: cards, buttons, status rows. One place so both
 *  screens stay in the same world. All dp scale with density; sp text
 *  stays readable on a 10-foot TV and a 6-inch phone alike. */
object Ui {

    fun Context.dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    /** Brand ink with API-24-safe color lookup (getColor(int) is 23+). */
    @Suppress("DEPRECATION")
    fun Context.brand(resId: Int): Int = resources.getColor(resId)

    fun card(context: Context): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card)
            val pad = context.dp(20)
            setPadding(pad, context.dp(16), pad, context.dp(16))
        }
    }

    /** Two-tone brand title: [a] in ink, [b] in accent. */
    fun twoToneTitle(context: Context, a: String, b: String, sizeSp: Float): TextView {
        val title = SpannableString("$a $b")
        title.setSpan(
            ForegroundColorSpan(context.brand(R.color.ink)),
            0, a.length, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        title.setSpan(
            ForegroundColorSpan(context.brand(R.color.accent)),
            a.length + 1, title.length, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        return TextView(context).apply {
            text = title
            textSize = sizeSp
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }

    fun sectionTitle(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(context.brand(R.color.ink))
            setPadding(0, context.dp(20), 0, context.dp(8))
            accessibilityHeadingCompat()
        }
    }

    private fun buttonBase(context: Context, text: String, primary: Boolean): Button {
        return Button(context).apply {
            this.text = text
            textSize = 15f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAllCaps = false
            minHeight = context.dp(52)
            minimumWidth = 0
            val padH = context.dp(16)
            setPadding(padH, 0, padH, 0)
            setBackgroundResource(
                if (primary) R.drawable.btn_primary else R.drawable.btn_secondary,
            )
            setTextColor(
                context.brand(if (primary) R.color.accent_ink else R.color.ink),
            )
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(8) }
        }
    }

    fun primaryButton(context: Context, text: String): Button =
        buttonBase(context, text, true)

    fun secondaryButton(context: Context, text: String): Button =
        buttonBase(context, text, false)

    fun View.accessibilityHeadingCompat() {
        if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }

    fun field(context: Context, label: String, multiline: Boolean = false): EditText = EditText(context).apply {
        contentDescription = label
        hint = label
        textSize = 16f
        setTextColor(context.brand(R.color.ink))
        setHintTextColor(context.brand(R.color.ink_dim))
        setBackgroundResource(R.drawable.btn_secondary)
        minHeight = context.dp(52)
        setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12))
        isSingleLine = !multiline
        if (multiline) { minLines = 2; maxLines = 5; gravity = Gravity.TOP }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(8) }
    }

    /** Narrow windows and enlarged text stack actions instead of clipping labels. */
    fun actions(context: Context, vararg buttons: Button): LinearLayout = LinearLayout(context).apply {
        val largest = buttons.maxOfOrNull { it.paint.measureText(it.text.toString()) + context.dp(32) } ?: 0f
        val needed = largest * buttons.size + context.dp(8 * (buttons.size - 1))
        val horizontal = needed <= context.dp(context.resources.configuration.screenWidthDp - 80) &&
            context.resources.configuration.fontScale <= 1.2f
        orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        buttons.forEachIndexed { index, button ->
            button.layoutParams = LinearLayout.LayoutParams(if (horizontal) 0 else -1, -2, if (horizontal) 1f else 0f).apply {
                topMargin = context.dp(8)
                if (horizontal && index > 0) marginStart = context.dp(8)
            }
            addView(button)
        }
    }

    /** Small status dot (8dp circle) tinted to [color]. */
    fun dot(context: Context, color: Int): View {
        val size = context.dp(9)
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginEnd = context.dp(10)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        }
    }

    /** One `label … value` row. Returns the row and the value view so
     *  callers can update the value after async work. Null [tone] hides
     *  the dot (plain info rows shouldn't strobe). */
    fun statusRow(
        context: Context,
        label: String,
        value: String,
        tone: Int? = null,
    ): Pair<LinearLayout, TextView> {
        lateinit var valueView: TextView
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, context.dp(5), 0, context.dp(5))
            if (tone != null) addView(dot(context, tone))
            addView(
                TextView(context).apply {
                    text = label
                    textSize = 15f
                    setTextColor(context.brand(R.color.ink_dim))
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
                    )
                },
            )
            valueView = TextView(context).apply {
                text = value
                textSize = 15f
                typeface = Typeface.MONOSPACE
                setTextColor(context.brand(R.color.ink))
                gravity = Gravity.END
            }
            addView(valueView)
        }
        return row to valueView
    }

    fun monoBlock(context: Context): TextView {
        return TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13.5f
            setTextColor(context.brand(R.color.ink))
            setLineSpacing(context.dp(3).toFloat(), 1f)
        }
    }

    fun bodyText(context: Context, sizeSp: Float = 17f): TextView {
        return TextView(context).apply {
            textSize = sizeSp
            setTextColor(context.brand(R.color.ink))
            setLineSpacing(context.dp(4).toFloat(), 1f)
        }
    }

    /**
     * Screen frame: a scrollable, edge-safe column capped at 620dp and
     * centered — full-bleed on phones, a composed column on a 1080p TV.
     * Returns the ScrollView (for setContentView) and the content column.
     */
    fun screenFrame(context: Context): Pair<android.widget.ScrollView, LinearLayout> {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // 920dp sounds narrow until a 320dpi TV turns it into 1840px.
            // 620dp ≈ 1240px on TV, full-bleed on phones either way.
            val maxW = context.dp(680)
            val screenW = context.dp(context.resources.configuration.screenWidthDp)
            val w = Math.min(screenW - context.dp(40), maxW)
            layoutParams = LinearLayout.LayoutParams(
                w, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
            setPadding(0, context.dp(26), 0, context.dp(26))
        }
        val center = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // Android 15 edge-to-edge draws under the status bar; consume
            // the inset as padding on every API level instead.
            fitsSystemWindows = true
            addView(content)
        }
        val scroll = android.widget.ScrollView(context).apply {
            setBackgroundColor(context.brand(R.color.bg))
            addView(center)
        }
        // Short screens (pair) sit centered instead of hugging the top;
        // tall screens that scroll keep their top anchor untouched.
        scroll.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    scroll.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    val spare = scroll.height - center.height
                    if (spare > 0) {
                        val pad = spare / 2
                        center.setPadding(0, pad, 0, pad)
                    }
                }
            },
        )
        return scroll to content
    }

    /** App `versionName (versionCode)` for footers. Never throws. */
    fun appVersion(context: Context): String {
        return try {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${info.versionCode})"
        } catch (_: Exception) {
            "?"
        }
    }
}
