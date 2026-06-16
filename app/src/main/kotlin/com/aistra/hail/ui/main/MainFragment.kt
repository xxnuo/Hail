package com.aistra.hail.ui.main

import android.text.InputFilter
import android.text.InputType
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.SearchView
import androidx.core.content.getSystemService
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.aistra.hail.app.HailData

abstract class MainFragment : Fragment() {
    private class T9DigitInputFilter : InputFilter {
        override fun filter(
            source: CharSequence,
            start: Int,
            end: Int,
            dest: android.text.Spanned?,
            dstart: Int,
            dend: Int
        ): CharSequence? {
            val digits = source.subSequence(start, end).filter { it in '0'..'9' }
            return if (digits.length == end - start) null else digits
        }
    }

    protected val activity: MainActivity get() = requireActivity() as MainActivity

    private var t9Owner: View? = null
    private var t9InsetTarget: View? = null
    private var t9AppliedPadding = 0
    private var t9BackCallback: OnBackPressedCallback? = null

    protected fun setupT9EditText(
        editText: EditText,
        insetTarget: View
    ) {
        if (!HailData.nineKeySearch) {
            resetSystemInput(editText)
            return
        }
        setupT9Input(
            ownerView = editText,
            editText = editText,
            insetTarget = insetTarget,
            appendDigit = { digit ->
                editText.append(digit.toString())
            },
            backspace = { deleteLastChar(editText) },
            clearFocus = editText::clearFocus
        )
    }

    protected fun setupT9Search(
        searchView: SearchView,
        menuItem: MenuItem,
        insetTarget: View
    ) {
        val editText = searchView.findViewById<EditText>(androidx.appcompat.R.id.search_src_text)
        if (!HailData.nineKeySearch) {
            resetSystemInput(editText)
            return
        }
        val activate = setupT9Input(
            ownerView = searchView,
            editText = editText,
            insetTarget = insetTarget,
            appendDigit = { digit ->
                editText.append(digit.toString())
            },
            backspace = { deleteLastChar(editText) },
            clearFocus = searchView::clearFocus
        ) ?: return
        searchView.setOnSearchClickListener { activate() }
        searchView.setOnQueryTextFocusChangeListener { _, hasFocus ->
            if (hasFocus) activate()
        }
        searchView.setOnCloseListener {
            if (searchView.query.isNullOrEmpty()) hideT9Keyboard(searchView)
            false
        }
        menuItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                searchView.post { activate() }
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                hideT9Keyboard(searchView)
                return true
            }
        })
    }

    private fun setupT9Input(
        ownerView: View,
        editText: EditText,
        insetTarget: View,
        appendDigit: (Char) -> Unit,
        backspace: () -> Unit,
        clearFocus: () -> Unit
    ): (() -> Unit)? {
        if (!HailData.nineKeySearch) return null
        t9BackCallback?.remove()
        val backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                clearFocus()
                hideT9Keyboard(ownerView)
            }
        }
        t9BackCallback = backCallback
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        editText.inputType = InputType.TYPE_NULL
        editText.showSoftInputOnFocus = false
        setT9DigitFilter(editText, true)

        fun activate() {
            t9Owner = ownerView
            t9InsetTarget = insetTarget
            hideSystemKeyboard(editText)
            activity.t9Keyboard.onDigitClick = { digit ->
                if (t9Owner == ownerView && viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    appendDigit(digit)
                }
            }
            activity.t9Keyboard.onBackspaceClick = {
                if (t9Owner == ownerView && viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    backspace()
                }
            }
            activity.t9Keyboard.onHideClick = {
                clearFocus()
                hideT9Keyboard(ownerView)
            }
            activity.t9Keyboard.showKeyboard()
            activity.fab.hide()
            backCallback.isEnabled = true
            applyT9PaddingAfterLayout()
        }

        editText.setOnClickListener { activate() }
        editText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) activate()
        }
        viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onPause(owner: LifecycleOwner) {
                hideT9Keyboard(ownerView)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                hideT9Keyboard(ownerView)
            }
        })
        return ::activate
    }

    private fun hideT9Keyboard(owner: View) {
        if (t9Owner != owner) return
        activity.t9Keyboard.hideKeyboard()
        activity.t9Keyboard.onDigitClick = null
        activity.t9Keyboard.onBackspaceClick = null
        activity.t9Keyboard.onHideClick = null
        t9BackCallback?.isEnabled = false
        applyT9Padding(0)
        t9Owner = null
        t9InsetTarget = null
        if (activity.fab.tag == true) activity.fab.show()
    }

    private fun applyT9PaddingAfterLayout() {
        activity.t9Keyboard.post {
            if (activity.t9Keyboard.visibility == View.VISIBLE) {
                applyT9Padding(activity.t9Keyboard.height)
            }
        }
    }

    private fun applyT9Padding(keyboardHeight: Int) {
        val target = t9InsetTarget ?: return
        val basePadding = target.paddingBottom - t9AppliedPadding
        t9AppliedPadding = keyboardHeight
        target.setPadding(target.paddingLeft, target.paddingTop, target.paddingRight, basePadding + keyboardHeight)
    }

    private fun hideSystemKeyboard(view: View) {
        view.context.getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun resetSystemInput(editText: EditText) {
        editText.inputType = InputType.TYPE_CLASS_TEXT
        editText.showSoftInputOnFocus = true
        setT9DigitFilter(editText, false)
    }

    private fun setT9DigitFilter(editText: EditText, enabled: Boolean) {
        val filters = editText.filters.filterNot { it is T9DigitInputFilter }
        editText.filters = if (enabled) (filters + T9DigitInputFilter()).toTypedArray() else filters.toTypedArray()
    }

    private fun deleteLastChar(editText: EditText) {
        val text = editText.text ?: return
        if (text.isEmpty()) return
        text.delete(text.length - 1, text.length)
        editText.setSelection(text.length)
    }
}
