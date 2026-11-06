package com.droiddeck.launcher.gpu

import com.droiddeck.launcher.gpu.BlobferryPackage.BlobferryException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ComponentReleasesTest {
    private val sha = "a".repeat(64)

    private fun asset(name: String, repo: String, digest: String? = null) = JSONObject().put("name", name).put("size", 9351167)
        .put("browser_download_url", "https://github.com/$repo/releases/download/t/$name").apply { if (digest != null) put("digest", digest) }

    private fun release(vararg assets: JSONObject) = JSONObject().put("tag_name", "t").put("assets", JSONArray(assets.toList()))

    @Test fun picksEachChannelsAsset() {
        val pv = ComponentReleases.parse(UpdateSources.PANVK, release(
            asset("PanVK-Kbase-glibc.zip", "zolotvok/panvk-kbase-android"),
            asset("panvk-kbase-v26.3.1-234-237.zip", "zolotvok/panvk-kbase-android", "sha256:$sha"),
        ))!!
        assertEquals("v26.3.1-234-237", pv.version)
        val env = ComponentReleases.parse(UpdateSources.ENV_DEFAULTS, release(
            asset("droiddeck.apk", "zolotvok/DroidDeck"), asset("env-defaults-2026.11.06.1.json", "zolotvok/DroidDeck"),
            asset("env-defaults-2026.11.06.1.json.sha256", "zolotvok/DroidDeck"),
        ))!!
        assertEquals("2026.11.06.1", env.version)
        assertEquals(sha, ComponentReleases.requiredSha256(UpdateSources.ENV_DEFAULTS, env) { "$sha  env-defaults-2026.11.06.1.json" })
    }

    @Test fun anotherReposUrlIsNotTrusted() {
        assertNull(ComponentReleases.parse(UpdateSources.PANVK, release(
            asset("panvk-kbase-v1.zip", "someone/else", "sha256:$sha"),
        )))
        val sneaky = ComponentReleases.parse(UpdateSources.PANVK, release(asset("panvk-kbase-v1.zip", "zolotvok/panvk-kbase-android", "sha256:$sha")))!!
        val moved = BlobferryReleases.Release(sneaky.tag, sneaky.version, sneaky.asset, "https://evil.example/panvk-kbase-v1.zip", 1, sha, null, 0)
        try {
            ComponentReleases.requiredSha256(UpdateSources.PANVK, moved) { null }
            fail()
        } catch (e: BlobferryException) {
            assertEquals("UNTRUSTED_SOURCE", e.code)
        }
    }

    @Test fun aDownloadNeedsASha256() {
        val bare = ComponentReleases.parse(UpdateSources.ENV_DEFAULTS, release(asset("env-defaults-2026.11.06.1.json", "zolotvok/DroidDeck")))!!
        try {
            ComponentReleases.requiredSha256(UpdateSources.ENV_DEFAULTS, bare) { null }
            fail()
        } catch (e: BlobferryException) {
            assertEquals("BAD_CHECKSUM", e.code)
        }
    }
}
