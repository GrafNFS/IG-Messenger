package ru.insolent.castoms.ig.utils

import android.graphics.*
import com.squareup.picasso.Transformation

class MultiCornersTransformation(private val radii: FloatArray) : Transformation {

    override fun transform(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val paint = Paint().apply {
            isAntiAlias = true
            shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }

        val path = Path().apply {
            addRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radii, Path.Direction.CW)
        }

        canvas.drawPath(path, paint)

        if (output != source) {
            source.recycle()
        }

        return output
    }

    override fun key(): String {
        return "multi_corners(${radii.joinToString()})"
    }
}

