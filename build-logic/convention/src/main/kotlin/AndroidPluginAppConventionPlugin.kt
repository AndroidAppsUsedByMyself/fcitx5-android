/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import javax.xml.XMLConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory

/**
 * Register `assemble${Variant}Plugins` task for root project,
 * and make all plugins' `assemble${Variant}` depends on it
 */
class AndroidPluginAppConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        // Must match the host's applicationId, including the APP_ID override.
        val appIdBase = target.findProperty("APP_ID") as String? ?: "org.fcitx.fcitx5.android"
        target.extensions.configure<ApplicationExtension> {
            buildFeatures {
                buildConfig = true
            }
            buildTypes {
                release {
                    buildConfigField("String", "MAIN_APPLICATION_ID", "\"$appIdBase\"")
                    addManifestPlaceholders(
                        mapOf(
                            "mainApplicationId" to appIdBase,
                        )
                    )
                }
                debug {
                    val debugAppId = "$appIdBase.debug"
                    buildConfigField("String", "MAIN_APPLICATION_ID", "\"$debugAppId\"")
                    addManifestPlaceholders(
                        mapOf(
                            "mainApplicationId" to debugAppId,
                        )
                    )
                }
            }
        }
        val verifyPluginManifests = target.tasks.register("verifyPluginManifests") {
            group = "verification"
            description = "Validate res/xml/plugin.xml against plugin/pluginSchema.xsd"
            val manifestFile = target.layout.projectDirectory.file("src/main/res/xml/plugin.xml")
            val schemaFile = target.rootProject.layout.projectDirectory.file("plugin/pluginSchema.xsd")
            inputs.files(manifestFile, schemaFile)
            doLast {
                if (!manifestFile.asFile.isFile) {
                    throw GradleException("Missing plugin manifest: ${manifestFile.asFile}")
                }
                val error = runCatching {
                    SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
                        .newSchema(schemaFile.asFile)
                        .newValidator()
                        .validate(StreamSource(manifestFile.asFile))
                }.exceptionOrNull()
                if (error != null) {
                    throw GradleException(
                        "Invalid plugin manifest ${manifestFile.asFile}: ${error.message}",
                        error
                    )
                }
            }
        }
        target.tasks.configureEach {
            if (name == "preBuild") dependsOn(verifyPluginManifests)
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
