// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.feedback.general.general

import com.intellij.internal.statistic.eventLog.EventLogGroup
import com.intellij.internal.statistic.eventLog.events.EventFields
import com.intellij.internal.statistic.service.fus.collectors.CounterUsagesCollector

internal class GeneralFeedbackCountCollector : CounterUsagesCollector() {
  companion object {
    private val GROUP = EventLogGroup("general.feedback", 1)

    private val INTERFACE_RATING_FIELD = EventFields.Int("interface_rating")
    private val PRICE_RATING_FIELD = EventFields.Int("price_rating")
    private val STABILITY_RATING_FIELD = EventFields.Int("stability_rating")
    private val FEATURE_SET_RATING_FIELD = EventFields.Int("feature_set_rating")
    private val PERFORMANCE_RATING_FIELD = EventFields.Int("performance_rating")

    private val EVALUATION_FEEDBACK_SENT = GROUP.registerVarargEvent("general.feedback.sent",
                                                                     EventFields.Int("interface_rating"),
                                                                     EventFields.Int("price_rating"),
                                                                     EventFields.Int("stability_rating"),
                                                                     EventFields.Int("feature_set_rating"),
                                                                     EventFields.Int("performance_rating"))

    private val EVALUATION_FEEDBACK_DIALOG_SHOWN = GROUP.registerVarargEvent("general.feedback.shown")
    private val EVALUATION_FEEDBACK_DIALOG_CANCELED = GROUP.registerVarargEvent("general.feedback.cancelled")

    fun logGeneralFeedbackSent(interfaceRating: Int,
                               priceRating: Int,
                               stabilityRating: Int,
                               featureSetRating: Int,
                               performanceRating: Int) {

      EVALUATION_FEEDBACK_SENT.log(
        INTERFACE_RATING_FIELD.with(interfaceRating),
        PRICE_RATING_FIELD.with(priceRating),
        STABILITY_RATING_FIELD.with(stabilityRating),
        FEATURE_SET_RATING_FIELD.with(featureSetRating),
        PERFORMANCE_RATING_FIELD.with(performanceRating)
      )
    }

    fun logGeneralFeedbackDialogShown() {
      EVALUATION_FEEDBACK_DIALOG_SHOWN.log()
    }

    fun logGeneralFeedbackDialogCanceled() {
      EVALUATION_FEEDBACK_DIALOG_CANCELED.log()
    }
  }

  override fun getGroup(): EventLogGroup = GROUP
}