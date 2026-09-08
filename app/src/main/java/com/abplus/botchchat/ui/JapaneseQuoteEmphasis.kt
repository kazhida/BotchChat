package com.abplus.botchchat.ui

/** Adds delimiter boundaries required by CommonMark around Japanese quotation emphasis. */
internal fun normalizeJapaneseQuoteEmphasis(markdown: String): String {
    val quote = Regex("\\*\\*(?:「[^」\\r\\n]+」)+\\*\\*")
    val fenceStart = Regex("^(?: {0,3}> ?)* {0,3}(`{3,}|~{3,})(.*)$")
    var fence: Char? = null
    var fenceLength = 0
    var inlineTicks = 0
    return markdown.split('\n').joinToString("\n") { line ->
        val marker = fenceStart.matchEntire(line)
        if (fence != null) {
            if (marker != null && marker.groupValues[1].first() == fence &&
                marker.groupValues[1].length >= fenceLength && marker.groupValues[2].isBlank()) {
                fence = null
            }
            line
        } else if (inlineTicks == 0 && marker != null) {
            fence = marker.groupValues[1].first()
            fenceLength = marker.groupValues[1].length
            line
        } else if (inlineTicks == 0 && (line.startsWith("    ") || line.startsWith('\t'))) {
            line
        } else {
            buildString {
                var index = 0
                while (index < line.length) {
                    if (line[index] == '`') {
                        var end = index + 1
                        while (end < line.length && line[end] == '`') end++
                        val count = end - index
                        if (inlineTicks == 0) inlineTicks = count
                        else if (inlineTicks == count) inlineTicks = 0
                        append(line.substring(index, end))
                        index = end
                    } else if (inlineTicks == 0 && line[index] == '\\' && index + 1 < line.length) {
                        append(line.substring(index, index + 2))
                        index += 2
                    } else {
                        val match = if (inlineTicks == 0) quote.matchAt(line, index) else null
                        if (match != null) {
                            if (isNotEmpty() && !last().isWhitespace()) append(' ')
                            append(match.value)
                            index = match.range.last + 1
                            if (index < line.length && !line[index].isWhitespace()) append(' ')
                        } else {
                            append(line[index++])
                        }
                    }
                }
            }
        }
    }
}
