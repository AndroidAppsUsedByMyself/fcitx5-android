/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

/**
 * Strict parser for the custom endpoint contract. Missing fields, wrong types, malformed
 * JSON and empty candidate lists are failures with distinct reasons.
 */
object CustomCandidatesParser {

    fun parse(body: String): CloudPinyinParseResult {
        val root = runCatching { JsonReader(body).read() }.getOrNull()
            ?: return CloudPinyinParseResult.Failure(CloudPinyinFailure.Parse)
        if (root !is JsonValue.Obj) {
            return CloudPinyinParseResult.Failure(CloudPinyinFailure.Parse, "root is not an object")
        }
        val candidates = root.fields["candidates"] as? JsonValue.Arr
            ?: return CloudPinyinParseResult.Failure(
                CloudPinyinFailure.Parse,
                "missing candidates array"
            )
        for (item in candidates.items) {
            if (item is JsonValue.Str && item.value.isNotBlank()) {
                return CloudPinyinParseResult.Candidate(item.value)
            }
        }
        return CloudPinyinParseResult.Failure(CloudPinyinFailure.EmptyCandidates)
    }
}
