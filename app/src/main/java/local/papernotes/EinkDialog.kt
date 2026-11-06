package local.papernotes

import android.app.Activity
import android.app.Dialog
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Rückfrage-Dialog nach den E-Ink-Regeln der Startseite (weiß, schwarzer Rand, keine Animation, Abdunkelung oder Schatten;
 * unten "Abbrechen" und die Bestätigung, je 56dp hoch). Gleiche Optik wie `MainActivity.openDialog`.
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
}
