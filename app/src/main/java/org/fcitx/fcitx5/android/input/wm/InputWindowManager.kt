/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.wm

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.transition.Transition
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcaster
import org.fcitx.fcitx5.android.input.dependency.UniqueViewComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.must
import org.mechdancer.dependency.minusAssign
import org.mechdancer.dependency.plusAssign
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import timber.log.Timber

class InputWindowManager : UniqueViewComponent<InputWindowManager, FrameLayout>(),
    InputBroadcastReceiver {

    private val context by manager.context()
    private val broadcaster: InputBroadcaster by manager.must()
    private lateinit var scope: DynamicScope

    private val essentialWindows = mutableMapOf<EssentialWindow.Key, Pair<InputWindow, View?>>()

    private var currentWindow: InputWindow? = null
    private var currentView: View? = null

    /**
     * An essential window embedded inside an arbitrary container of another window (e.g. the
     * keyboard shown below the clipboard search grid), without taking over the "current window".
     */
    private var auxiliaryWindow: Pair<EssentialWindow.Key, View>? = null
    private var auxiliaryContainer: ViewGroup? = null

    private val disableAnimation by AppPrefs.getInstance().advanced.disableAnimation

    private fun prepareAnimation(
        exitAnimation: Transition?,
        enterAnimation: Transition?,
        remove: View,
        add: View
    ) {
        if (disableAnimation)
            return
        enterAnimation?.addTarget(add)
        exitAnimation?.addTarget(remove)
        TransitionManager.beginDelayedTransition(view, TransitionSet().apply {
            enterAnimation?.let { addTransition(it) }
            exitAnimation?.let { addTransition(it) }
            duration = 100
        })
    }

    /**
     * Associate essential window with its key and add it to scope
     * If [createView] is `true`, the view will be created immediately.
     * Otherwise, it will be created on first attach
     */
    fun <R> addEssentialWindow(
        window: R,
        createView: Boolean = false
    ) where R : InputWindow, R : EssentialWindow {
        if (window.key in essentialWindows) {
            if (essentialWindows[window.key]!!.first === window)
                Timber.d("Skip adding essential window $window")
            else
                throw IllegalStateException("${window.key} is already occupied")
        }
        scope += window
        val view = if (createView) window.onCreateView() else null
        essentialWindows[window.key] = window to view
    }

    fun getEssentialWindow(windowKey: EssentialWindow.Key) =
        essentialWindows[windowKey]?.first
            ?: throw IllegalArgumentException("Unable to find essential window associated with $windowKey")

    /**
     * Attach an essential window by key
     * IMPORTANT: the window key must be known,
     * i.e. the essential window should be added first via [addEssentialWindow].
     * Moreover, [attachWindow] can also add the essential window with key.
     */
    fun attachWindow(windowKey: EssentialWindow.Key) {
        essentialWindows[windowKey]?.let { (window, _) ->
            attachWindow(window)
        } ?: throw IllegalStateException("$windowKey is not a known essential window key")
    }

    /**
     * Remove an essential window
     */
    fun removeEssentialWindow(windowKey: EssentialWindow.Key) {
        val (window, _) = essentialWindows[windowKey]
            ?: throw IllegalStateException("$windowKey is not a known essential window key")
        if (currentWindow === window)
            throw IllegalStateException("$windowKey cannot be removed when it's active")
        // remove from scope
        scope -= window
        // remove from map
        essentialWindows.remove(windowKey)
    }

    /**
     * Attach a new window, removing the old one
     * This function initialize the view for windows and save it for essential windows.
     * [attachWindow] includes the operation done by [addEssentialWindow].
     */
    fun attachWindow(window: InputWindow) {
        if (window === currentWindow)
            Timber.d("Skip attaching $window")
        val newView = if (window is EssentialWindow) {
            // keep the view for essential windows
            essentialWindows[window.key]?.second ?: window.onCreateView()
                .also { essentialWindows[window.key] = window to it }
        } else {
            // add the new window to scope, except essential windows (they are always in scope)
            scope += window
            window.onCreateView()
        }
        if (currentWindow != null) {
            val oldWindow = currentWindow!!
            val oldView = currentView!!
            prepareAnimation(
                oldWindow.exitAnimation(window),
                window.enterAnimation(oldWindow),
                oldView,
                newView
            )
            // notify the window that it will be detached
            oldWindow.onDetached()
            // remove the old window from layout
            view.removeView(oldView)
            // broadcast the old window was removed from layout
            broadcaster.onWindowDetached(oldWindow)
            Timber.d("Detach $oldWindow")
            // finally remove the old window from scope only if it's not an essential window,
            if (oldWindow !is EssentialWindow)
                scope -= oldWindow
        }
        // call before attached for essential window
        if (window is EssentialWindow)
            window.beforeAttached()
        // add the new window to layout
        view.apply { add(newView, lParams(matchParent, matchParent)) }
        currentView = newView
        Timber.d("Attach $window")
        // notify the window it was attached
        window.onAttached()
        currentWindow = window
        // broadcast the new window was added to layout
        broadcaster.onWindowAttached(window)
    }

    /**
     * Embed an essential window (its view saved in the window manager) into [container] of
     * another window, without detaching the currently attached window.
     * Only one auxiliary window can be attached at a time.
     * Notice that no attach/detach broadcast is sent for this auxiliary window,
     * so the bar/title UI is not affected by it.
     */
    fun attachAuxiliary(windowKey: EssentialWindow.Key, container: ViewGroup) {
        check(auxiliaryWindow == null) { "An auxiliary window is already attached" }
        val (window, savedView) = essentialWindows[windowKey]
            ?: throw IllegalArgumentException("Essential window $windowKey is not registered")
        val view = savedView ?: window.onCreateView().also {
            essentialWindows[windowKey] = window to it
        }
        check(view.parent == null) { "View of essential window $windowKey is already attached elsewhere" }
        if (window is EssentialWindow) window.beforeAttached()
        container.addView(
            view,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        window.onAttached()
        auxiliaryWindow = windowKey to view
        auxiliaryContainer = container
    }

    /**
     * Remove the auxiliary window (if any) from its container and notify its lifecycle callbacks.
     */
    fun detachAuxiliary() {
        val pair = auxiliaryWindow ?: return
        auxiliaryWindow = null
        auxiliaryContainer = null
        val (windowKey, view) = pair
        // remove the view first: a re-entrant detach (eg. triggered from the window's
        // onDetached) would otherwise try to manipulate a view still attached to the layout
        view.parent?.let { (it as? ViewGroup)?.removeView(view) }
        essentialWindows[windowKey]?.first?.onDetached()
    }

    /**
     * Whether an auxiliary window is currently attached.
     */
    val isAuxiliary: Boolean get() = auxiliaryWindow != null

    /**
     * Replace the current auxiliary window with [windowKey] in the same container.
     * The old auxiliary is detached and the new one is attached.
     */
    fun switchAuxiliary(windowKey: EssentialWindow.Key) {
        val container = auxiliaryContainer
            ?: error("No auxiliary container")
        val (oldKey, oldView) = auxiliaryWindow
            ?: error("No auxiliary window attached")
        // detach old auxiliary
        oldView.parent?.let { (it as? ViewGroup)?.removeView(oldView) }
        essentialWindows[oldKey]?.first?.onDetached()
        // attach new auxiliary
        val (window, savedView) = essentialWindows[windowKey]
            ?: throw IllegalArgumentException("Essential window $windowKey is not registered")
        val newView = savedView ?: window.onCreateView().also {
            essentialWindows[windowKey] = window to it
        }
        check(newView.parent == null) { "View of essential window $windowKey is already attached elsewhere" }
        if (window is EssentialWindow) window.beforeAttached()
        container.addView(
            newView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        window.onAttached()
        auxiliaryWindow = windowKey to newView
    }

    override val view: FrameLayout by lazy { context.frameLayout(R.id.input_window) }

    override fun onScopeSetupFinished(scope: DynamicScope) {
        this.scope = scope
    }

    fun isAttached(window: InputWindow) = currentWindow === window
}