package com.tvnas.filebrowser.util

import android.graphics.BitmapFactory

/**
 * 图片解码采样率计算工具，供缩略图与全屏查看复用。
 */
object BitmapSampler {

    /**
     * 根据目标尺寸计算 BitmapFactory 的 inSampleSize（2 的幂）。
     * 返回 1 表示无需采样。
     */
    fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight &&
                (halfWidth / inSampleSize) >= reqWidth
            ) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
