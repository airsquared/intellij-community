// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.ide.menu

import com.intellij.diagnostic.StartUpMeasurer
import com.intellij.ide.DataManager
import com.intellij.ide.ui.UISettingsListener
import com.intellij.ide.ui.customization.CustomActionsSchema
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ActionManagerEx
import com.intellij.openapi.actionSystem.impl.ActionMenu
import com.intellij.openapi.actionSystem.impl.MenuItemPresentationFactory
import com.intellij.openapi.actionSystem.impl.PopupMenuPreloader
import com.intellij.openapi.actionSystem.impl.PresentationFactory
import com.intellij.openapi.actionSystem.impl.Utils
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.serviceAsync
import com.intellij.openapi.diagnostic.getOrLogException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.wm.WindowManager
import com.intellij.platform.diagnostic.telemetry.impl.rootTask
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.flow.throttle
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import java.awt.Dialog
import java.awt.Dimension
import java.awt.KeyboardFocusManager
import javax.swing.JComponent
import javax.swing.JFrame
import kotlin.coroutines.EmptyCoroutineContext

internal interface ActionAwareIdeMenuBar {
  fun updateMenuActions(forceRebuild: Boolean = false)
}

internal interface IdeMenuFlavor {
  val state: IdeMenuBarState
    get() = IdeMenuBarState.EXPANDED

  fun jMenuSelectionChanged(isIncluded: Boolean) {
  }

  fun getPreferredSize(size: Dimension): Dimension = size

  fun updateAppMenu()

  fun layoutClockPanelAndButton() {
  }

  fun correctMenuCount(menuCount: Int): Int = menuCount

  fun suspendAnimator() {}
}

private val LOG = logger<IdeMenuBarHelper>()

internal sealed class IdeMenuBarHelper(@JvmField val flavor: IdeMenuFlavor,
                                       @JvmField val menuBar: MenuBarImpl) : ActionAwareIdeMenuBar {
  protected abstract fun isUpdateForbidden(): Boolean

  @JvmField
  protected var visibleActions = emptyList<ActionGroup>()

  @JvmField
  protected val presentationFactory: PresentationFactory = MenuItemPresentationFactory()

  private val updateRequests = MutableSharedFlow<Boolean>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

  interface MenuBarImpl {
    val frame: JFrame

    val coroutineScope: CoroutineScope
    val component: JComponent

    fun updateGlobalMenuRoots()

    suspend fun getMainMenuActionGroup(): ActionGroup?
  }

  init {
    val app = ApplicationManager.getApplication()
    val coroutineScope = menuBar.coroutineScope + CoroutineName("IdeMenuBarHelper")
    if (app != null) {
      app.messageBus.connect(coroutineScope).subscribe(UISettingsListener.TOPIC, UISettingsListener {
        presentationFactory.reset()
        updateMenuActions(true)
      })
    }
    val initJob = coroutineScope.launch(
      (if (StartUpMeasurer.isEnabled()) rootTask() + CoroutineName("ide menu bar actions init") else EmptyCoroutineContext) +
      Dispatchers.EDT + ModalityState.any().asContextElement()) {
      val actions = expandMainActionGroup(true)
      doUpdateVisibleActions(actions, false)
      postInitActions(actions)
    }
    initJob.invokeOnCompletion { error ->
      if (error != null) {
        LOG.info("First menu bar update failed with $error")
      }
    }
    coroutineScope.launch {
      initJob.join()
      val timerEvents = (serviceAsync<ActionManager>() as? ActionManagerEx)?.timerEvents ?: emptyFlow()
      timerEvents.collect {
        updateRequests.tryEmit(false)
      }
    }
    coroutineScope.launch {
      withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
        updateRequests.throttle(500).collectLatest { forceRebuild ->
          runCatching {
            if (canUpdate()) {
              doUpdateVisibleActions(expandMainActionGroup(false), forceRebuild)
            }
          }.getOrLogException(LOG)
        }
      }
    }
  }

  private suspend fun expandMainActionGroup(isFirstUpdate: Boolean): List<ActionGroup> {
    val mainActionGroup = menuBar.getMainMenuActionGroup() ?: return emptyList()
    return expandMainActionGroup(mainActionGroup, menuBar.component, menuBar.frame, presentationFactory, isFirstUpdate)
  }

  private fun canUpdate(): Boolean {
    ThreadingAssertions.assertEventDispatchThread()
    if (!menuBar.frame.isShowing || !menuBar.frame.isActive) {
      return false
    }

    // do not update when a popup menu is shown
    // (if a popup menu contains an action which is also in the menu bar, it should not be enabled/disabled)
    if (isUpdateForbidden()) {
      return false
    }

    // don't update the toolbar if there is currently active modal dialog
    val window = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow
    return window !is Dialog || !window.isModal
  }

  @RequiresEdt
  final override fun updateMenuActions(forceRebuild: Boolean) {
    if (forceRebuild && LOG.isDebugEnabled) {
      LOG.debug(Throwable("Force rebuild menu bar"))
    }
    check(updateRequests.tryEmit(forceRebuild))
  }

  @RequiresEdt
  protected open suspend fun postInitActions(actions: List<ActionGroup>) {
    for (action in actions) {
      PopupMenuPreloader.install(menuBar.component, ActionPlaces.MAIN_MENU, null) { action }
    }
  }

  @RequiresEdt
  abstract suspend fun doUpdateVisibleActions(newVisibleActions: List<ActionGroup>, forceRebuild: Boolean)
}

private suspend fun expandMainActionGroup(mainActionGroup: ActionGroup,
                                          menuBar: JComponent,
                                          frame: JFrame,
                                          presentationFactory: PresentationFactory,
                                          isFirstUpdate: Boolean): List<ActionGroup> {
  ThreadingAssertions.assertEventDispatchThread()
  return withContext(CoroutineName("expandMainActionGroup")) {
    val windowManager = serviceAsync<WindowManager>()
    val targetComponent = windowManager.getFocusedComponent(frame) ?: menuBar
    val dataContext = DataManager.getInstance().getDataContext(targetComponent)
    Utils.expandActionGroupSuspend(mainActionGroup, presentationFactory, dataContext,
                                   ActionPlaces.MAIN_MENU, false, isFirstUpdate)
  }.filterIsInstance<ActionGroup>()
}

internal suspend fun getAndWrapMainMenuActionGroup(): ActionGroup? {
  val group = CustomActionsSchema.getInstanceAsync().getCorrectedActionAsync(IdeActions.GROUP_MAIN_MENU) ?: return null
  // enforce the "always-visible" flag for all main menu items
  // without forcing everyone to employ custom groups in their plugin.xml files.
  return object : ActionGroupWrapper(group) {
    override fun getChildren(e: AnActionEvent?): Array<out AnAction> {
      return super.getChildren(e).onEach { it.templatePresentation.putClientProperty(ActionMenu.ALWAYS_VISIBLE, true) }
    }
  }
}