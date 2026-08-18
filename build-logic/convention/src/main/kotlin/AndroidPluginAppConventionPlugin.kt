/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Register `assemble${Variant}Plugins` task for root project,
 * and make all plugins' `assemble${Variant}` depends on it
 */
class AndroidPluginAppConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        val mainAppId = (target.findProperty("APP_ID") as? String) ?: "org.fcitx.fcitx5.android"
        val pluginName = target.name.replace("-", "_")
        val pluginAppId = (target.findProperty("APP_ID") as? String)?.let { "$it.plugin.$pluginName" }
            ?: "org.fcitx.fcitx5.android.plugin.$pluginName"
        // namespace must stay the fixed source package: generated BuildConfig/R classes
        // and relative class names in the merged manifest are resolved against it,
        // while applicationId (the installed package) varies with APP_ID
        val pluginNamespace = "org.fcitx.fcitx5.android.plugin.$pluginName"
        target.extensions.configure<ApplicationExtension> {
            buildFeatures {
                buildConfig = true
            }
            namespace = pluginNamespace
            defaultConfig {
                applicationId = pluginAppId
            }
            buildTypes {
                release {
                    buildConfigField("String", "MAIN_APPLICATION_ID", "\"$mainAppId\"")
                    addManifestPlaceholders(
                        mapOf(
                            "mainApplicationId" to mainAppId,
                        )
                    )
                }
                debug {
                    buildConfigField("String", "MAIN_APPLICATION_ID", "\"$mainAppId.debug\"")
                    addManifestPlaceholders(
                        mapOf(
                            "mainApplicationId" to "$mainAppId.debug",
                        )
                    )
                }
            }
        }
        target.extensions.configure<ApplicationAndroidComponentsExtension> {
            onVariants { variant ->
                val variantName = variant.name.capitalized()
                target.afterEvaluate {
                    val pluginsTaskName = "assemble${variantName}Plugins"
                    val pluginsTask = target.rootProject.tasks.findByName(pluginsTaskName)
                        ?: target.rootProject.tasks.register(pluginsTaskName).get()
                    pluginsTask?.dependsOn(target.tasks.getByName("assemble${variantName}"))
                }
            }
        }
    }

}
