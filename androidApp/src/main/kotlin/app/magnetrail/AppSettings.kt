package app.magnetrail

import android.content.Context
import app.magnetrail.shared.SubtitleMode
import app.magnetrail.shared.SubtitleSource

/** App-wide preferences (for now just the global upload rate limit). */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** bytes/sec, 0 = unlimited. */
    var uploadLimitBytes: Int
        get() = prefs.getInt(KEY_UPLOAD_LIMIT, MIN_UPLOAD_LIMIT)
        set(value) {
            prefs.edit().putInt(KEY_UPLOAD_LIMIT, value.coerceAtLeast(0)).apply()
        }

    /** 浏览器：广告拦截默认开启。 */
    var adBlockEnabled: Boolean
        get() = prefs.getBoolean(KEY_AD_BLOCK, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AD_BLOCK, value).apply()
        }

    /** 浏览器：桌面版 UA（手机版网页广告/弹窗太多时用）。 */
    var desktopMode: Boolean
        get() = prefs.getBoolean(KEY_DESKTOP_MODE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DESKTOP_MODE, value).apply()
        }

    /** 浏览器：上次访问的网址。 */
    var lastUrl: String
        get() = prefs.getString(KEY_LAST_URL, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_LAST_URL, value).apply()
        }

    /**
     * 播放器：起播直接选源里的最高档，而不是等自适应慢慢升。
     *
     * 多档 HLS/DASH 站点上，默认 ABR 起播只给 480p 左右，带宽好也要几十秒才升到 1080p；
     * 打开这个开关后由 DefaultTrackSelector 直接挑最高码率档，卡了再自动往下退。
     */
    var preferHd: Boolean
        get() = prefs.getBoolean(KEY_PREFER_HD, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PREFER_HD, value).apply()
        }

    /** 字幕显示模式（关闭 / 原文 / 中文 / 双语），默认关，用户自己开。 */
    var subtitleMode: SubtitleMode
        get() = enumOf(prefs.getString(KEY_SUB_MODE, null), SubtitleMode.OFF)
        set(value) {
            prefs.edit().putString(KEY_SUB_MODE, value.name).apply()
        }

    /** 字幕来源（自动 / 内置 / 本地文件 / AI），默认自动。 */
    var subtitleSource: SubtitleSource
        get() = enumOf(prefs.getString(KEY_SUB_SOURCE, null), SubtitleSource.AUTO)
        set(value) {
            prefs.edit().putString(KEY_SUB_SOURCE, value.name).apply()
        }

    /** 生成 AI 字幕时同时生成译文（默认开）。 */
    var subtitleBilingual: Boolean
        get() = prefs.getBoolean(KEY_SUB_BILINGUAL, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SUB_BILINGUAL, value).apply()
        }

    /** 译文的第二语种，默认中文。 */
    var subtitleSecondLanguage: String
        get() = prefs.getString(KEY_SUB_SECOND_LANG, "zh") ?: "zh"
        set(value) {
            prefs.edit().putString(KEY_SUB_SECOND_LANG, value).apply()
        }

    /** 源语言：空串=自动识别；ja/en/zh 表示强制按该语言翻译。 */
    var subtitleSourceLanguage: String
        get() = prefs.getString(KEY_SUB_SOURCE_LANG, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_SUB_SOURCE_LANG, value).apply()
        }

    /** 实时字幕是否顺带翻译（独立开关，默认开，不受离线开关影响）。 */
    var liveTranslate: Boolean
        get() = prefs.getBoolean(KEY_LIVE_TRANSLATE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_LIVE_TRANSLATE, value).apply()
        }

    /** 播放器防截屏/防录屏（FLAG_SECURE）；默认关，开了画中画缩略图会变黑。 */
    var secureScreen: Boolean
        get() = prefs.getBoolean(KEY_SECURE_SCREEN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SECURE_SCREEN, value).apply()
        }

    /** 字幕字号倍率：0.85 / 1.0 / 1.25。 */
    var subtitleFontScale: Float
        get() = prefs.getFloat(KEY_SUB_FONT_SCALE, 1.0f)
        set(value) {
            prefs.edit().putFloat(KEY_SUB_FONT_SCALE, value).apply()
        }

    /** 字幕位置：bottom（贴近底部）/ middle（画面下方三分之一）。 */
    var subtitlePosition: String
        get() = prefs.getString(KEY_SUB_POSITION, "bottom") ?: "bottom"
        set(value) {
            prefs.edit().putString(KEY_SUB_POSITION, value).apply()
        }

    /** 时间轴微调（毫秒）：正数=字幕往后推。 */
    var subtitleOffsetMs: Int
        get() = prefs.getInt(KEY_SUB_OFFSET_MS, 0)
        set(value) {
            prefs.edit().putInt(KEY_SUB_OFFSET_MS, value.coerceIn(-5_000, 5_000)).apply()
        }

    private inline fun <reified T : Enum<T>> enumOf(raw: String?, fallback: T): T =
        raw?.let { name -> runCatching { enumValueOf<T>(name) }.getOrNull() } ?: fallback

    companion object {
        /**
         * 128 KB/s 是推荐的最低值：再低 peer 很容易把你 choke，BT 的互惠会变差，
         * 下载速度反而受影响；想"几乎不做种"也建议留在这个值。
         */
        const val MIN_UPLOAD_LIMIT = 128 * 1024
        private const val KEY_UPLOAD_LIMIT = "upload_limit_bytes"
        private const val KEY_AD_BLOCK = "browser_ad_block"
        private const val KEY_DESKTOP_MODE = "browser_desktop_mode"
        private const val KEY_LAST_URL = "browser_last_url"
        private const val KEY_PREFER_HD = "player_prefer_hd"
        private const val KEY_SUB_MODE = "player_sub_mode"
        private const val KEY_SUB_SOURCE = "player_sub_source"
        private const val KEY_SUB_BILINGUAL = "player_sub_bilingual"
        private const val KEY_SUB_SECOND_LANG = "player_sub_second_lang"
        private const val KEY_SUB_SOURCE_LANG = "player_sub_source_lang"
        private const val KEY_LIVE_TRANSLATE = "player_live_translate"
        private const val KEY_SECURE_SCREEN = "player_secure_screen"
        private const val KEY_SUB_FONT_SCALE = "player_sub_font_scale"
        private const val KEY_SUB_POSITION = "player_sub_position"
        private const val KEY_SUB_OFFSET_MS = "player_sub_offset_ms"
    }
}
