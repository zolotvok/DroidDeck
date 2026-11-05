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
class BlobferryReleasesTest {
    private val sha = "c".repeat(64)

    private fun asset(name: String, digest: String? = null) = JSONObject().put("name", name).put("size", 580913)
        .put("browser_download_url", "https://github.com/zolotvok/blobferry/releases/download/1.0.0/$name")
        .apply { if (digest != null) put("digest", digest) }

    private fun release(vararg assets: JSONObject, draft: Boolean = false) =
        JSONObject().put("tag_name", "1.0.0").put("draft", draft).put("assets", JSONArray(assets.toList()))

    @Test fun picksThePackageAndItsSidecar() {
        val r = BlobferryReleases.parse(release(
            asset("Mali_Bridge.zip"), asset("blobferry-1.0.0.zip", "sha256:$sha"), asset("blobferry-1.0.0.zip.sha256"),
            asset("blobferry-1.0.0.zip.md5"),
        ))!!
        assertEquals("1.0.0", r.version)
        assertEquals("blobferry-1.0.0.zip", r.asset)
        assertEquals(sha, r.digestSha256)
        assertEquals("https://github.com/zolotvok/blobferry/releases/download/1.0.0/blobferry-1.0.0.zip.sha256", r.sha256Url)
        assertEquals(580913L, r.size)
    }

    @Test fun noPackageOrDraftIsNothing() {
        assertNull(BlobferryReleases.parse(release(asset("Mali_Bridge.zip"), asset("blobferry-1.0.0.tar.gz"))))
        assertNull(BlobferryReleases.parse(release(asset("blobferry-1.0.0.zip"), draft = true)))
        assertNull(BlobferryReleases.parse(release(asset("blobferry-../x.zip"))))
    }

    @Test fun digestAndSidecarMustAgree() {
        val r = BlobferryReleases.parse(release(asset("blobferry-1.0.0.zip", "sha256:$sha"), asset("blobferry-1.0.0.zip.sha256")))!!
        assertEquals(sha, BlobferryReleases.expectedSha256(r) { "$sha  blobferry-1.0.0.zip\n" })
        try {
            BlobferryReleases.expectedSha256(r) { "${"d".repeat(64)}  blobferry-1.0.0.zip\n" }
            fail()
        } catch (e: BlobferryException) {
            assertEquals("BAD_CHECKSUM", e.code)
        }
        try {
            BlobferryReleases.expectedSha256(r) { "<html>not found</html>" }
            fail()
        } catch (e: BlobferryException) {
            assertEquals("BAD_CHECKSUM", e.code)
        }
    }

    @Test fun eitherSourceAloneIsEnoughAndNoneMeansMd5sumsOnly() {
        val digestOnly = BlobferryReleases.parse(release(asset("blobferry-1.0.0.zip", "sha256:$sha")))!!
        assertEquals(sha, BlobferryReleases.expectedSha256(digestOnly) { fail("no sidecar to fetch"); null })
        val sidecarOnly = BlobferryReleases.parse(release(asset("blobferry-1.0.0.zip"), asset("blobferry-1.0.0.zip.sha256")))!!
        assertEquals(sha, BlobferryReleases.expectedSha256(sidecarOnly) { sha })
        val none = BlobferryReleases.parse(release(asset("blobferry-1.0.0.zip")))!!
        assertNull(BlobferryReleases.expectedSha256(none) { null })
    }
}
