package com.aistra.hail.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
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
    private var touchProgress = 0f
    private var waveProgress = 0f
    private var waveAnimator: ValueAnimator? = null

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
            val distance = kotlin.math.abs(index - touchProgress)
            val influence = ((3f - distance) / 3f).coerceIn(0f, 1f) * waveProgress
            val scale = 1f + 0.55f * influence
            val shift = -width * 0.28f * influence
            if (selected) {
                canvas.drawCircle(
                    width / 2f + shift,
                    centerY,
                    min(width.toFloat(), cellHeight) * 0.46f,
                    selectedBackgroundPaint
                )
            }
            val paint = when {
                selected -> selectedTextPaint
                available -> enabledPaint
                else -> disabledPaint
            }
            val originalTextSize = paint.textSize
            paint.textSize = originalTextSize * scale
            canvas.drawText(letter.toString(), width / 2f + shift, centerY - (paint.ascent() + paint.descent()) / 2f, paint)
            paint.textSize = originalTextSize
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (availableLetters.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                waveAnimator?.cancel()
                waveProgress = 1f
                updateSelectedLetter(event.y)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                updateSelectedLetter(event.y)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dispatchedLetter = null
                parent.requestDisallowInterceptTouchEvent(false)
                onLetterCleared?.invoke()
                fadeOutWave()
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
        touchProgress = ((y.coerceIn(0f, height - 1f) / height) * letters.size)
            .coerceIn(0f, letters.lastIndex.toFloat())
        val index = touchProgress.toInt().coerceIn(0, letters.lastIndex)
        val letter = letters[index]
        if (selectedLetter != letter) {
            selectedLetter = letter
            invalidate()
        }
        if (letter in availableLetters && dispatchedLetter != letter) {
            dispatchedLetter = letter
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onLetterSelected?.invoke(letter)
        } else if (letter !in availableLetters && dispatchedLetter != null) {
            dispatchedLetter = null
            onLetterCleared?.invoke()
        }
    }

    private fun fadeOutWave() {
        waveAnimator?.cancel()
        waveAnimator = ValueAnimator.ofFloat(waveProgress, 0f).apply {
            duration = 180L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                waveProgress = it.animatedValue as Float
                if (waveProgress == 0f) selectedLetter = null
                invalidate()
            }
            start()
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
