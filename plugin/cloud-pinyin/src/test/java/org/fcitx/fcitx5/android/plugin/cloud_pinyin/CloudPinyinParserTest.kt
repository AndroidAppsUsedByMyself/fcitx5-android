/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloudPinyinParserTest {

    // Real sample from the pinned upstream test:
    // test/testcloudpinyinbackend.cpp, testGoogleResult()
    private val googleOk =
        """["SUCCESS",[["nihao",["你好"],[],{"annotation":["ni hao"],"candidate_type":[0],"lc":["16 16"]}]]]"""

    // Real sample from the pinned upstream test: testBaiduResult()
    private val baiduOk =
        """{"status":"T","errno":"0","errmsg":"","result":[[["结果",6,{"pinyin":"jie'guo","type":"IMEDICT"}]]]}"""

    @Test
    fun testGoogleNormal() {
        assertEquals("你好", CloudPinyinBackend.parseGoogle(googleOk))
    }

    @Test
    fun testGoogleNonSuccessStatus() {
        assertNull(CloudPinyinBackend.parseGoogle("""["FAILED_TO_PARSE_REQUEST_BODY"]"""))
        assertNull(CloudPinyinBackend.parseGoogle("""["FAILED",[]]"""))
    }

    @Test
    fun testGoogleMissingFieldAndWrongType() {
        assertNull(CloudPinyinBackend.parseGoogle("""[]"""))
        assertNull(CloudPinyinBackend.parseGoogle("""["SUCCESS",[]]"""))
        assertNull(CloudPinyinBackend.parseGoogle("""["SUCCESS",[["nihao",[1]]]]"""))
        assertNull(CloudPinyinBackend.parseGoogle("""{"status":"SUCCESS"}"""))
    }

    @Test
    fun testGoogleOversize() {
        assertNull(CloudPinyinBackend.parseGoogle("[" + "\"x\"".repeat(5000) + "]"))
    }

    @Test
    fun testGoogleNestingTooDeep() {
        assertNull(CloudPinyinBackend.parseGoogle("[".repeat(20) + "1" + "]".repeat(20)))
    }

    @Test
    fun testBaiduNormal() {
        assertEquals("结果", CloudPinyinBackend.parseBaidu(baiduOk))
    }

    @Test
    fun testBaiduNonSuccessStatus() {
        assertNull(
            CloudPinyinBackend.parseBaidu(
                """{"status":"F","errno":"3","errmsg":"","result":[]}"""
            )
        )
    }

    @Test
    fun testBaiduMissingFieldAndWrongType() {
        assertNull(CloudPinyinBackend.parseBaidu("""{"errno":"0"}"""))
        assertNull(CloudPinyinBackend.parseBaidu("""{"status":"T","errno":"0"}"""))
        assertNull(
            CloudPinyinBackend.parseBaidu(
                """{"status":"T","errno":"0","result":[[[1]]]}"""
            )
        )
    }

    @Test
    fun testBaiduNestingTooDeep() {
        assertNull(CloudPinyinBackend.parseBaidu("{\"a\":" + "[".repeat(20) + "1" + "]".repeat(20) + "}"))
    }

    @Test
    fun testBaiduErrnAsNumber() {
        assertEquals(
            "结果",
            CloudPinyinBackend.parseBaidu(
                """{"status":"T","errno":0,"result":[[["结果"]]]}"""
            )
        )
    }
}
