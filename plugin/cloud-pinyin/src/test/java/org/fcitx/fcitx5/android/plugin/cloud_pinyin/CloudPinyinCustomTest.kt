/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomCandidatesParserTest {

    private fun assertFailure(
        result: CloudPinyinParseResult,
        reason: CloudPinyinFailure
    ) {
        assertTrue(result is CloudPinyinParseResult.Failure)
        assertEquals(reason, (result as CloudPinyinParseResult.Failure).reason)
    }

    @Test
    fun testFirstNonEmptyStringWins() {
        assertEquals(
            CloudPinyinParseResult.Candidate("你好"),
            CustomCandidatesParser.parse("""{"candidates":["","你好","再见"]}""")
        )
        assertEquals(
            CloudPinyinParseResult.Candidate("好"),
            CustomCandidatesParser.parse("""{"candidates":[1,"","好"]}""")
        )
    }

    @Test
    fun testEmptyCandidatesFail() {
        assertFailure(
            CustomCandidatesParser.parse("""{"candidates":[]}"""),
            CloudPinyinFailure.EmptyCandidates
        )
        assertFailure(
            CustomCandidatesParser.parse("""{"candidates":["  ","\t"]}"""),
            CloudPinyinFailure.EmptyCandidates
        )
    }

    @Test
    fun testMalformedOrWrongShapeFails() {
        assertFailure(
            CustomCandidatesParser.parse("""{"candidates":"x"}"""),
            CloudPinyinFailure.Parse
        )
        assertFailure(
            CustomCandidatesParser.parse("""{}"""),
            CloudPinyinFailure.Parse
        )
        assertFailure(
            CustomCandidatesParser.parse("""["你好"]"""),
            CloudPinyinFailure.Parse
        )
        assertFailure(
            CustomCandidatesParser.parse("not json"),
            CloudPinyinFailure.Parse
        )
    }
}

class CustomEndpointUrlTest {

    @Test
    fun testHttpsUrlsOnly() {
        assertEquals(
            "https://example.com/cloud?key=1",
            CustomEndpointUrl.normalize(" https://example.com/cloud?key=1 ")
        )
        assertNull(CustomEndpointUrl.normalize("http://example.com/cloud"))
        assertNull(CustomEndpointUrl.normalize("https://example.com/cloud#frag"))
        assertNull(CustomEndpointUrl.normalize("ftp://example.com/cloud"))
        assertNull(CustomEndpointUrl.normalize("not a url"))
        assertNull(CustomEndpointUrl.normalize(""))
        assertNull(CustomEndpointUrl.normalize(null))
    }
}

class CustomEndpointBackendTest {

    @Test
    fun testQueryParameterAppending() {
        assertEquals(
            "https://example.com/cloud?q=ni%27hao",
            CustomEndpointBackend("https://example.com/cloud").requestUrl("ni'hao")
        )
        assertEquals(
            "https://example.com/cloud?key=1&q=ni%27hao",
            CustomEndpointBackend("https://example.com/cloud?key=1").requestUrl("ni'hao")
        )
        assertEquals(
            "https://example.com/cloud?q=ni%27hao",
            CustomEndpointBackend("https://example.com/cloud?").requestUrl("ni'hao")
        )
    }

    @Test
    fun testParseUsesContract() {
        assertEquals(
            CloudPinyinParseResult.Candidate("你好"),
            CustomEndpointBackend("https://example.com/cloud")
                .parse("""{"candidates":["你好"]}""")
        )
    }
}
