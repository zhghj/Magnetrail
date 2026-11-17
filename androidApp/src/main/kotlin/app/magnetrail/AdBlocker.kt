package app.magnetrail

import android.net.Uri

/**
 * 轻量广告 / 追踪拦截。
 *
 * 两条规则：
 *  1. 命中广告联盟域名黑名单 → 直接拦掉请求；
 *  2. 路径里带常见广告目录 / 广告脚本名 → 拦掉。
 *
 * 宁可漏拦也不误拦：媒体文件（mp4/m3u8/图片等）永远先放行，
 * 否则带 "ads" 字样的视频 CDN 会被一起干掉。
 */
object AdBlocker {

    /** 广告联盟 / 追踪 / 弹窗中转域名（按后缀匹配，子域名同样命中）。 */
    private val HOST_SUFFIXES = listOf(
        // Google 广告与统计
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "googletagmanager.com",
        "google-analytics.com",
        // 国际广告交易平台
        "adnxs.com",
        "rubiconproject.com",
        "pubmatic.com",
        "openx.net",
        "criteo.com",
        "smartadserver.com",
        "taboola.com",
        "outbrain.com",
        "mgid.com",
        "revcontent.com",
        "zergnet.com",
        "adroll.com",
        "adcash.com",
        "clickadu.com",
        // 成人站常见广告联盟
        "exoclick.com",
        "exdynsrv.com",
        "exosrv.com",
        "tsyndicate.com",
        "trafficjunky.net",
        "trafficjunky.com",
        "juicyads.com",
        "adsterra.com",
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "propellerpops.com",
        "hilltopads.net",
        "trafficfactory.biz",
        "magsrv.com",
        "adnium.com",
        "realsrv.com",
        "adsco.re",
        "crakrevenue.com",
        "adspy.com",
        "exoticads.com",
        "trafficstars.com",
        // 国内常见
        "tanx.com",
        "alimama.com",
        "gdt.qq.com",
        "e.qq.com",
        "pos.baidu.com",
        "hm.baidu.com",
        "cnzz.com",
        "umeng.com",
        "miaozhen.com",
        "irs01.com",
        "51.la"
    )

    /** 路径片段（强制小写比较）。 */
    private val AD_PATH_MARKERS = listOf(
        "/ads/",
        "/adserver",
        "/advert/",
        "/advertisement",
        "/adsense",
        "/prebid",
        "adsbygoogle",
        "popunder",
        "/popads",
        "/adframe",
        "/ad_banner",
        "/ad_banners",
        "/bannerads",
        "/vast/",
        "/adcall"
    )

    /** 可以直接交给 ExoPlayer 的媒体扩展名（不含 HLS 分片 .ts/.m4s）。 */
    private val MEDIA_EXT = listOf(
        ".mp4", ".m3u8", ".mpd", ".webm", ".flv", ".mov", ".mkv", ".m4v", ".ogv"
    )

    fun isBlocked(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        val uri = runCatching { Uri.parse(lower) }.getOrNull() ?: return false
        val host = uri.host ?: return false

        if (HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }) return true

        val path = uri.path ?: return false
        if (MEDIA_EXT.any { path.endsWith(it) }) return false
        return AD_PATH_MARKERS.any { path.contains(it) }
    }

    /** 是不是一个可以直接交给播放器的媒体地址（blob:/data: 不行）。 */
    fun isPlayableMediaUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val path = (uri.path ?: "").lowercase()
        return MEDIA_EXT.any { path.endsWith(it) }
    }

    /** "hls" / "dash" / "progressive" —— 交给播放器选对应的 MediaSource。 */
    fun mediaKind(url: String): String {
        val path = runCatching { Uri.parse(url).path }.getOrNull()?.lowercase() ?: return "progressive"
        return when {
            path.endsWith(".m3u8") -> "hls"
            path.endsWith(".mpd") -> "dash"
            else -> "progressive"
        }
    }
}
