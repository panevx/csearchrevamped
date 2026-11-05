package io.github.panevx.csearchrevamped

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** In-app browser sheet: uploads the selection to Google Lens on the web, or shows a URL. */
class ResultActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"

        fun lens(context: Context): Intent = Intent(context, ResultActivity::class.java)
            .putExtra(EXTRA_TITLE, context.getString(R.string.title_search))

        fun url(context: Context, url: String, title: String): Intent =
            Intent(context, ResultActivity::class.java).putExtra(EXTRA_URL, url).putExtra(EXTRA_TITLE, title)
    }

    private lateinit var web: WebView
    private lateinit var progress: View
    private var image: Bitmap? = null
    private var sawConsent = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)

        findViewById<View>(R.id.root).setOnClickListener { finish() }
        findViewById<View>(R.id.sheet).setOnClickListener { }
        findViewById<View>(R.id.closeSheet).setOnClickListener { finish() }
        findViewById<TextView>(R.id.title).text = intent.getStringExtra(EXTRA_TITLE)
        progress = findViewById(R.id.progress)
        web = findViewById(R.id.web)

        CookieManager.getInstance().setAcceptCookie(true)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Google serves a reduced page to user agents that advertise WebView.
            userAgentString = userAgentString.replace("; wv", "")
        }
        web.webViewClient = Client()
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.isVisible = newProgress < 100
            }
        }

        findViewById<View>(R.id.openExternal).setOnClickListener {
            val current = web.url
            if (current != null && current.startsWith("http")) openExternally(Uri.parse(current))
        }

        onBackPressedDispatcher.addCallback(this) {
            if (web.canGoBack()) web.goBack() else finish()
        }

        val url = intent.getStringExtra(EXTRA_URL)
        if (url != null) {
            web.loadUrl(url)
        } else {
            image = ScreenshotHolder.crop
            if (image == null) finish() else uploadToLens()
        }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    /**
     * Posts the image to Lens' web upload endpoint from inside the WebView, so the results page,
     * cookies and any consent screen all live in the same browser session.
     */
    private fun uploadToLens() {
        val src = image ?: return
        val scale = 1600f / max(src.width, src.height)
        val bmp = if (scale < 1f) {
            Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
        } else src
        val jpeg = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val lang = resources.configuration.locales[0].toLanguageTag()
        val html = """
            <!DOCTYPE html><html><head><meta name="viewport" content="width=device-width"></head>
            <body style="margin:0;height:100vh;display:flex;align-items:center;justify-content:center;font-family:sans-serif;color:#888">
            Uploading…
            <form id="f" method="POST" enctype="multipart/form-data"
              action="https://lens.google.com/v3/upload?ep=ccm&amp;s=&amp;st=${System.currentTimeMillis()}&amp;hl=$lang">
              <input type="file" name="encoded_image" id="i" style="display:none">
              <input type="hidden" name="processed_image_dimensions" value="${bmp.width},${bmp.height}">
            </form>
            <script>
              const bin = atob("$b64");
              const bytes = new Uint8Array(bin.length);
              for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
              const dt = new DataTransfer();
              dt.items.add(new File([bytes], "image.jpg", {type: "image/jpeg"}));
              document.getElementById("i").files = dt.files;
              document.getElementById("f").submit();
            </script></body></html>
        """.trimIndent()
        web.loadDataWithBaseURL("https://lens.google.com/", html, "text/html", "utf-8", null)
    }

    private fun openExternally(uri: Uri) {
        try {
            val intent = if (uri.scheme == "intent") {
                Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    component = null
                    selector = null
                }
            } else {
                Intent(Intent.ACTION_VIEW, uri)
            }
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
        } catch (_: java.net.URISyntaxException) {
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if (uri.scheme == "http" || uri.scheme == "https") return false
            openExternally(uri)
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            val host = Uri.parse(url).host.orEmpty()
            if (host.startsWith("consent.")) {
                sawConsent = true
            } else if (sawConsent && image != null) {
                // The consent screen swallowed the upload; redo it now that cookies are set.
                sawConsent = false
                view.stopLoading()
                uploadToLens()
            }
        }
    }
}
