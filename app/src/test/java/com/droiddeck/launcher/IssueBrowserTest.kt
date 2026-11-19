package com.droiddeck.launcher

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class IssueBrowserTest {
    @Test fun theDefaultBrowserWinsAndTheGithubAppIsNeverPicked() {
        assertEquals("org.mozilla.firefox", IssueBrowser.pick("org.mozilla.firefox", listOf("com.android.chrome", "org.mozilla.firefox")))
        assertEquals("com.android.chrome", IssueBrowser.pick(IssueBrowser.GITHUB_APP, listOf(IssueBrowser.GITHUB_APP, "com.android.chrome")))
        assertEquals("com.android.chrome", IssueBrowser.pick("android", listOf("com.android.chrome", "org.mozilla.firefox")))
        assertNull(IssueBrowser.pick(null, listOf(IssueBrowser.GITHUB_APP)))
        assertNull(IssueBrowser.pick(null, emptyList()))
    }

    @Test fun theInAppPageOpensOnlyGithubAndStaysOnTheWeb() {
        assertTrue(IssueWebActivity.allowedStart("https://github.com/zolotvok/DroidDeck/issues/new?title=t&body=b"))
        assertFalse(IssueWebActivity.allowedStart("http://github.com/zolotvok/DroidDeck/issues/new"))
        assertFalse(IssueWebActivity.allowedStart("https://github.com.evil.example/issues/new"))
        assertFalse(IssueWebActivity.allowedStart("intent://github.com/#Intent;package=com.github.android;end"))
        assertTrue(IssueWebActivity.isWeb(Uri.parse("https://github.com/login")))
        assertFalse(IssueWebActivity.isWeb(Uri.parse("intent://x#Intent;end")))
        assertFalse(IssueWebActivity.isWeb(Uri.parse("market://details?id=com.github.android")))
        assertFalse(IssueWebActivity.isWeb(Uri.parse("github://issues")))
        assertFalse(IssueWebActivity.isWeb(Uri.parse("http://github.com/login")))
        assertTrue(IssueWebActivity.isFrameContent(Uri.parse("about:blank")))
        assertFalse(IssueWebActivity.isFrameContent(Uri.parse("intent://x#Intent;end")))
    }
}
