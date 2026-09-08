package com.abplus.botchchat.data

/** Splits long replies at sentence boundaries where possible, without cutting UTF-16 pairs. */
internal fun speechChunks(text: String, maxLength: Int): List<String> {
    require(maxLength >= 2)
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(start + maxLength, text.length)
        if (end < text.length) {
            if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            val boundary = (end - 1 downTo start + (end - start) / 2)
                .firstOrNull { text[it] in "。！？.!?\n" }
            if (boundary != null) end = boundary + 1
        }
        chunks += text.substring(start, end)
        start = end
    }
    return chunks
}
