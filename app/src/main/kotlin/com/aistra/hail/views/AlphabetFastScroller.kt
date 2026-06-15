package com.aistra.hail.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.google.android.material.color.MaterialColors
import kotlin.math.min

class AlphabetFastScroller : View {
    private val letters = ('A'..'Z').toList()
    private val enabledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val disabledPaint = Paint(enabledPaint).apply { alpha = 88 }
    private val selectedTextPaint = Paint(enabledPaint)
    private val selectedBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var availableLetters = emptySet<Char>()
    private var selectedLetter: Char? = null
    private var dispatchedLetter: Char? = null

    var onLetterSelected: ((Char) -> Unit)? = null
    var onLetterCleared: (() -> Unit)? = null

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    fun setAvailableLetters(value: Collection<Char>) {
        availableLetters = value.map { it.uppercaseChar() }.filter { it in 'A'..'Z' }.toSet()
        visibility = if (availableLetters.size > 1) VISIBLE else GONE
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateColors()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (availableLetters.isEmpty()) return
        val cellHeight = height / letters.size.toFloat()
        val textSize = (cellHeight * 0.58f).coerceIn(7f.sp, 12f.sp)
        enabledPaint.textSize = textSize
        disabledPaint.textSize = textSize
        selectedTextPaint.textSize = textSize
        letters.forEachIndexed { index, letter ->
            val centerY = cellHeight * index + cellHeight / 2f
            val available = letter in availableLetters
            val selected = letter == selectedLetter && available
            if (selected) {
                canvas.drawCircle(
                    width / 2f,
                    centerY,
                    min(width.toFloat(), cellHeight) * 0.42f,
                    selectedBackgroundPaint
                )
            }
            val paint = when {
                selected -> selectedTextPaint
                available -> enabledPaint
                else -> disabledPaint
            }
            canvas.drawText(letter.toString(), width / 2f, centerY - (paint.ascent() + paint.descent()) / 2f, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (availableLetters.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                updateSelectedLetter(event.y)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                updateSelectedLetter(event.y)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                selectedLetter = null
                dispatchedLetter = null
                parent.requestDisallowInterceptTouchEvent(false)
                onLetterCleared?.invoke()
                invalidate()
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateSelectedLetter(y: Float) {
        val index = ((y.coerceIn(0f, height - 1f) / height) * letters.size).toInt()
            .coerceIn(0, letters.lastIndex)
        val letter = letters[index]
        if (selectedLetter != letter) {
            selectedLetter = letter
            invalidate()
        }
        if (letter in availableLetters && dispatchedLetter != letter) {
            dispatchedLetter = letter
            onLetterSelected?.invoke(letter)
        } else if (letter !in availableLetters && dispatchedLetter != null) {
            dispatchedLetter = null
            onLetterCleared?.invoke()
        }
    }

    private fun updateColors() {
        enabledPaint.color = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSurfaceVariant
        )
        disabledPaint.color = enabledPaint.color
        disabledPaint.alpha = 88
        selectedTextPaint.color = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnPrimary
        )
        selectedBackgroundPaint.color = MaterialColors.getColor(
            this,
            androidx.appcompat.R.attr.colorPrimary
        )
    }

    private val Float.sp
        get() = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, this, resources.displayMetrics)
}
