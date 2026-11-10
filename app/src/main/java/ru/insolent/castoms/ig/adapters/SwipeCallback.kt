package ru.insolent.castoms.ig.adapters

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.view.View
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import ru.insolent.castoms.ig.R

class SwipeCallback(
    private val onSwipeLeft: (position: Int) -> Unit
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {

    private val background = ColorDrawable(Color.parseColor("#E8F5E9")) // Светло-зеленый фон
    private val iconColor = Color.parseColor("#4CAF50") // Зеленая иконка
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean = false

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        if (direction == ItemTouchHelper.LEFT) {
            // ✅ Вызываем callback, но НЕ удаляем элемент
            onSwipeLeft(viewHolder.adapterPosition)

            // ✅ Возвращаем элемент на место
            viewHolder.itemView.translationX = 0f
        }
    }

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX < 0) {
            // Рисуем фон
            val itemView = viewHolder.itemView
            /*background.setBounds(
                itemView.right + dX.toInt(),
                itemView.top,
                itemView.right,
                itemView.bottom
            )
            background.draw(c)*/

            // Рисуем иконку "ответить" (стрелка влево)
            paint.color = iconColor
            paint.textSize = 60f
            paint.textAlign = Paint.Align.CENTER

            val iconText = "↩" // Символ стрелки
            val centerY = itemView.top + (itemView.bottom - itemView.top) / 2f
            val centerX = itemView.right - 100f // Отступ от правого края

            c.drawText(iconText, centerX, centerY + 20f, paint)
        }

        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
    }

    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float {
        return 0.3f // Свайп срабатывает при 30% ширины экрана
    }

    override fun getSwipeEscapeVelocity(defaultVelocity: Float): Float {
        return defaultVelocity * 2 // Увеличиваем скорость для более четкого свайпа
    }
}