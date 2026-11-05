/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.common.plugin

import android.annotation.SuppressLint
import android.content.res.Resources
import org.fcitx.fcitx5.android.common.util.PathSafety
import org.xmlpull.v1.XmlPullParser

/**
 * Declared addons, native libraries, and data roots of a plugin.
 */
data class PluginProvides(
    val addons: List<String> = emptyList(),
    val libraries: List<String> = emptyList(),
    val dataRoots: List<String> = emptyList()
)

/**
 * IPC service declared by a plugin.
 *
 * [component] may be a fully qualified class name or a relative name starting with `.`.
 */
data class PluginService(
    val component: String,
    val action: String,
    val protocolVersion: Int
)

/**
 * Validated contents of `res/xml/plugin.xml`.
 */
data class PluginManifest(
    val apiVersion: String,
    val domain: String?,
    val description: String?,
    /**
     * Legacy boolean hint. New manifests should declare [service] instead.
     */
    val hasService: Boolean,
    val provides: PluginProvides?,
    val service: PluginService?
)

/**
 * Unvalidated values read from `res/xml/plugin.xml`.
 */
data class RawPluginManifest(
    val apiVersion: String? = null,
    val domain: String? = null,
    val description: String? = null,
    val hasService: Boolean = false,
    val provides: PluginProvides? = null,
    val service: PluginService? = null
)

enum class PluginManifestRejection(val technicalMessage: String) {
    MissingApiVersion("apiVersion is missing"),
    InvalidApiVersion("apiVersion is malformed"),
    InvalidDomain("domain contains characters outside [A-Za-z0-9._-]"),
    ReservedDomain("domain is reserved by the host"),
    InvalidService("service declaration is incomplete"),
    InvalidDataRoot("provides/dataRoot is not a safe relative path")
}

sealed interface PluginManifestResult {
    data class Parsed(val manifest: PluginManifest) : PluginManifestResult
    data object Missing : PluginManifestResult
    data class Invalid(val rejection: PluginManifestRejection) : PluginManifestResult
}

object PluginManifestValidator {

    private val apiVersionPattern = Regex("[0-9]+(\\.[0-9]+)*")
    private val domainPattern = Regex("[A-Za-z0-9._-]+")

    /**
     * gettext domains owned by the host; a plugin must not reuse them.
     */
    val reservedDomains: Set<String> = setOf("fcitx5", "fcitx5-android")

    fun validate(raw: RawPluginManifest): PluginManifestResult {
        val apiVersion = raw.apiVersion?.trim()
        if (apiVersion.isNullOrEmpty()) {
            return PluginManifestResult.Invalid(PluginManifestRejection.MissingApiVersion)
        }
        if (!apiVersionPattern.matches(apiVersion)) {
            return PluginManifestResult.Invalid(PluginManifestRejection.InvalidApiVersion)
        }

        val domain = raw.domain?.trim()?.takeIf { it.isNotEmpty() }
        if (domain != null) {
            if (!domainPattern.matches(domain)) {
                return PluginManifestResult.Invalid(PluginManifestRejection.InvalidDomain)
            }
            if (domain in reservedDomains) {
                return PluginManifestResult.Invalid(PluginManifestRejection.ReservedDomain)
            }
        }

        val service = raw.service
        if (service != null) {
            if (service.component.isBlank() || service.action.isBlank() ||
                service.protocolVersion < 0
            ) {
                return PluginManifestResult.Invalid(PluginManifestRejection.InvalidService)
            }
        }

        raw.provides?.dataRoots?.forEach {
            if (PathSafety.normalizeRelative(it) == null) {
                return PluginManifestResult.Invalid(PluginManifestRejection.InvalidDataRoot)
            }
        }

        return PluginManifestResult.Parsed(
            PluginManifest(
                apiVersion = apiVersion,
                domain = domain,
                description = raw.description,
                hasService = raw.hasService,
                provides = raw.provides,
                service = service
            )
        )
    }
}

object PluginManifestParser {

    private const val DESCRIPTOR_XML_NAME = "plugin"
    private val resourceNamePattern = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /**
     * Read `res/xml/plugin` from [packageName] and validate it.
     *
     * This is the only place that should parse the plugin descriptor.
     */
    @SuppressLint("DiscouragedApi")
    fun parse(resources: Resources, packageName: String): PluginManifestResult {
        val resId = resources.getIdentifier(DESCRIPTOR_XML_NAME, "xml", packageName)
        if (resId == 0) return PluginManifestResult.Missing

        val xml = resources.getXml(resId)
        val raw = try {
            read(xml, resources, packageName)
        } finally {
            xml.close()
        }
        return PluginManifestValidator.validate(raw)
    }

    @SuppressLint("DiscouragedApi")
    private fun read(
        parser: XmlPullParser,
        resources: Resources,
        packageName: String
    ): RawPluginManifest {
        var apiVersion: String? = null
        var domain: String? = null
        var description: String? = null
        var hasService = false
        var inProvides = false
        var inService = false
        var seesProvides = false
        var seesService = false
        var component: String? = null
        var action: String? = null
        var protocolVersion: Int? = null
        val addons = mutableListOf<String>()
        val libraries = mutableListOf<String>()
        val dataRoots = mutableListOf<String>()
        var text: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    text = null
                    when (parser.name) {
                        "provides" -> {
                            inProvides = true
                            seesProvides = true
                        }
                        "service" -> {
                            inService = true
                            seesService = true
                        }
                    }
                }
                XmlPullParser.TEXT -> if (!parser.isWhitespace) text = parser.text
                XmlPullParser.END_TAG -> {
                    val value = text?.trim()
                    when (parser.name) {
                        "apiVersion" -> apiVersion = value
                        "domain" -> domain = value
                        "description" -> description = value
                        "hasService" -> hasService = value.equals("true", ignoreCase = true)
                        "addon" -> if (inProvides && !value.isNullOrEmpty()) addons.add(value)
                        "library" -> if (inProvides && !value.isNullOrEmpty()) libraries.add(value)
                        "dataRoot" -> if (inProvides && !value.isNullOrEmpty()) dataRoots.add(value)
                        "component" -> if (inService) component = value
                        "action" -> if (inService) action = value
                        "protocolVersion" -> if (inService) {
                            protocolVersion = value?.toIntOrNull() ?: -1
                        }
                        "provides" -> inProvides = false
                        "service" -> inService = false
                    }
                    text = null
                }
            }
            event = parser.next()
        }

        return RawPluginManifest(
            apiVersion = apiVersion,
            domain = domain,
            description = resolveDescription(description, resources, packageName),
            hasService = hasService,
            provides = if (seesProvides) {
                PluginProvides(addons, libraries, dataRoots)
            } else null,
            service = if (seesService) {
                PluginService(
                    component = component.orEmpty(),
                    action = action.orEmpty(),
                    protocolVersion = protocolVersion ?: -1
                )
            } else null
        )
    }

    @SuppressLint("DiscouragedApi")
    private fun resolveDescription(
        value: String?,
        resources: Resources,
        packageName: String
    ): String? {
        if (value == null || !value.startsWith("@string/")) return value
        val name = value.removePrefix("@string/")
        if (!resourceNamePattern.matches(name)) return value
        val resId = resources.getIdentifier(name, "string", packageName)
        return if (resId != 0) resources.getString(resId) else value
    }
}
