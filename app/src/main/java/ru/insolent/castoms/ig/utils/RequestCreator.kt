package ru.insolent.castoms.ig.utils

import android.content.Context
import com.squareup.picasso.RequestCreator

fun RequestCreator.customCornersDp(
    context: Context,
    topLeftDp: Float = 0f,
    topRightDp: Float = 0f,
    bottomLeftDp: Float = 0f,
    bottomRightDp: Float = 0f
): RequestCreator {
    val density = context.resources.displayMetrics.density

    // Переводим каждый угол из DP в пиксели
    val tlPx = topLeftDp * density
    val trPx = topRightDp * density
    val blPx = bottomLeftDp * density
    val brPx = bottomRightDp * density

    // Создаем массив из 8 радиусов для Path.addRoundRect
    val radii = floatArrayOf(
        tlPx, tlPx,
        trPx, trPx,
        brPx, brPx,
        blPx, blPx
    )

    // Передаем массив в обновленную трансформацию
    return this.transform(MultiCornersTransformation(radii))
}