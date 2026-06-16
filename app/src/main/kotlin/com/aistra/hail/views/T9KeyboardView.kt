package com.aistra.hail.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import kotlin.math.min

class T9KeyboardView : View {
    private enum class KeyAction { DIGIT, HIDE, BACKSPACE }

    private data class Key(
        val label: String,
        val subLabel: String = "",
        val action: KeyAction = KeyAction.DIGIT,
        val digit: Char? = null
    )

    private val keys = listOf(
        Key("1", digit = '1'),
        Key("2", "ABC", digit = '2'),
        Key("3", "DEF", digit = '3'),
        Key("4", "GHI", digit = '4'),
        Key("5", "JKL", digit = '5'),
        Key("6", "MNO", digit = '6'),
        Key("7", "PQRS", digit = '7'),
        Key("8", "TUV", digit = '8'),
        Key("9", "WXYZ", digit = '9'),
        Key("", action = KeyAction.HIDE),
        Key("0", digit = '0'),
        Key("", action = KeyAction.BACKSPACE)
    )
    private val keyBounds = Array(keys.size) { RectF() }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }
    private val subLabelPaint = Paint(labelPaint).apply {
        typeface = Typeface.DEFAULT_BOLD
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val iconPath = Path()
    private var surfaceColor = 0
    private var keyColor = 0
    private var pressedKeyColor = 0
    private var labelColor = 0
    private var pressedLabelColor = 0
    private var subLabelColor = 0
    private var dividerColor = 0
    private var pressedIndex = -1

    var onDigitClick: ((Char) -> Unit)? = null
    var onBackspaceClick: (() -> Unit)? = null
    var onHideClick: (() -> Unit)? = null

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    init {
        isClickable = true
        isFocusable = false
        visibility = GONE
    }

    fun showKeyboard() {
        if (visibility == VISIBLE) return
        visibility = VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(120L).start()
    }

    fun hideKeyboard() {
        if (visibility != VISIBLE) return
        animate().cancel()
        pressedIndex = -1
        visibility = GONE
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateColors()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = 256f.dp.toInt()
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(surfaceColor)
        canvas.drawRect(0f, 0f, width.toFloat(), 1f.dp, dividerPaint)
        val outerPadding = 8f.dp
        val gap = 6f.dp
        val availableWidth = width - outerPadding * 2f
        val availableHeight = height - outerPadding * 2f
        val keyWidth = (availableWidth - gap * 2f) / 3f
        val keyHeight = (availableHeight - gap * 3f) / 4f
        labelPaint.textSize = min(24f.sp, keyHeight * 0.42f)
        subLabelPaint.textSize = min(10f.sp, keyHeight * 0.18f)
        iconPaint.strokeWidth = 2f.dp
        keys.forEachIndexed { index, key ->
            val row = index / 3
            val col = index % 3
            val left = outerPadding + col * (keyWidth + gap)
            val top = outerPadding + row * (keyHeight + gap)
            val bounds = keyBounds[index]
            bounds.set(left, top, left + keyWidth, top + keyHeight)
            keyPaint.color = if (index == pressedIndex) pressedKeyColor else keyColor
            canvas.drawRoundRect(bounds, 8f.dp, 8f.dp, keyPaint)
            val primaryColor = if (index == pressedIndex) pressedLabelColor else labelColor
            val secondaryColor = if (index == pressedIndex) pressedLabelColor else subLabelColor
            when (key.action) {
                KeyAction.DIGIT -> drawDigit(canvas, key, bounds, primaryColor, secondaryColor)
                KeyAction.HIDE -> drawHideIcon(canvas, bounds, primaryColor)
                KeyAction.BACKSPACE -> drawBackspaceIcon(canvas, bounds, primaryColor)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                pressedIndex = keyIndexAt(event.x, event.y)
                if (pressedIndex >= 0) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val newPressedIndex = keyIndexAt(event.x, event.y)
                if (newPressedIndex != pressedIndex) {
                    pressedIndex = newPressedIndex
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val releasedIndex = pressedIndex
                pressedIndex = -1
                parent.requestDisallowInterceptTouchEvent(false)
                invalidate()
                if (releasedIndex >= 0 && releasedIndex == keyIndexAt(event.x, event.y)) {
                    dispatchKey(keys[releasedIndex])
                    performClick()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pressedIndex = -1
                parent.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun dispatchKey(key: Key) {
        when (key.action) {
            KeyAction.DIGIT -> key.digit?.let { onDigitClick?.invoke(it) }
            KeyAction.HIDE -> onHideClick?.invoke()
            KeyAction.BACKSPACE -> onBackspaceClick?.invoke()
        }
    }

    private fun keyIndexAt(x: Float, y: Float): Int =
        keyBounds.indexOfFirst { it.contains(x, y) }

    private fun drawDigit(canvas: Canvas, key: Key, bounds: RectF, primaryColor: Int, secondaryColor: Int) {
        labelPaint.color = primaryColor
        subLabelPaint.color = secondaryColor
        val centerX = bounds.centerX()
        val labelBaseline = if (key.subLabel.isEmpty()) {
            bounds.centerY() - (labelPaint.ascent() + labelPaint.descent()) / 2f
        } else {
            bounds.centerY() - 3f.dp
        }
        canvas.drawText(key.label, centerX, labelBaseline, labelPaint)
        if (key.subLabel.isNotEmpty()) {
            canvas.drawText(key.subLabel, centerX, bounds.centerY() + 15f.dp, subLabelPaint)
        }
    }

    private fun drawHideIcon(canvas: Canvas, bounds: RectF, color: Int) {
        iconPaint.color = color
        val centerX = bounds.centerX()
        val centerY = bounds.centerY()
        val size = min(bounds.width(), bounds.height()) * 0.24f
        iconPath.reset()
        iconPath.moveTo(centerX - size, centerY - size * 0.35f)
        iconPath.lineTo(centerX, centerY + size * 0.45f)
        iconPath.lineTo(centerX + size, centerY - size * 0.35f)
        canvas.drawPath(iconPath, iconPaint)
    }

    private fun drawBackspaceIcon(canvas: Canvas, bounds: RectF, color: Int) {
        iconPaint.color = color
        val centerX = bounds.centerX()
        val centerY = bounds.centerY()
        val width = min(bounds.width(), bounds.height()) * 0.48f
        val height = width * 0.58f
        val left = centerX - width * 0.42f
        val right = centerX + width * 0.5f
        val top = centerY - height / 2f
        val bottom = centerY + height / 2f
        iconPath.reset()
        iconPath.moveTo(left, centerY)
        iconPath.lineTo(left + height * 0.42f, top)
        iconPath.lineTo(right, top)
        iconPath.lineTo(right, bottom)
        iconPath.lineTo(left + height * 0.42f, bottom)
        iconPath.close()
        canvas.drawPath(iconPath, iconPaint)
        val crossSize = height * 0.22f
        val crossX = centerX + width * 0.18f
        canvas.drawLine(crossX - crossSize, centerY - crossSize, crossX + crossSize, centerY + crossSize, iconPaint)
        canvas.drawLine(crossX + crossSize, centerY - crossSize, crossX - crossSize, centerY + crossSize, iconPaint)
    }

    private fun updateColors() {
        val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
        val onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
        surfaceColor = ColorUtils.blendARGB(surface, onSurface, 0.03f)
        keyColor = ColorUtils.blendARGB(surface, onSurface, 0.07f)
        pressedKeyColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer)
        labelColor = onSurface
        pressedLabelColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimaryContainer)
        subLabelColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
        dividerColor = ColorUtils.blendARGB(surface, onSurface, 0.14f)
        backgroundPaint.color = surfaceColor
        dividerPaint.color = dividerColor
    }

    private val Float.dp
        get() = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, this, resources.displayMetrics)

    private val Float.sp
        get() = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, this, resources.displayMetrics)
}
