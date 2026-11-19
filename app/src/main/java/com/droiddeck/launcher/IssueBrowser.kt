package com.droiddeck.launcher

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Opens the prefilled new-issue page in a browser. github.com is a verified App Link of the GitHub app, which drops
 * the /issues/new?title=&body= prefill; an intent with an explicit browser package skips App Link resolution.
 */
object IssueBrowser {
    const val GITHUB_APP = "com.github.android"

    // "android" is the system resolver: no default browser is set.
    private val NOT_BROWSERS = setOf(GITHUB_APP, "android")

    /** The browser to target: the default handler of a plain https link, else the first other handler; never GitHub's app. */
    fun pick(defaultPackage: String?, handlers: List<String>): String? {
        val browsers = handlers.filter { it !in NOT_BROWSERS }.distinct()
        return defaultPackage?.takeIf { it in browsers } ?: browsers.firstOrNull()
    }

    fun browserPackage(activity: Activity): String? {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/")).addCategory(Intent.CATEGORY_BROWSABLE)
        val pm = activity.packageManager
        val default = runCatching { pm.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }.getOrNull()
        val handlers = runCatching { pm.queryIntentActivities(probe, PackageManager.MATCH_ALL).map { it.activityInfo.packageName } }
            .getOrDefault(emptyList())
        return pick(default, handlers)
    }

    /** True when a browser took the link. */
    fun open(activity: Activity, url: String): Boolean {
        val browser = browserPackage(activity) ?: return false
        return runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(browser))
        }.isSuccess
    }
}
