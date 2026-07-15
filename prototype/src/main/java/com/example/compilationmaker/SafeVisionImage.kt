package com.example.compilationmaker

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

const val MIN_INPUT_IMAGE_DIMENSION = 32

data class SafeCropRect(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val usedFullFrameFallback: Boolean = false
) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
    val isValidForInputImage: Boolean get() = width >= MIN_INPUT_IMAGE_DIMENSION && height >= MIN_INPUT_IMAGE_DIMENSION
}

object SafeVisionImage {
    fun computeSafeCropRect(
        frameWidth: Int,
        frameHeight: Int,
        scanWindow: ScanWindow,
        allowFullFrameFallback: Boolean = true
    ): SafeCropRect? {
        if (frameWidth <= 0 || frameHeight <= 0) return null
        if (frameWidth < MIN_INPUT_IMAGE_DIMENSION || frameHeight < MIN_INPUT_IMAGE_DIMENSION) return null

        val xPercent = scanWindow.xPercent.coerceIn(0f, 1f)
        val yPercent = scanWindow.yPercent.coerceIn(0f, 1f)
        val widthPercent = scanWindow.widthPercent.coerceIn(0f, 1f)
        val heightPercent = scanWindow.heightPercent.coerceIn(0f, 1f)

        val rawLeft = floor(frameWidth * xPercent).toInt().coerceIn(0, frameWidth - 1)
        val rawTop = floor(frameHeight * yPercent).toInt().coerceIn(0, frameHeight - 1)
        val rawRight = ceil(frameWidth * (xPercent + widthPercent)).toInt().coerceIn(rawLeft + 1, frameWidth)
        val rawBottom = ceil(frameHeight * (yPercent + heightPercent)).toInt().coerceIn(rawTop + 1, frameHeight)

        val targetWidth = max(MIN_INPUT_IMAGE_DIMENSION, rawRight - rawLeft)
        val targetHeight = max(MIN_INPUT_IMAGE_DIMENSION, rawBottom - rawTop)

        if (targetWidth > frameWidth || targetHeight > frameHeight) {
            return if (allowFullFrameFallback && frameWidth >= MIN_INPUT_IMAGE_DIMENSION && frameHeight >= MIN_INPUT_IMAGE_DIMENSION) {
                SafeCropRect(0, 0, frameWidth, frameHeight, usedFullFrameFallback = true)
            } else {
                null
            }
        }

        val centerX = (rawLeft + rawRight) / 2f
        val centerY = (rawTop + rawBottom) / 2f
        val left = (centerX - targetWidth / 2f).toInt().coerceIn(0, frameWidth - targetWidth)
        val top = (centerY - targetHeight / 2f).toInt().coerceIn(0, frameHeight - targetHeight)
        return SafeCropRect(left, top, targetWidth, targetHeight)
    }

    fun userFacingNoValidFramesMessage(): String =
        "No valid frames found in the selected ROI. Try increasing the ROI size or using higher quality."
}
