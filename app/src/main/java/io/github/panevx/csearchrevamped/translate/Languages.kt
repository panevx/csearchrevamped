package io.github.panevx.csearchrevamped.translate

import android.content.Context
import android.graphics.PixelFormat
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.ListView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.nl.translate.TranslateLanguage
import io.github.panevx.csearchrevamped.R
import java.util.Locale

/** The languages on-device translation supports, named in the user's own language. */
object Languages {

    fun name(code: String): String =
        Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault())
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }

    private fun all(): List<String> = TranslateLanguage.getAllLanguages().sortedBy { name(it) }

    private fun labels(codes: List<String>, current: String) =
        codes.map { if (it == current) "✓  ${name(it)}" else name(it) }

    /** Language list as a dialog, for use inside an activity. */
    fun pick(context: Context, current: String, onPicked: (String) -> Unit) {
        val codes = all()
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.choose_language)
            .setItems(labels(codes, current).toTypedArray()) { _, which -> onPicked(codes[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.listView?.setSelection(maxOf(0, codes.indexOf(current) - 2))
    }

    /**
     * Language list as an accessibility overlay window, for use from the accessibility service.
     * (Regular dialogs shown from a service don't reliably receive touches.)
     */
    fun pickOverlay(context: Context, wm: WindowManager, current: String, onPicked: (String) -> Unit) {
        val codes = all()
        val panel = LayoutInflater.from(context).inflate(R.layout.language_panel, null)
        fun close() = runCatching { wm.removeView(panel) }
        panel.findViewById<View>(R.id.languageScrim).setOnClickListener { close() }
        panel.findViewById<View>(R.id.languageCancel).setOnClickListener { close() }
        panel.findViewById<ListView>(R.id.languageList).apply {
            adapter = ArrayAdapter(context, R.layout.language_item, labels(codes, current))
            setSelection(maxOf(0, codes.indexOf(current) - 2))
            setOnItemClickListener { _, _, position, _ ->
                close()
                onPicked(codes[position])
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        wm.addView(panel, params)
    }
}
