// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.sdk.add.v2

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.dsl.builder.Panel
import com.intellij.util.text.nullize
import com.jetbrains.python.PyBundle.message
import com.jetbrains.python.sdk.poetry.detectPoetryExecutable
import com.jetbrains.python.sdk.poetry.poetryPath
import com.jetbrains.python.sdk.poetry.setupPoetrySdkUnderProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PoetryNewEnvironmentCreator(presenter: PythonAddInterpreterPresenter) : PythonAddEnvironment(presenter) {

  val executable = propertyGraph.property(UNKNOWN_EXECUTABLE)
  private lateinit var poetryPathField: TextFieldWithBrowseButton
  private lateinit var basePythonComboBox: ComboBox<String>
  override fun buildOptions(panel: Panel) {
    with(panel) {
      row(message("sdk.create.custom.base.python")) {
        basePythonComboBox = pythonBaseInterpreterComboBox(presenter, presenter.basePythonSdksFlow, presenter.basePythonHomePath)
      }

      poetryPathField = executableSelector(message("sdk.create.custom.poetry.path"), executable,
                                           message("sdk.create.custom.poetry.missing.text"))
    }
  }

  override fun onShown() {
    val savedPath = PropertiesComponent.getInstance().poetryPath
    if (savedPath != null) {
      executable.set(savedPath)
    }
    else {
      val modalityState = ModalityState.current().asContextElement()
      state.scope.launch(Dispatchers.IO) {
        val poetryExecutable = detectPoetryExecutable()
        withContext(Dispatchers.EDT + modalityState) {
          poetryExecutable?.let { executable.set(it.path) }
        }
      }
    }
  }


  override fun getOrCreateSdk(): Sdk {
    PropertiesComponent.getInstance().poetryPath = executable.get().nullize()
    return setupPoetrySdkUnderProgress(null, null, state.basePythonSdks.get(), state.projectPath.get(),
                                       state.basePythonVersion.get()!!.homePath, false)!!
  }


}