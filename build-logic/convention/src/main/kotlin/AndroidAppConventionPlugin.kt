/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.internal.tasks.CompileArtProfileTask
import com.android.build.gradle.internal.tasks.ExpandArtProfileWildcardsTask
import com.android.build.gradle.internal.tasks.MergeArtProfileTask
import com.android.build.gradle.tasks.PackageApplication
import com.mikepenz.aboutlibraries.plugin.AboutLibrariesExtension
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.internal.provider.AbstractProperty
import org.gradle.api.internal.provider.Providers
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
private const val TOOLS_NS = "http://schemas.android.com/tools"
private const val INTERNET_PERMISSION = "android.permission.INTERNET"

private fun readManifestPermissions(manifest: File): Set<String> {
    val document = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(manifest)
    val permissions = mutableSetOf<String>()
    for (tag in listOf("uses-permission", "uses-permission-sdk-23", "permission")) {
        val nodes = document.getElementsByTagName(tag)
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as? Element ?: continue
            if (element.getAttributeNS(TOOLS_NS, "node") == "remove") continue
            val name = element.getAttributeNS(ANDROID_NS, "name")
            if (name.isNotBlank()) permissions.add(name)
        }
    }
    return permissions
}

/**
 * The prototype of an Android Application
 *
 * - Configure dependency for [DataDescriptorPlugin] task (If have)
 * - Provide default configuration for `android {...}`
 * - Add desugar JDK libs
 */
@Suppress("unused")
class AndroidAppConventionPlugin : AndroidBaseConventionPlugin() {

