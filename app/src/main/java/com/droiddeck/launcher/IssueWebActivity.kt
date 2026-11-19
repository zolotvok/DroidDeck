package com.droiddeck.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

/**
 * The new-issue page inside the app, for phones where the browser still hands github.com to the GitHub app. A WebView
 * never passes a link to another app; anything but https (intent://, market://, app schemes) is refused here. GitHub's
 * sign-in cookies stay in the app's WebView data. "Attach files"
 * opens the system file picker at Downloads/DroidDeck, where the report zip was saved.
 */
class IssueWebActivity : Activity() {
    private lateinit var web: WebView
    private var pending: ValueCallback<Array<Uri>>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)?.takeIf { allowedStart(it) } ?: run { finish(); return }
        title = getString(R.string.report_issue_web_title)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                if (request.isForMainFrame) !isWeb(request.url) else !isFrameContent(request.url)
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                pending?.onReceiveValue(null)
                pending = callback
                val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                    .putExtra(DocumentsContract.EXTRA_INITIAL_URI, REPORT_FOLDER)
                // true in both cases: the callback is answered here (WebView would answer it again after a false).
                runCatching { startActivityForResult(pick, PICK) }.onFailure {
                    pending = null
                    callback.onReceiveValue(null)
                    Toast.makeText(this@IssueWebActivity, R.string.report_issue_no_picker, Toast.LENGTH_LONG).show()
                }
                return true
            }
        }
        setContentView(web)
        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) web.loadUrl(url)
    }

    @Deprecated("Activity result of the file picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != PICK) return super.onActivityResult(requestCode, resultCode, data)
        val callback = pending ?: return
        pending = null
        val uris = if (resultCode != RESULT_OK || data == null) null else
            (data.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } } ?: listOfNotNull(data.data))
                .takeIf { it.isNotEmpty() }?.toTypedArray()
        callback.onReceiveValue(uris)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::web.isInitialized) web.saveState(outState)
    }

    @Deprecated("Back goes back in the page first")
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        pending?.onReceiveValue(null)
        pending = null
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val PICK = 1
        private val REPORT_FOLDER: Uri =
            DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/DroidDeck")

        internal fun isWeb(uri: Uri): Boolean = uri.scheme == "https"

        /** What a page's own frames may load: web content, never an app scheme (intent://, market://, github://). */
        internal fun isFrameContent(uri: Uri): Boolean = uri.scheme in setOf("https", "about", "blob", "data")

        /** Only GitHub's own pages are opened from outside (the report screen). */
        internal fun allowedStart(url: String): Boolean =
            Uri.parse(url).let { it.scheme == "https" && it.host == "github.com" }

        fun intent(context: Context, url: String): Intent = Intent(context, IssueWebActivity::class.java).putExtra(EXTRA_URL, url)
    }
}
