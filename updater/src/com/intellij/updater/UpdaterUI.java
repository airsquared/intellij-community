// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.updater;

import java.util.List;
import java.util.Map;

public interface UpdaterUI {
  void setDescription(String oldBuildDesc, String newBuildDesc);
  void setDescription(String text);

  void startProcess(String title);
  void setProgress(int percentage);
  void setProgressIndeterminate();
  void checkCancelled() throws OperationCancelledException;

  void showError(String message);

  void askUser(String message) throws OperationCancelledException;
  Map<String, ValidationResult.Option> askUser(List<ValidationResult> validationResults) throws OperationCancelledException;

  default String bold(String text) { return text; }
}
