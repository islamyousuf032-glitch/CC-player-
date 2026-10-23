package com.ccplayer.media.android.color

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.RgbMatrix
import com.ccplayer.core.media.color.ColorGrade
import com.ccplayer.core.media.color.ColorGradeMatrix

/** The exact same effect object is used by ExoPlayer preview and Transformer export. */
@UnstableApi
class ColorGradeEffect(private val grade: ColorGrade) : RgbMatrix {
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray =
        ColorGradeMatrix.create(grade, useHdr)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = grade.isIdentity
}

@UnstableApi
object ColorGradeEffects {
    fun create(grade: ColorGrade): List<Effect> =
        if (grade.isIdentity) emptyList() else listOf(ColorGradeEffect(grade))
}
