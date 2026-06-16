package com.aistra.hail.views

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class AlphabetFastScroller : View {
    enum class Placement { START, END, BOTTOM }

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
    private var contentAlpha = 0f
    private var waveAnimator: ValueAnimator? = null
    private var fadeAnimator: ValueAnimator? = null
    private var placement = Placement.END

    var onLetterSelected: ((Char, Float) -> Unit)? = null
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
        if (availableLetters.size <= 1) {
            fadeAnimator?.cancel()
            contentAlpha = 0f
            visibility = GONE
        }
        invalidate()
    }

    fun setPlacement(value: Placement) {
        placement = value
        invalidate()
    }

    fun showScroller() {
        if (availableLetters.size <= 1) return
        fadeAnimator?.cancel()
        visibility = VISIBLE
        alpha = 1f
        fadeAnimator = ValueAnimator.ofFloat(contentAlpha, 1f).apply {
            duration = 120L
            addUpdateListener {
                contentAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun hideScroller(delayMillis: Long = 700L) {
        if (visibility != VISIBLE) return
        fadeAnimator?.cancel()
        fadeAnimator = ValueAnimator.ofFloat(contentAlpha, 0f).apply {
            startDelay = delayMillis
            duration = 180L
            addUpdateListener {
                contentAlpha = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (contentAlpha == 0f) visibility = GONE
                }
            })
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateColors()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (availableLetters.isEmpty()) return
        if (contentAlpha <= 0f) return
        val layer = canvas.saveLayerAlpha(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            (contentAlpha * 255).roundToInt().coerceIn(0, 255)
        )
        val horizontal = placement == Placement.BOTTOM
        val cellSize = (if (horizontal) width else height) / letters.size.toFloat()
        val trackSize = min(if (horizontal) height.toFloat() else width.toFloat(), cellSize)
        val textSize = (trackSize * 0.58f).coerceIn(7f.sp, 12f.sp)
        enabledPaint.textSize = textSize
        disabledPaint.textSize = textSize
        selectedTextPaint.textSize = textSize
        letters.forEachIndexed { index, letter ->
            val baseX = if (horizontal) cellSize * index + cellSize / 2f else width / 2f
            val baseY = if (horizontal) height / 2f else cellSize * index + cellSize / 2f
            val available = letter in availableLetters
            val selected = letter == selectedLetter && available
            val distance = abs(index - touchProgress)
            val influence = ((3f - distance) / 3f).coerceIn(0f, 1f) * waveProgress
            val scale = 1f + 0.55f * influence
            val shift = when (placement) {
                Placement.START -> width * 0.28f * influence
                Placement.END -> -width * 0.28f * influence
                Placement.BOTTOM -> -height * 0.22f * influence
            }
            val radius = trackSize * 0.42f
            val centerX = if (horizontal) baseX else (baseX + shift).coerceIn(radius, width - radius)
            val centerY = if (horizontal) {
                (baseY + shift).coerceIn(radius, height - radius)
            } else {
                baseY.coerceIn(radius, height - radius)
            }
            if (selected) {
                canvas.drawCircle(
                    centerX,
                    centerY,
                    radius,
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
            canvas.drawText(letter.toString(), centerX, centerY - (paint.ascent() + paint.descent()) / 2f, paint)
            paint.textSize = originalTextSize
        }
        canvas.restoreToCount(layer)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (availableLetters.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                waveAnimator?.cancel()
                waveProgress = 1f
                showScroller()
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
                hideScroller(360L)
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

    private fun updateSelectedLetter(eventY: Float) {
        val length = max(if (placement == Placement.BOTTOM) width else height, 1)
        val position = if (placement == Placement.BOTTOM) lastTouchX else eventY
        val anchorFraction = if (placement == Placement.BOTTOM) {
            0.78f
        } else {
            (position.coerceIn(0f, length - 1f) / length).coerceIn(0f, 1f)
        }
        touchProgress = ((position.coerceIn(0f, length - 1f) / length) * letters.size)
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
            onLetterSelected?.invoke(letter, anchorFraction)
        } else if (letter !in availableLetters && dispatchedLetter != null) {
            dispatchedLetter = null
            onLetterCleared?.invoke()
        }
    }

    private val lastTouchX: Float get() = recentX
    private var recentX: Float = 0f

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        recentX = event.x
        return super.dispatchTouchEvent(event)
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
