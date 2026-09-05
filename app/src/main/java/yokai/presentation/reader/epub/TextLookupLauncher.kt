package yokai.presentation.reader.epub

import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Launches Android or browser actions for text selected in the EPUB reader.
 *
 * Readium's selection callback should pass the selected text to these methods.
 * Every external action returns false when no compatible activity is installed,
 * allowing the reader UI to show a concise error instead of crashing.
 */
object TextLookupLauncher {

    fun define(context: Context, selectedText: String): Boolean {
        val text = normalized(selectedText) ?: return false
        return launch(
            context,
            Intent(Intent.ACTION_DEFINE).putExtra(Intent.EXTRA_TEXT, text),
        )
    }

    fun webSearch(context: Context, selectedText: String): Boolean {
        val text = normalized(selectedText) ?: return false
        return launch(
            context,
            Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, text),
        )
    }

    fun google(context: Context, selectedText: String): Boolean {
        val text = normalized(selectedText) ?: return false
        val uri = Uri.Builder()
            .scheme("https")
            .authority("www.google.com")
            .appendPath("search")
            .appendQueryParameter("q", text)
            .build()
        return launch(context, Intent(Intent.ACTION_VIEW, uri))
    }

    fun translate(context: Context, selectedText: String): Boolean {
        val text = normalized(selectedText) ?: return false
        val uri = Uri.Builder()
            .scheme("https")
            .authority("translate.google.com")
            .appendPath("")
            .appendQueryParameter("sl", "auto")
            .appendQueryParameter("tl", "en")
            .appendQueryParameter("text", text)
            .appendQueryParameter("op", "translate")
            .build()
        return launch(context, Intent(Intent.ACTION_VIEW, uri))
    }

    fun copy(context: Context, selectedText: String): Boolean {
        val text = normalized(selectedText) ?: return false
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText("Selected text", text))
        return true
    }

    fun share(context: Context, selectedText: String): Boolean {
        val text = normalized(selectedText) ?: return false
        val sendIntent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        return launch(context, Intent.createChooser(sendIntent, null))
    }

    private fun normalized(selectedText: String): String? =
        selectedText.trim().takeIf(String::isNotEmpty)

    private fun launch(context: Context, intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) return false
        context.startActivity(intent)
        return true
    }
}
