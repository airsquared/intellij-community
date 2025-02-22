// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.tools.projectWizard.gradle

import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.openapi.observable.properties.GraphProperty
import com.intellij.openapi.util.Key
import org.jetbrains.plugins.gradle.service.project.wizard.GradleNewProjectWizardData

interface GradleKotlinNewProjectWizardData : GradleNewProjectWizardData {

    val addSampleCodeProperty: GraphProperty<Boolean>

    var addSampleCode: Boolean

    val generateOnboardingTipsProperty: GraphProperty<Boolean>

    var generateOnboardingTips: Boolean

    companion object {

        val KEY = Key.create<GradleKotlinNewProjectWizardData>(GradleKotlinNewProjectWizardData::class.java.name)

        @JvmStatic
        val NewProjectWizardStep.kotlinGradleData: GradleKotlinNewProjectWizardData?
            get() = data.getUserData(KEY)
    }
}