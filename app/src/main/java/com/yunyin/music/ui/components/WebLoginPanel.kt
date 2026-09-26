package com.yunyin.music.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle
import kotlinx.coroutines.delay

/**
 * In-app browser sign-in that captures the session cookie automatically.
 *
 * Loads the **desktop** NetEase site (`music.163.com`) with a desktop user agent, so the
 * full web login (QR code or phone) is available. The user signs in there as usual and we
 * read the resulting cookie for `music.163.com` straight out of [CookieManager] — no
 * pasting, and no reliance on the API server's own login endpoints (which are what get
 * blocked by risk control).
 *
 * Success is detected by polling rather than by page navigation: the site is a single-page
 * app whose hash routes do not reliably fire `onPageFinished` after login.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebLoginPanel(
    modifier: Modifier = Modifier,
    /** Invoked once a cookie containing `MUSIC_U` is observed. */
    onCookieCaptured: (String) -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }

    // Let the embedded page consume the back gesture while it has history, so navigating
    // the site does not close the sign-in sheet. When exhausted, the host's handler runs.
    androidx.activity.compose.BackHandler(enabled = webView?.canGoBack() == true) {
        webView?.goBack()
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                loadUrl("about:blank")
                (parent as? ViewGroup)?.removeView(this)
                destroy()
            }
            webView = null
        }
    }

    // CookieManager may not have flushed to disk/JS bridge synchronously; poll until the
    // login cookie shows up, then hand the whole cookie header to the caller.
    LaunchedEffect(webView) {
        val view = webView ?: return@LaunchedEffect
        while (true) {
            delay(1000)
            val cookie = CookieManager.getInstance().getCookie(WEB_ORIGIN).orEmpty()
            if (cookie.contains("MUSIC_U=")) {
                onCookieCaptured(cookie)
                return@LaunchedEffect
            }
            if (!view.isShown) return@LaunchedEffect
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(ContinuousRoundedRectangle(AppleShapes.card))
            .background(AppTheme.palette.background),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                val view = WebView(context)
                view.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                view.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    // Desktop layout: the full site is what offers every login option.
                    userAgentString = DESKTOP_UA
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    builtInZoomControls = true
                    displayZoomControls = false
                    setSupportZoom(true)
                    cacheMode = WebSettings.LOAD_DEFAULT
                }
                // Third-party cookies are required for the login flow to persist a session.
                CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
                CookieManager.getInstance().setAcceptCookie(true)
                view.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                        loading = true
                    }

                    override fun onPageFinished(v: WebView?, url: String?) {
                        loading = false
                    }
                }
                view.webChromeClient = WebChromeClient()
                view.loadUrl(LOGIN_URL)
                webView = view
                view
            },
        )

        if (loading) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(AppTheme.palette.accent.copy(alpha = 0.92f))
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "正在打开网易云音乐网页…",
                    fontFamily = SFPro,
                    fontSize = 12.sp,
                    color = androidx.compose.ui.graphics.Color.White,
                )
            }
        }
    }
}

/** Guidance shown above the embedded browser. */
@Composable
fun WebLoginHint(modifier: Modifier = Modifier) {
    Text(
        text = "在下方网页里登录（扫码或手机号均可），\n登录成功后会自动完成，无需手动复制 Cookie。",
        fontFamily = SFPro,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = AppTheme.palette.secondaryLabel,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().height(34.dp).padding(horizontal = 24.dp),
    )
}

private const val WEB_ORIGIN = "https://music.163.com"
private const val LOGIN_URL = "https://music.163.com/#/login"
private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
