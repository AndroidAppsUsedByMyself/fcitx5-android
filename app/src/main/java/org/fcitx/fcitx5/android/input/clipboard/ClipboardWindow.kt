/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.annotation.SuppressLint
import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupMenu
import androidx.annotation.Keep
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.view.isVisible
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.google.android.material.snackbar.BaseTransientBottomBar.BaseCallback
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.snackbar.SnackbarContentLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.BooleanKey.ClipboardDbEmpty
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.BooleanKey.ClipboardListeningEnabled
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.State.AddMore
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.State.EnableListening
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.State.Normal
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.TransitionEvent.ClipboardDbUpdated
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.TransitionEvent.ClipboardListeningUpdated
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.EventStateMachine
import org.fcitx.fcitx5.android.utils.item
import org.fcitx.fcitx5.android.utils.styledColorOrDefault
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.dsl.core.withTheme
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

class ClipboardWindow : InputWindow.ExtendedInputWindow<ClipboardWindow>(),
    InputBroadcastReceiver {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()
    private val theme by manager.theme()
    private val fcitx by manager.fcitx()

    private val snackbarCtx by lazy {
        context.withTheme(R.style.InputViewSnackbarTheme)
    }
    private var snackbarInstance: Snackbar? = null

    private lateinit var stateMachine: EventStateMachine<ClipboardStateMachine.State, ClipboardStateMachine.TransitionEvent, ClipboardStateMachine.BooleanKey>

    @Keep
    private val clipboardEnabledListener = ManagedPreference.OnChangeListener<Boolean> { _, it ->
        stateMachine.push(
            ClipboardListeningUpdated, ClipboardListeningEnabled to it
        )
    }

    private val prefs = AppPrefs.getInstance().clipboard

    private val clipboardEnabledPref = prefs.clipboardListening
    private val clipboardReturnAfterPaste by prefs.clipboardReturnAfterPaste
    private val clipboardMaskSensitive by prefs.clipboardMaskSensitive

    private val clipboardEntryRadius by ThemeManager.prefs.clipboardEntryRadius

    private var isSearchMode = false
    private var currentQuery = ""

    /**
     * Height of the IME input area before the search mode expanded it. Restored on exit.
     */
    private var savedInputHeight: Int? = null

    /**
     * InputConnection bound to the search box, registered on the service while the search
     * box is focused so that the Fcitx keyboard output is typed into it instead of the host.
     */
    private var internalTextInput: InputConnection? = null

    private fun getPager(query: String? = null): Pager<Int, ClipboardEntry> {
        return if (query.isNullOrEmpty()) {
            Pager(PagingConfig(pageSize = 16)) { ClipboardManager.allEntries() }
        } else {
            Pager(PagingConfig(pageSize = 16)) { ClipboardManager.searchEntries(query) }
        }
    }

    private val clipboardEntriesPager by lazy { getPager() }
    private var adapterSubmitJob: Job? = null

    private val adapter: ClipboardAdapter by lazy {
        object : ClipboardAdapter(
            theme,
            context.dp(clipboardEntryRadius.toFloat()),
            clipboardMaskSensitive
        ) {
            override fun onPin(id: Int) {
                service.lifecycleScope.launch { ClipboardManager.pin(id) }
            }

            override fun onUnpin(id: Int) {
                service.lifecycleScope.launch { ClipboardManager.unpin(id) }
            }

            override fun onEdit(id: Int) {
                AppUtil.launchClipboardEdit(context, id)
            }

            override fun onShare(entry: ClipboardEntry) {
                val target = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, entry.text)
                }
                val chooser = Intent.createChooser(target, null).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                service.startActivity(chooser)
            }

            override fun onDelete(id: Int) {
                service.lifecycleScope.launch {
                    ClipboardManager.delete(id)
                    showUndoSnackbar(id)
                }
            }

            override fun onPaste(entry: ClipboardEntry) {
                deactivateSearchInput()
                service.commitText(entry.text)
                if (clipboardReturnAfterPaste) windowManager.attachWindow(KeyboardWindow)
            }
        }
    }

    private val ui by lazy {
        ClipboardUi(context, theme).apply {
            recyclerView.apply {
                layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
                adapter = this@ClipboardWindow.adapter
            }
            ItemTouchHelper(object : ItemTouchHelper.Callback() {
                override fun getMovementFlags(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder
                ): Int {
                    return makeMovementFlags(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT)
                }

                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    return false
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    val entry = adapter.getEntryAt(viewHolder.bindingAdapterPosition) ?: return
                    service.lifecycleScope.launch {
                        ClipboardManager.delete(entry.id)
                        showUndoSnackbar(entry.id)
                    }
                }
            }).attachToRecyclerView(recyclerView)
            enableUi.enableButton.setOnClickListener {
                clipboardEnabledPref.setValue(true)
            }
            deleteAllButton.setOnClickListener {
                service.lifecycleScope.launch {
                    promptDeleteAll(ClipboardManager.haveUnpinned())
                }
            }
        }
    }

    override fun onCreateView(): View = ui.root

    private var promptMenu: PopupMenu? = null

    private fun promptDeleteAll(skipPinned: Boolean) {
        promptMenu?.dismiss()
        promptMenu = PopupMenu(context, ui.deleteAllButton).apply {
            menu.add(buildSpannedString {
                bold {
                    color(context.styledColorOrDefault(android.R.attr.colorAccent, theme.genericActiveForegroundColor)) {
                        append(context.getString(if (skipPinned) R.string.delete_all_except_pinned else R.string.delete_all_pinned_items))
                    }
                }
            }).isEnabled = false
            menu.add(android.R.string.cancel)
            menu.item(android.R.string.ok) {
                service.lifecycleScope.launch {
                    val ids = ClipboardManager.deleteAll(skipPinned)
                    showUndoSnackbar(*ids)
                }
            }
            setOnDismissListener {
                if (it === promptMenu) promptMenu = null
            }
            show()
        }
    }

    private val pendingDeleteIds = arrayListOf<Int>()

    @SuppressLint("RestrictedApi")
    private fun showUndoSnackbar(vararg id: Int) {
        id.forEach { pendingDeleteIds.add(it) }
        val str = context.resources.getString(R.string.num_items_deleted, pendingDeleteIds.size)
        snackbarInstance = Snackbar.make(snackbarCtx, ui.root, str, Snackbar.LENGTH_LONG)
            .setBackgroundTint(theme.popupBackgroundColor)
            .setTextColor(theme.popupTextColor)
            .setActionTextColor(theme.genericActiveBackgroundColor)
            .setAction(R.string.undo) {
                service.lifecycleScope.launch {
                    ClipboardManager.undoDelete(*pendingDeleteIds.toIntArray())
                    pendingDeleteIds.clear()
                }
            }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar, event: Int) {
                    if (snackbarInstance === transientBottomBar) {
                        snackbarInstance = null
                    }
                    when (event) {
                        BaseCallback.DISMISS_EVENT_SWIPE,
                        BaseCallback.DISMISS_EVENT_MANUAL,
                        BaseCallback.DISMISS_EVENT_TIMEOUT -> {
                            service.lifecycleScope.launch {
                                ClipboardManager.realDelete()
                                pendingDeleteIds.clear()
                            }
                        }
                        BaseCallback.DISMISS_EVENT_ACTION,
                        BaseCallback.DISMISS_EVENT_CONSECUTIVE -> {
                            // user clicked "undo" or deleted more items which makes a new snackbar
                        }
                    }
                }
            }).apply {
                val hMargin = snackbarCtx.dp(24)
                val vMargin = snackbarCtx.dp(16)
                view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    leftMargin = hMargin
                    rightMargin = hMargin
                    bottomMargin = vMargin
                }
                ((view as FrameLayout).getChildAt(0) as SnackbarContentLayout).apply {
                    messageView.letterSpacing = 0f
                    actionView.letterSpacing = 0f
                }
                show()
            }
    }

    private val searchTextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            val query = s?.toString() ?: ""
            if (query != currentQuery) {
                currentQuery = query
                adapterSubmitJob?.cancel()
                adapterSubmitJob = service.lifecycleScope.launch {
                    getPager(query).flow.collect {
                        adapter.submitData(it)
                    }
                }
            }
        }
    }

    override fun onAttached() {
        val isEmpty = ClipboardManager.itemCount == 0
        val isListening = clipboardEnabledPref.getValue()
        val initialState = when {
            !isListening -> EnableListening
            isEmpty -> AddMore
            else -> Normal
        }
        stateMachine = ClipboardStateMachine.new(initialState, isEmpty, isListening) {
            ui.switchUiByState(it)
        }
        // manually switch to initial ui
        ui.switchUiByState(initialState)
        adapter.addLoadStateListener {
            val empty = it.append.endOfPaginationReached && adapter.itemCount < 1
            stateMachine.push(ClipboardDbUpdated, ClipboardDbEmpty to empty)
        }
        adapterSubmitJob = service.lifecycleScope.launch {
            clipboardEntriesPager.flow.collect {
                adapter.submitData(it)
            }
        }
        clipboardEnabledPref.registerOnChangeListener(clipboardEnabledListener)

        // Search functionality
        ui.searchIcon.setOnClickListener {
            toggleSearchMode()
        }
        ui.searchEditText.addTextChangedListener(searchTextWatcher)
        ui.searchEditText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) activateSearchInput() else deactivateSearchInput()
        }
        // don't let the system treat the internal search box as a normal editor:
        // the Fcitx keyboard is embedded in the window itself
        ui.searchEditText.setShowSoftInputOnFocus(false)
    }

    override fun onDetached() {
        deactivateSearchInput()
        clipboardEnabledPref.unregisterOnChangeListener(clipboardEnabledListener)
        adapter.onDetached()
        adapterSubmitJob?.cancel()
        promptMenu?.dismiss()
        snackbarInstance?.dismiss()
        ui.searchEditText.removeTextChangedListener(searchTextWatcher)
    }

    private fun toggleSearchMode() {
        isSearchMode = !isSearchMode
        if (isSearchMode) {
            ui.searchEditText.isVisible = true
            ui.searchEditText.requestFocus()
            activateSearchInput()
        } else {
            deactivateSearchInput()
            ui.searchEditText.isVisible = false
            ui.searchEditText.setText("")
            currentQuery = ""
            adapterSubmitJob?.cancel()
            adapterSubmitJob = service.lifecycleScope.launch {
                clipboardEntriesPager.flow.collect {
                    adapter.submitData(it)
                }
            }
        }
    }

    /**
     * Embed the Fcitx keyboard below the clipboard grid and redirect IME output to the
     * search box while it has focus.
     */
    private fun activateSearchInput() {
        if (internalTextInput != null) return
        // embed the keyboard first (its view gets created), then restore the text (QWERTY)
        // layout synchronously so it can't race with the embed below
        windowManager.attachAuxiliary(KeyboardWindow, ui.keyboardContainer)
        (windowManager.getEssentialWindow(KeyboardWindow) as? KeyboardWindow)?.resetToDefaultLayout()
        expandInputArea()
        internalTextInput = ui.searchEditText.onCreateInputConnection(EditorInfo())
        service.setInternalTextInput(internalTextInput)
    }

    /**
     * Enlarge the IME input area while searching so that the embedded keyboard below the
     * clipboard grid keeps its normal (100%) height, instead of being squeezed into the
     * space left next to the grid.
     */
    private fun expandInputArea() {
        val inputArea = windowManager.view
        // the very first activation can happen before the IME window is laid out; recording
        // height=0 here would leave the window collapsed after search is closed, so defer
        // until the size is actually known
        if (inputArea.height <= 0) {
            inputArea.doOnLayout { expandInputArea() }
            return
        }
        val keyboardHeight = savedInputHeight ?: inputArea.height
        if (savedInputHeight == null) {
            savedInputHeight = keyboardHeight
        }
        val gridExtra = inputArea.context.dp(ClipboardUi.SearchGridMaxHeightDp)
        inputArea.updateLayoutParams<ViewGroup.LayoutParams> {
            height = keyboardHeight + gridExtra
        }
        ui.setSearchKeyboardVisible(true, keyboardHeight)
    }

    private fun restoreInputArea() {
        val restored = savedInputHeight
        savedInputHeight = null
        if (restored != null) {
            windowManager.view.updateLayoutParams<ViewGroup.LayoutParams> { height = restored }
        }
    }

    private fun deactivateSearchInput() {
        if (internalTextInput == null) return
        service.setInternalTextInput(null)
        internalTextInput = null
        windowManager.detachAuxiliary()
        (windowManager.getEssentialWindow(KeyboardWindow) as? KeyboardWindow)?.resetToDefaultLayout()
        ui.setSearchKeyboardVisible(false)
        restoreInputArea()
        // drop any candidate strip left over from fcitx
        ui.setCandidates(arrayOf())
    }

    override fun onCandidateUpdate(data: FcitxEvent.CandidateListEvent.Data) {
        // candidates only make sense while the search box (and its embedded keyboard) is
        // actually active: after focus is lost the keyboard is detached, so drop them
        if (isSearchMode && internalTextInput != null) {
            ui.setCandidates(data.candidates) { index ->
                fcitx.launchOnReady { it.select(index) }
            }
        } else {
            ui.setCandidates(arrayOf())
        }
    }

    override val title: String by lazy {
        context.getString(R.string.clipboard)
    }

    override fun onCreateBarExtension(): View = ui.extension
}