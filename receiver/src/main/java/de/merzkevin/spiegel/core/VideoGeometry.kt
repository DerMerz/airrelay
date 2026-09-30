package de.merzkevin.spiegel.core

data class Size(val width: Int, val height: Int) {
    val isValid: Boolean get() = width > 0 && height > 0
}

data class Rect(val left: Int, val top: Int, val width: Int, val height: Int)

/** Upper bound for the advertised mirroring size (the Stick Lite decodes up to 1920x1088). */
val MAX_MIRROR_SIZE = Size(1920, 1080)

/**
 * Largest rectangle with the aspect ratio of [content] that fits into [container],
 * centred. The rest stays black (letterbox or pillarbox). Unknown content size fills the
 * container.
 */
fun fitCentered(content: Size, container: Size): Rect {
    if (!container.isValid) return Rect(0, 0, 0, 0)
    if (!content.isValid) return Rect(0, 0, container.width, container.height)
    // compare aspect ratios without floating point: content.w/content.h vs container.w/container.h
    val contentWider = content.width.toLong() * container.height >= container.width.toLong() * content.height
    val w: Int
    val h: Int
    if (contentWider) {
        w = container.width
        h = ((container.width.toLong() * content.height + content.width / 2) / content.width).toInt()
    } else {
        h = container.height
        w = ((container.height.toLong() * content.width + content.height / 2) / content.height).toInt()
    }
    return Rect((container.width - w) / 2, (container.height - h) / 2, w, h)
}

/**
 * Size announced to the sender: the display in landscape, capped by what the decoder can
 * do and by [MAX_MIRROR_SIZE]. Unknown decoder limits count as the cap.
 */
fun advertisedSize(display: Size, decoderMax: Size?): Size {
    val landscape = if (display.height > display.width) Size(display.height, display.width) else display
    val source = if (landscape.isValid) landscape else MAX_MIRROR_SIZE
    val limit = decoderMax?.takeIf { it.isValid } ?: MAX_MIRROR_SIZE
    return Size(
        minOf(source.width, limit.width, MAX_MIRROR_SIZE.width),
        minOf(source.height, limit.height, MAX_MIRROR_SIZE.height),
    )
}

/**
 * Visible picture size from a decoder output format. Hardware decoders pad to multiples of
 * 16 (1920x1088) and report the real picture as an inclusive crop rectangle.
 * Crop values are -1 when absent.
 */
fun visibleSize(width: Int, height: Int, cropLeft: Int, cropTop: Int, cropRight: Int, cropBottom: Int): Size {
    val hasCrop = cropLeft >= 0 && cropTop >= 0 && cropRight >= cropLeft && cropBottom >= cropTop
    return if (hasCrop) Size(cropRight - cropLeft + 1, cropBottom - cropTop + 1) else Size(width, height)
}
