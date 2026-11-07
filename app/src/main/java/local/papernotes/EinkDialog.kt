package local.papernotes

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Rückfrage- und Auswahldialoge nach den E-Ink-Regeln (weiß, schwarzer Rand, keine Animation, Abdunkelung oder Schatten;
 * Touch-Ziele mindestens 48–56dp hoch).
 */
internal object EinkDialog {
    fun confirm(activity: Activity, title: String, message: String, positive: String, onConfirm: () -> Unit): Dialog {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = Math.round(v * density)
        fun line() = View(activity).apply { setBackgroundColor(activity.getColor(R.color.black)) }
        fun button(label: String, onClick: () -> Unit) =
            TextView(activity, null, 0, R.style.Widget_PaperNotes_DialogButton).apply {
                text = label
                setOnClickListener { onClick() }
            }

        val dialog = Dialog(activity, R.style.Theme_PaperNotes_TemplateDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(16))
        }
        body.addView(
            TextView(activity).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(activity.getColor(R.color.black))
                setTypeface(typeface, Typeface.BOLD)
            },
        )
        body.addView(
            TextView(activity).apply {
                text = message
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(activity.getColor(R.color.black))
                setPadding(0, dp(12), 0, 0)
            },
        )
        root.addView(body)
        root.addView(line(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        val buttons = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(button(activity.getString(R.string.cancel)) { dialog.dismiss() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        buttons.addView(line(), LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
        buttons.addView(
            button(positive) {
                dialog.dismiss()
                onConfirm()
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
        )
        root.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        dialog.setContentView(root)
        dialog.window?.let { w ->
            w.setLayout(minOf(activity.resources.displayMetrics.widthPixels - dp(48), dp(520)), ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setWindowAnimations(0)
        }
        dialog.show()
        return dialog
    }

    /** Meldungsdialog mit einer Schaltfläche [ok] (Ergebnisanzeige, keine Rückfrage). */
    fun message(activity: Activity, title: String, message: String, ok: String, onDismiss: () -> Unit = {}): Dialog {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = Math.round(v * density)
        val dialog = Dialog(activity, R.style.Theme_PaperNotes_TemplateDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(16))
        }
        body.addView(
            TextView(activity).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(activity.getColor(R.color.black))
                setTypeface(typeface, Typeface.BOLD)
            },
        )
        body.addView(
            TextView(activity).apply {
                text = message
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(activity.getColor(R.color.black))
                setPadding(0, dp(12), 0, 0)
            },
        )
        root.addView(body)
        root.addView(View(activity).apply { setBackgroundColor(activity.getColor(R.color.black)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        val button = TextView(activity, null, 0, R.style.Widget_PaperNotes_DialogButton).apply {
            text = ok
            setOnClickListener { dialog.dismiss() }
        }
        root.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        dialog.setOnDismissListener { onDismiss() }
        dialog.setContentView(root)
        dialog.window?.let { w ->
            w.setLayout(minOf(activity.resources.displayMetrics.widthPixels - dp(48), dp(520)), ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setWindowAnimations(0)
        }
        dialog.show()
        return dialog
    }

    /** Fortschrittsdialog: nur Text (keine Animation, E-Ink), unten "Abbrechen". */
    class Progress(val dialog: Dialog, private val message: TextView) {
        fun setText(text: String) {
            message.text = text
        }

        fun dismiss() {
            if (dialog.isShowing) dialog.dismiss()
        }
    }

    /** Zeigt [title] und einen aktualisierbaren Text; "Abbrechen" und die Zurück-Taste rufen [onCancel] und schließen den Dialog. */
    fun progress(activity: Activity, title: String, initialText: String, onCancel: () -> Unit): Progress {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = Math.round(v * density)
        val dialog = Dialog(activity, R.style.Theme_PaperNotes_TemplateDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(false)
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(16))
        }
        body.addView(
            TextView(activity).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(activity.getColor(R.color.black))
                setTypeface(typeface, Typeface.BOLD)
            },
        )
        val message = TextView(activity).apply {
            text = initialText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(activity.getColor(R.color.black))
            setPadding(0, dp(12), 0, 0)
        }
        body.addView(message)
        root.addView(body)
        root.addView(View(activity).apply { setBackgroundColor(activity.getColor(R.color.black)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        val cancel = TextView(activity, null, 0, R.style.Widget_PaperNotes_DialogButton).apply {
            text = activity.getString(R.string.cancel)
            setOnClickListener {
                onCancel()
                dialog.dismiss()
            }
        }
        root.addView(cancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        dialog.setOnCancelListener { onCancel() }
        dialog.setContentView(root)
        dialog.window?.let { w ->
            w.setLayout(minOf(activity.resources.displayMetrics.widthPixels - dp(48), dp(520)), ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setWindowAnimations(0)
        }
        dialog.show()
        return Progress(dialog, message)
    }

    /**
     * Auswahldialog mit Einzelauswahl im E-Ink-Stil. Bei Klick auf eine Option wird [onSelected] mit dem Index aufgerufen.
     * Mit [selectedIndex] = -1 ist keine Option markiert (reine Aktionsliste).
     */
    fun singleChoice(
        activity: Activity,
        title: String,
        items: List<String>,
        selectedIndex: Int,
        onSelected: (Int) -> Unit,
    ): Dialog {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = Math.round(v * density)
        fun lineGray() = View(activity).apply { setBackgroundColor(activity.getColor(R.color.line_gray)) }
        fun lineBlack() = View(activity).apply { setBackgroundColor(activity.getColor(R.color.black)) }
        fun button(label: String, onClick: () -> Unit) =
            TextView(activity, null, 0, R.style.Widget_PaperNotes_DialogButton).apply {
                text = label
                setOnClickListener { onClick() }
            }

        val dialog = Dialog(activity, R.style.Theme_PaperNotes_TemplateDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(16))
        }
        header.addView(
            TextView(activity).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(activity.getColor(R.color.black))
                setTypeface(typeface, Typeface.BOLD)
            },
        )
        root.addView(header)
        root.addView(lineBlack(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        val listLayout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        for ((index, itemText) in items.withIndex()) {
            val isSelected = index == selectedIndex
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                setPadding(dp(24), dp(8), dp(24), dp(8))
                background = activity.getDrawable(R.drawable.bg_list_row)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    onSelected(index)
                }
            }
            val text = TextView(activity).apply {
                this.text = itemText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTextColor(activity.getColor(if (isSelected) R.color.black else R.color.text_tint))
                if (isSelected) setTypeface(typeface, Typeface.BOLD)
            }
            row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (isSelected) {
                val icon = ImageView(activity).apply {
                    setImageResource(R.drawable.ic_check)
                    imageTintList = ColorStateList.valueOf(activity.getColor(R.color.black))
                }
                row.addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)))
            }
            listLayout.addView(row)
            if (index < items.size - 1) {
                listLayout.addView(lineGray(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            }
        }
        root.addView(listLayout)
        root.addView(lineBlack(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        val buttons = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(
            button(activity.getString(R.string.cancel)) { dialog.dismiss() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        root.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        dialog.setContentView(root)
        dialog.window?.let { w ->
            w.setLayout(minOf(activity.resources.displayMetrics.widthPixels - dp(48), dp(520)), ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setWindowAnimations(0)
        }
        dialog.show()
        return dialog
    }
}
