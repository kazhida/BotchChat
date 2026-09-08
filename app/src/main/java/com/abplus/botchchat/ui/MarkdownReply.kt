package com.abplus.botchchat.ui

import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin

/** Native Markdown rendering, including incomplete Markdown during streaming. */
@Composable
internal fun MarkdownReply(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val codeBackground = MaterialTheme.colorScheme.surfaceContainer.toArgb()
    val codeColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val textSize = MaterialTheme.typography.bodyLarge.fontSize.value
    val markwon = remember(context, linkColor, codeBackground, codeColor) {
        Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun processMarkdown(markdown: String): String =
                    normalizeJapaneseQuoteEmphasis(markdown)

                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder.linkColor(linkColor)
                        .codeBackgroundColor(codeBackground)
                        .codeBlockBackgroundColor(codeBackground)
                        .codeTextColor(codeColor)
                        .codeBlockTextColor(codeColor)
                }
            })
            .build()
    }
    // No WebView or image-loading plugin: rendering never fetches remote resources.
    // Serialize work per renderer and keep Markdown parsing off the speech/UI thread.
    val renderDispatcher = remember(markwon) { Dispatchers.Default.limitedParallelism(1) }
    val rendered by produceState<Spanned?>(null, markwon, text) {
        value = withContext(renderDispatcher) { markwon.toMarkdown(text) }
    }
    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            TextView(viewContext).apply {
                setPadding(0, 0, 0, 0)
                setTextIsSelectable(true)
                movementMethod = LinkMovementMethod.getInstance()
            }
        },
        update = { view ->
            view.setTextColor(color.toArgb())
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
            rendered?.let { parsed ->
                if (view.tag !== parsed) {
                    markwon.setParsedMarkdown(view, parsed)
                    view.tag = parsed
                }
            }
        }
    )
}
