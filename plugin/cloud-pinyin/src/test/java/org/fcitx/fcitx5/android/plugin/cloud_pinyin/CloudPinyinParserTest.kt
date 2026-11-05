/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudPinyinParserTest {

    // Real sample from the pinned upstream test:
    // test/testcloudpinyinbackend.cpp, testGoogleResult()
    private val googleOk =
        """["SUCCESS",[["nihao",["你好"],[],{"annotation":["ni hao"],"candidate_type":[0],"lc":["16 16"]}]]]"""

    // Real sample from the pinned upstream test: testBaiduResult()
    private val baiduOk =
        """{"status":"T","errno":"0","errmsg":"","result":[[["结果",6,{"pinyin":"jie'guo","type":"IMEDICT"}]]]}"""

    private fun assertFailed(result: CloudPinyinParseResult) {
        assertTrue(result is CloudPinyinParseResult.Failure)
    }

    @Test
    fun testGoogleCnNormal() {
        assertEquals(
            CloudPinyinParseResult.Candidate("你好"),
            GoogleCnBackend().parse(googleOk)
        )
    }

    @Test
    fun testGoogleNormal() {
        assertEquals(
            CloudPinyinParseResult.Candidate("你好"),
            GoogleBackend().parse(googleOk)
        )
    }

    @Test
    fun testGoogleNonSuccessStatus() {
        assertFailed(GoogleBackend().parse("""["FAILED_TO_PARSE_REQUEST_BODY"]"""))
        assertFailed(GoogleBackend().parse("""["FAILED",[]]"""))
    }

    @Test
    fun testGoogleMissingFieldAndWrongType() {
        assertFailed(GoogleBackend().parse("""[]"""))
        assertFailed(GoogleBackend().parse("""["SUCCESS",[]]"""))
        assertFailed(GoogleBackend().parse("""["SUCCESS",[["nihao",[1]]]]"""))
        assertFailed(GoogleBackend().parse("""{"status":"SUCCESS"}"""))
    }

    @Test
    fun testGoogleOversize() {
        assertFailed(GoogleBackend().parse("[" + "\"x\"".repeat(5000) + "]"))
    }

    @Test
    fun testGoogleNestingTooDeep() {
        assertFailed(GoogleBackend().parse("[".repeat(20) + "1" + "]".repeat(20)))
    }

    @Test
    fun testGoogleEndpoints() {
        assertEquals(
            "https://www.google.cn/inputtools/request?ime=pinyin&text=ni%27hao",
            GoogleCnBackend().requestUrl("ni'hao")
        )
        assertEquals(
            "https://www.google.com/inputtools/request?ime=pinyin&text=ni%27hao",
            GoogleBackend().requestUrl("ni'hao")
        )
    }

    @Test
    fun testBaiduNormal() {
        assertEquals(CloudPinyinParseResult.Candidate("结果"), BaiduBackend().parse(baiduOk))
    }

    @Test
    fun testBaiduNonSuccessStatus() {
        assertFailed(
            BaiduBackend().parse("""{"status":"F","errno":"3","errmsg":"","result":[]}""")
        )
    }

    @Test
    fun testBaiduMissingFieldAndWrongType() {
        assertFailed(BaiduBackend().parse("""{"errno":"0"}"""))
        assertFailed(BaiduBackend().parse("""{"status":"T","errno":"0"}"""))
        assertFailed(BaiduBackend().parse("""{"status":"T","errno":"0","result":[[[1]]]}"""))
    }

    @Test
    fun testBaiduNestingTooDeep() {
        assertFailed(
            BaiduBackend().parse("{\"a\":" + "[".repeat(20) + "1" + "]".repeat(20) + "}")
        )
    }

    @Test
    fun testBaiduErrnAsNumber() {
        assertEquals(
            CloudPinyinParseResult.Candidate("结果"),
            BaiduBackend().parse("""{"status":"T","errno":0,"result":[[["结果"]]]}""")
        )
    }

    @Test
    fun testBaiduEndpoint() {
        assertEquals(
            "https://olimenew.baidu.com/py?input=ni%27hao&inputtype=py&resultcoding=utf-8",
            BaiduBackend().requestUrl("ni'hao")
        )
    }
}