    override fun apply(target: Project) {
        target.pluginManager.apply(target.libs.plugins.android.application.get().pluginId)

        super.apply(target)

        target.extensions.configure<ApplicationExtension> {
            defaultConfig {
                targetSdk = Versions.targetSdk
                versionCode = Versions.calculateVersionCode()
                versionName = target.buildVersionName
            }
            buildTypes {
                release {
                    isMinifyEnabled = true
                    isShrinkResources = true
                    signingConfig = signingConfigs.fromProjectEnv(target)
                    proguardFile(getDefaultProguardFile("proguard-android-optimize.txt"))
                }
                debug {
                    applicationIdSuffix = ".debug"
                }
                all {
                    // remove META-INF/version-control-info.textproto
                    @Suppress("UnstableApiUsage")
                    vcsInfo.include = false
                }
            }
            compileOptions {
                isCoreLibraryDesugaringEnabled = true
            }
        }

        target.extensions.configure<ApplicationExtension> {
            dependenciesInfo {
                includeInApk = false
                includeInBundle = false
            }
            packaging {
                resources {
                    excludes += setOf(
                        "/META-INF/*.version",
                        "/META-INF/*.kotlin_module",  // cannot be excluded actually
                        "/META-INF/androidx/**",
                        "/DebugProbesKt.bin",
                        "/kotlin-tooling-metadata.json"
                    )
                }
            }
        }

        // remove META-INF/com/android/build/gradle/app-metadata.properties
        target.tasks.withType<PackageApplication> {
            val valueField =
                AbstractProperty::class.java.declaredFields.find { it.name == "value" } ?: run {
                    println("class AbstractProperty field value not found, something could have gone wrong")
                    return@withType
                }
            valueField.isAccessible = true
            doFirst {
                valueField.set(appMetadata, Providers.notDefined<RegularFile>())
                allInputFilesWithNameOnlyPathSensitivity.removeAll { true }
            }
        }

        // try to remove <pkg_name>-<version_name>.kotlin_module, but it does not work ¯\_(ツ)_/¯
        target.tasks.withType<KotlinCompile> {
            doLast f@{
                val ktClass = outputs.files.files.filter { it.path.contains("kotlin-classes") }
                if (ktClass.isEmpty()) return@f
                val metaInf = ktClass.first().resolve("META-INF")
                if (!metaInf.exists() || !metaInf.isDirectory) return@f
                metaInf.listFiles()?.forEach {
                    if (it.name.endsWith(".kotlin_module")) {
                        it.delete()
                    }
                }
            }
        }

        // remove assets/dexopt/baseline.prof{,m} (baseline profile)
        target.tasks.withType<MergeArtProfileTask> { enabled = false }
        target.tasks.withType<ExpandArtProfileWildcardsTask> { enabled = false }
        target.tasks.withType<CompileArtProfileTask> { enabled = false }

        target.extensions.configure<ApplicationAndroidComponentsExtension> {
            // Add dependency relationships for data descriptor task
            onVariants { v ->
                val variantName = v.name.capitalized()
                // Evaluation should be delayed as we need be able to see other tasks
                target.afterEvaluate {
                    tasks.findByName(DataDescriptorPlugin.TASK)?.also {
                        tasks.findByName("merge${variantName}Assets")?.dependsOn(it)
                        tasks.findByName("lintVitalAnalyzeRelease")?.dependsOn(it)
                        tasks.findByName("generateReleaseLintVitalReportModel")?.dependsOn(it)
                    }
                }
            }
            // Make data descriptor depend on fcitx component if have
            // Since we are using finalizeDsl, there is no need to do afterEvaluate
            finalizeDsl {
                target.tasks.findByName(DataDescriptorPlugin.TASK)?.also { dataDescriptorTask ->
                    FcitxComponentPlugin.DEPENDENT_TASKS
                        .mapNotNull { taskName -> target.tasks.findByName(taskName) }
                        .forEach { componentTask -> dataDescriptorTask.dependsOn(componentTask) }
                }
                // applicationId is not set upon apply
                it.defaultConfig {
                    // https://www.norio.be/blog/archivesBaseName-removed-from-gradle9.html
                    target.extensions.configure<BasePluginExtension> {
                        archivesName.set("$applicationId-$versionName")
                    }
                }
            }
        }

        if (target.path == ":app") {
            val verifyAppPermissions = target.tasks.register("verifyAppPermissions") {
                group = "verification"
                description = "Verify merged app manifests contain only allowed permissions and never INTERNET"
            }
            val verifyInternetScope = target.tasks.register("verifyManifestInternetScope") {
                group = "verification"
                description = "Verify INTERNET is only declared by plugin modules"
                val manifests = target.rootProject.fileTree(target.rootProject.projectDir) {
                    include("**/src/**/AndroidManifest.xml")
                }
                doLast {
                    val rootDir = target.rootProject.projectDir
                    manifests.files.sortedBy { it.path }.forEach { manifest ->
                        val relative = manifest.relativeTo(rootDir).invariantSeparatorsPath
                        if (!relative.startsWith("app/") && !relative.startsWith("lib/")) {
                            return@forEach
                        }
                        if (INTERNET_PERMISSION in readManifestPermissions(manifest)) {
                            throw GradleException(
                                "$relative: $INTERNET_PERMISSION is only allowed in plugin modules"
                            )
                        }
                    }
                }
            }
            verifyAppPermissions.configure {
                dependsOn(verifyInternetScope)
            }
            target.tasks.configureEach {
                if (name == "check") dependsOn(verifyAppPermissions)
            }

            target.extensions.configure<ApplicationAndroidComponentsExtension> {
                onVariants { variant ->
                    val mergedManifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
                    val verifyVariant = target.tasks.register(
                        "verify${variant.name.capitalized()}AppPermissions"
                    ) {
                        group = "verification"
                        description = "Verify merged ${variant.name} manifest permissions"
                        inputs.file(mergedManifest)
                        doLast {
                            val manifestFile = mergedManifest.get().asFile
                            val permissions = readManifestPermissions(manifestFile)
                            if (INTERNET_PERMISSION in permissions) {
                                throw GradleException(
                                    "${manifestFile.path}: $INTERNET_PERMISSION is forbidden in the main app"
                                )
                            }
                            val applicationId = variant.applicationId.get()
                            val allowed = setOf(
                                "android.permission.VIBRATE",
                                "android.permission.POST_NOTIFICATIONS",
                                "$applicationId.permission.PLUGIN",
                                "$applicationId.permission.IPC"
                            )
                            val unexpected = permissions - allowed
                            if (unexpected.isNotEmpty()) {
                                throw GradleException(
                                    "${manifestFile.path}: unexpected permissions " +
                                        "${unexpected.sorted().joinToString()} " +
                                        "(allowed: ${allowed.sorted().joinToString()})"
                                )
                            }
                        }
                    }
                    verifyAppPermissions.configure {
                        dependsOn(verifyVariant)
                    }
                    target.tasks.configureEach {
                        if (name == "assemble${variant.name.capitalized()}") {
                            dependsOn(verifyVariant)
                        }
                    }
                }
            }
        }

        target.pluginManager.apply(target.libs.plugins.aboutlibraries.get().pluginId)

        target.configure<AboutLibrariesExtension> {
            collect {
                configPath.set(target.file("licenses").takeIf { it.exists() })
                fetchRemoteLicense.set(false)
                fetchRemoteFunding.set(false)
                includePlatform.set(false)
            }
            export {
                excludeFields.set(
                    setOf("generated", "developers", "organization", "scm", "funding", "content")
                )
            }
        }

        target.dependencies.add("coreLibraryDesugaring", target.libs.android.desugarJDKLibs)
    }

}
