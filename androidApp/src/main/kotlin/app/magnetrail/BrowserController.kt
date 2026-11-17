package app.magnetrail

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.net.URLEncoder

/** 网页里检测到的一个可播放视频。 */
data class MediaCandidate(
    val url: String,
    val label: String,
    val kind: String
)

/**
 * 「浏览」内置浏览器的全部状态与 WebView 逻辑（UI 在 ui/BrowserTab.kt）。
 *
 * 关键能力：
 *  - 广告拦截：请求级（shouldInterceptRequest）+ 跳转级（shouldOverrideUrlLoading）+ JS 隐藏广告位；
 *  - 视频检测：拦截网络请求里的 mp4/m3u8/mpd + JS 扫 video/source/og:video；
 *  - 交给本机播放器：带上 Cookie / Referer，交给 PlayerActivity（支持画中画、横屏）；
 *  - magnet 链接直接丢给下载器。
 *
 * 这个对象由 MainActivity 持有，切 tab / 旋转都不会丢网页状态。
 */
class BrowserController(
    private val context: Context,
    private val settings: AppSettings,
    private val store: BrowserStore
) {

    /** 交给下载器 / 弹提示的回调，由 Compose 层绑定。 */
    var onMagnet: (String) -> Unit = {}
    var notify: (String) -> Unit = {}

    var webView: WebView? = null
        private set

    /** WebView 渲染进程崩溃后换一个实例（AndroidView 用这个当 key）。 */
    var generation by mutableIntStateOf(0)
        private set

    var currentUrl by mutableStateOf("")
        private set
    var pageTitle by mutableStateOf("")
        private set
    var loading by mutableStateOf(false)
        private set
    var progress by mutableIntStateOf(0)
        private set
    var canGoBack by mutableStateOf(false)
        private set
    var canGoForward by mutableStateOf(false)
        private set
    var blockedCount by mutableIntStateOf(0)
        private set
    var adBlockEnabled by mutableStateOf(settings.adBlockEnabled)
        private set
    var desktopMode by mutableStateOf(settings.desktopMode)
        private set
    var incognito by mutableStateOf(store.incognito)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    /** 长按链接时待选择的 URL。 */
    var pendingLink by mutableStateOf<String?>(null)

    /** 上次访问的网址（空白首页「继续上次」用）。 */
    val lastUrl: String get() = settings.lastUrl

    val mediaUrls = mutableStateListOf<MediaCandidate>()

    private var defaultUserAgent: String? = null
    private val cookieManager = CookieManager.getInstance()

    /** WebView 还没创建时先记住要打开的网址。 */
    private var pendingLoad: String? = null

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    fun obtainWebView(): WebView {
        webView?.let { existing ->
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }
        val wv = WebView(context)
        val defaultUa = wv.settings.userAgentString
        defaultUserAgent = defaultUa

        // WebView 的背景色在部分 ROM 上会被 Chromium 铺满整个窗口（盖住 tab 栏/地址栏），
        // 这里必须用透明色；网页自己的白色背景由页面在自身 viewport 内绘制。
        wv.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true

        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            // 很多站点是 https 页面里套 http 播放源，放宽混用内容
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
            // 不保存表单/密码：这是给临时浏览用的，别往磁盘写凭据
            saveFormData = false
            savePassword = false
            setGeolocationEnabled(false)
            userAgentString = if (desktopMode) DESKTOP_UA else defaultUa
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                if (!request.isForMainFrame) return false
                return handleNavigation(request.url.toString())
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val requestUrl = request.url?.toString() ?: return null
                if (adBlockEnabled && AdBlocker.isBlocked(requestUrl)) {
                    view.post { blockedCount++ }
                    return blockedResponse()
                }
                if (AdBlocker.isPlayableMediaUrl(requestUrl)) {
                    view.post { registerMedia(requestUrl) }
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                currentUrl = if (url == "about:blank") "" else url.orEmpty()
                loading = true
                progress = 0
                errorMessage = null
                view.post {
                    canGoBack = view.canGoBack()
                    canGoForward = view.canGoForward()
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                loading = false
                progress = 100
                val finishedUrl = if (url == "about:blank") "" else (url ?: currentUrl)
                currentUrl = finishedUrl
                canGoBack = view.canGoBack()
                canGoForward = view.canGoForward()
                if (finishedUrl.startsWith("http")) {
                    if (!incognito) {
                        settings.lastUrl = finishedUrl
                        store.addHistory(finishedUrl, view.title.orEmpty())
                    }
                }
                injectAdHiding(view)
                // 页面里的 <video> 往往晚一点才挂上，延后再扫一次
                view.postDelayed({ scanMedia(silent = true) }, 800)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    loading = false
                    errorMessage = "网页打不开：${error.description}"
                }
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail
            ): Boolean {
                // 渲染进程崩了（内存不足/恶意页面），换一个新的 WebView
                runCatching { (view.parent as? ViewGroup)?.removeView(view) }
                runCatching { view.destroy() }
                if (webView === view) webView = null
                generation++
                notify("浏览器渲染进程崩溃，已自动重置")
                return true
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress = newProgress
                if (newProgress < 100) loading = true
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                pageTitle = title.orEmpty()
            }

            // 弹窗一律不开（广告弹窗的主要来源）
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message
            ): Boolean = false
        }

        // 长按链接：给一个「打开 / 复制 / 播放」菜单，而不是误触发文字选择
        wv.setOnLongClickListener {
            val hit = wv.hitTestResult
            val extra = hit.extra
            if ((hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                    hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) &&
                !extra.isNullOrBlank()
            ) {
                pendingLink = extra
                true
            } else {
                false
            }
        }

        wv.setDownloadListener(DownloadListener { url, _, _, _, _ ->
            if (AdBlocker.isPlayableMediaUrl(url)) {
                registerMedia(url)
                notify("检测到视频下载链接，可在「本页视频」里交给播放器")
            } else {
                notify("该链接不是可播放的视频，已忽略")
            }
        })

        webView = wv
        pendingLoad?.let { url ->
            pendingLoad = null
            wv.loadUrl(url)
        }
        return wv
    }

    private fun handleNavigation(url: String): Boolean {
        val scheme = runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull()
        return when {
            scheme == "http" || scheme == "https" -> {
                if (adBlockEnabled && AdBlocker.isBlocked(url)) {
                    blockedCount++
                    notify("已拦截广告跳转：${hostOf(url)}")
                    true
                } else {
                    mediaUrls.clear()
                    false
                }
            }
            scheme == "magnet" -> {
                // 网页里的 href 可能是百分号编码的 magnet%3A%3Fxt%3D…
                onMagnet(runCatching { Uri.decode(url) }.getOrDefault(url))
                true
            }
            scheme == "tel" || scheme == "mailto" || scheme == "sms" -> {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                true
            }
            scheme == "intent" || scheme == "javascript" || scheme == "about" -> true
            else -> {
                // 其它自定义 scheme 基本都是「唤醒 App」式广告
                blockedCount++
                notify("已阻止跳转到外部 App：${scheme ?: "未知"}")
                true
            }
        }
    }

    // ---------------------------------------------------------------- actions

    /** 输入框内容 → URL（看起来像域名就补 https://，否则当搜索词）。 */
    fun load(input: String) {
        val raw = input.trim()
        if (raw.isEmpty()) return
        if (raw.startsWith("magnet:", ignoreCase = true)) {
            onMagnet(raw)
            return
        }
        if (raw.startsWith("blob:") || raw.startsWith("data:")) {
            notify("这个地址不能直接打开，请用网页里的视频检测")
            return
        }
        val url = normalize(raw)
        mediaUrls.clear()
        errorMessage = null
        currentUrl = url
        if (!incognito) settings.lastUrl = url
        val wv = webView
        if (wv != null) wv.loadUrl(url) else pendingLoad = url
    }

    private fun normalize(raw: String): String {
        val lower = raw.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return raw
        if (lower.startsWith("file:") || lower.startsWith("content:")) {
            return "https://www.bing.com/search?q=" + encode(raw)
        }
        val domainOnly = Regex(
            "^([a-z0-9-]+\\.)+[a-z0-9-]+(:\\d+)?(/.*)?$",
            RegexOption.IGNORE_CASE
        )
        if (domainOnly.matches(raw)) return "https://$raw"
        return "https://www.bing.com/search?q=" + encode(raw)
    }

    private fun encode(value: String): String =
        runCatching { URLEncoder.encode(value, "UTF-8") }.getOrDefault(value)

    fun goBack() {
        webView?.let { if (it.canGoBack()) it.goBack() }
    }

    fun goForward() {
        webView?.let { if (it.canGoForward()) it.goForward() }
    }

    fun reloadOrStop() {
        val wv = webView ?: return
        if (loading) wv.stopLoading() else wv.reload()
    }

    fun goHome() {
        mediaUrls.clear()
        errorMessage = null
        currentUrl = ""
        pageTitle = ""
        canGoBack = false
        canGoForward = false
        webView?.let { wv ->
            wv.stopLoading()
            wv.loadUrl("about:blank")
        }
    }

    fun toggleAdBlock(): Boolean {
        adBlockEnabled = !adBlockEnabled
        settings.adBlockEnabled = adBlockEnabled
        webView?.reload()
        notify(if (adBlockEnabled) "广告拦截已开启" else "广告拦截已关闭（刷新后生效）")
        return adBlockEnabled
    }

    fun toggleDesktopMode(): Boolean {
        desktopMode = !desktopMode
        settings.desktopMode = desktopMode
        applyUserAgent()
        webView?.reload()
        notify(if (desktopMode) "已切换桌面版网页" else "已切回手机版网页")
        return desktopMode
    }

    private fun applyUserAgent() {
        val ua = if (desktopMode) DESKTOP_UA else defaultUserAgent ?: return
        runCatching { webView?.settings?.userAgentString = ua }
    }

    fun clearCache() {
        val wv = webView ?: return
        wv.clearCache(true)
        wv.clearHistory()
        canGoBack = false
        canGoForward = false
        notify("已清理网页缓存")
    }

    /** 清缓存 + 清 Cookie（无痕切换、隐私清理用）。 */
    fun clearBrowsingData() {
        runCatching { webView?.clearCache(true) }
        runCatching { webView?.clearHistory() }
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
        canGoBack = false
        canGoForward = false
    }

    /** 无痕开关：进出无痕都清一次 Cookie/缓存，避免串用登录态。 */
    fun applyIncognito(enabled: Boolean) {
        incognito = enabled
        store.incognito = enabled
        clearBrowsingData()
        notify(
            if (enabled) "无痕模式已开启：不记历史，已清 Cookie/缓存"
            else "已退出无痕模式"
        )
    }

    // ------------------------------------------------------------ 历史 / 收藏

    fun historyEntries(): List<BrowserStore.Entry> = store.history()

    fun bookmarkEntries(): List<BrowserStore.Entry> = store.bookmarks()

    fun isCurrentBookmarked(): Boolean = store.isBookmarked(currentUrl)

    /** 收藏/取消收藏当前页，返回切换后的状态。 */
    fun toggleBookmarkCurrent(): Boolean {
        val url = currentUrl
        if (url.isBlank()) {
            notify("先打开一个网页")
            return false
        }
        val title = webView?.title?.takeIf { it.isNotBlank() } ?: pageTitle
        val added = store.toggleBookmark(url, title)
        notify(if (added) "已收藏：${title.ifBlank { url }}" else "已取消收藏")
        return added
    }

    fun removeHistory(url: String) = store.removeHistory(url)

    fun clearHistory() {
        store.clearHistory()
        notify("已清空浏览历史")
    }

    fun removeBookmark(url: String) = store.removeBookmark(url)

    fun shareText(text: String) {
        runCatching {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                    "分享"
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun openExternal(url: String) {
        // 只允许 http/https 交给系统；intent://、file:// 等自定义 scheme 一律拒绝
        val scheme = runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull()
        if (scheme != "http" && scheme != "https") {
            notify("已拒绝打开非网页链接：${scheme ?: "未知"}")
            return
        }
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun copyText(text: String) {
        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("Magnetrail", text))
        notify("已复制到剪贴板")
    }

    fun pauseWebView() {
        runCatching { webView?.onPause() }
    }

    fun resumeWebView() {
        runCatching { webView?.onResume() }
    }

    /** 切到别的 tab 时把网页里的 <video> 停掉（网页继续保留，回来还能接着用）。 */
    fun pauseAllMedia() {
        runCatching {
            webView?.evaluateJavascript(
                "(function(){var vs=document.querySelectorAll('video');" +
                    "for(var i=0;i<vs.length;i++){try{vs[i].pause()}catch(e){}}}())",
                null
            )
        }
    }

    fun release() {
        if (incognito) runCatching { clearBrowsingData() }
        webView?.let { wv ->
            runCatching { wv.stopLoading() }
            (wv.parent as? ViewGroup)?.removeView(wv)
            runCatching { wv.destroy() }
        }
        webView = null
    }

    // ---------------------------------------------------------------- media

    /** 扫描当前页面里的视频（静态 <video> + 已发生的媒体请求 + og:video）。 */
    fun scanMedia(silent: Boolean = false) {
        val wv = webView ?: return
        wv.evaluateJavascript(SCAN_JS) { value ->
            val found = runCatching {
                val arr = JSONArray(value ?: "[]")
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { u -> u.isNotBlank() } }
            }.getOrDefault(emptyList())
            found.forEach { registerMedia(it) }
            if (!silent) {
                notify(
                    if (mediaUrls.isEmpty()) "没找到视频：先点开网页里的播放按钮，再点「本页视频」"
                    else "找到 ${mediaUrls.size} 个视频，点「本页视频」播放"
                )
            }
        }
    }

    private fun registerMedia(url: String) {
        if (!AdBlocker.isPlayableMediaUrl(url)) return
        if (mediaUrls.any { it.url == url }) return
        if (mediaUrls.size >= 40) {
            mediaUrls.removeAll(mediaUrls.take(mediaUrls.size - 39).toList())
        }
        mediaUrls.add(MediaCandidate(url = url, label = labelFor(url), kind = AdBlocker.mediaKind(url)))
        android.util.Log.i(
            "MagnetrailBrowser",
            "media detected: " + if (url.contains('?')) url.substringBefore('?') + "?<redacted>" else url
        )
    }

    fun removeMedia(url: String) {
        mediaUrls.removeAll { it.url == url }
    }

    /** 给播放器的请求头：很多站点没 Referer/Cookie 就直接 403。 */
    fun headersFor(url: String): Array<String> {
        val out = ArrayList<String>(3)
        val referer = currentUrl.ifBlank { url }
        if (referer.startsWith("http")) out.add("Referer: $referer")
        val cookie = runCatching { cookieManager.getCookie(url) }.getOrNull()
            ?: runCatching { cookieManager.getCookie(referer) }.getOrNull()
        if (!cookie.isNullOrBlank()) out.add("Cookie: $cookie")
        val ua = runCatching { webView?.settings?.userAgentString }.getOrNull()
        if (!ua.isNullOrBlank()) out.add("User-Agent: $ua")
        return out.toTypedArray()
    }

    private fun labelFor(url: String): String {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val last = uri?.pathSegments?.lastOrNull()?.takeIf { it.isNotBlank() }
            ?: return url.substringAfterLast('/').take(40).ifBlank { "视频" }
        val decoded = runCatching { Uri.decode(last) }.getOrDefault(last)
        return if (decoded.length > 48) decoded.take(44) + "…" else decoded
    }

    private fun hostOf(url: String): String =
        runCatching { Uri.parse(url).host }.getOrNull() ?: url.take(32)

    private fun injectAdHiding(view: WebView) {
        if (!adBlockEnabled) return
        view.evaluateJavascript(AD_HIDE_JS, null)
    }

    private companion object {
        /** 空响应 = 资源被拦掉，页面拿不到广告内容。每次返回新实例（流只能读一次）。 */
        fun blockedResponse(): WebResourceResponse = WebResourceResponse(
            "text/plain",
            "utf-8",
            ByteArrayInputStream(ByteArray(0))
        )

        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        const val AD_HIDE_JS = """
            (function(){
              if (document.getElementById('__mb_ad_style')) return;
              var s = document.createElement('style');
              s.id = '__mb_ad_style';
              s.textContent = 'ins.adsbygoogle,' +
                'iframe[src*="doubleclick"],iframe[src*="googlesyndication"],' +
                'iframe[src*="exoclick"],iframe[src*="adsterra"],iframe[src*="tsyndicate"],' +
                'iframe[src*="juicyads"],iframe[src*="trafficjunky"],' +
                'iframe[src*="popads"],iframe[src*="propeller"],' +
                '.adsbygoogle,[id^="div-gpt-ad"],[class^="ad-slot"],[id^="ad-slot"]' +
                '{display:none!important;height:0!important;}';
              (document.head || document.documentElement).appendChild(s);
              try { window.open = function(){ return null; }; } catch(e) {}
            })();
        """

        /** 扫视频：<video>/<source> + performance entries + og:video。 */
        const val SCAN_JS = """
            (function(){
              var out = [];
              function add(u){
                try {
                  if (!u) return;
                  if (typeof u !== 'string') return;
                  if (u.indexOf('blob:') === 0 || u.indexOf('data:') === 0) return;
                  out.push(u);
                } catch(e) {}
              }
              var vs = document.querySelectorAll('video');
              for (var i = 0; i < vs.length; i++) {
                var v = vs[i];
                add(v.currentSrc); add(v.src);
                var ss = v.querySelectorAll('source');
                for (var j = 0; j < ss.length; j++) add(ss[j].src);
              }
              try {
                var es = performance.getEntriesByType('resource');
                for (var k = 0; k < es.length; k++) {
                  if (/\.(m3u8|mp4|webm|flv|mpd|mov|mkv|m4v)(\?|#|$)/i.test(es[k].name)) add(es[k].name);
                }
              } catch(e) {}
              try {
                var ms = document.querySelectorAll('meta[property="og:video"],meta[property="og:video:url"],meta[property="og:video:secure_url"]');
                for (var m = 0; m < ms.length; m++) add(ms[m].content);
              } catch(e) {}
              var uniq = [];
              for (var q = 0; q < out.length; q++) if (uniq.indexOf(out[q]) < 0) uniq.push(out[q]);
              return JSON.stringify(uniq.slice(0, 30));
            })();
        """
    }
}
