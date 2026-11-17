package app.magnetrail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.magnetrail.SubtitleController
import app.magnetrail.shared.SubtitleMode
import app.magnetrail.shared.SubtitleSource
import app.magnetrail.shared.SubtitleState

/**
 * 播放器右侧设置面板。
 *
 * 只放「播放时想改」的东西：字幕（开关/主语言/源语言/来源/AI 生成）、显示样式、
 * 清晰度。所有改动即时生效并持久化。
 */
@Composable
fun PlayerSettingsPanel(
    controller: SubtitleController,
    aiState: SubtitleState,
    qualityLabels: List<String>,
    qualitySelected: Int,
    preferHd: Boolean,
    onSelectQuality: (Int) -> Unit,
    onPreferHd: (Boolean) -> Unit,
    onAiInstall: () -> Unit,
    onAiGenerate: () -> Unit,
    onAiCancel: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(320.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "播放设置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onClose) { Text("关闭") }
        }

        // ------------------------------------------------------------ 字幕
        Section("字幕")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("显示字幕", style = MaterialTheme.typography.bodyMedium)
                Text(
                    controller.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = controller.mode != SubtitleMode.OFF,
                onCheckedChange = { if ((it) != (controller.mode != SubtitleMode.OFF)) controller.toggle() }
            )
        }

        Label("翻译语言（译文）")
        ChipRow(
            options = listOf(
                "zh" to "中文",
                "en" to "英文",
                "ja" to "日文",
                "ko" to "韩语"
            ),
            selected = controller.secondLanguage,
            onSelect = { controller.chooseSecondLanguage(it) }
        )

        Label("显示方式")
        ChipRow(
            options = listOf(
                SubtitleMode.ORIGINAL to "原文",
                SubtitleMode.CHINESE to "译文",
                SubtitleMode.BILINGUAL to "双语"
            ),
            selected = controller.mode,
            onSelect = { controller.applyMode(it) }
        )

        Label("源语言（识别）")
        ChipRow(
            options = listOf(
                "" to "自动",
                "ja" to "日语",
                "en" to "英语",
                "zh" to "中文"
            ),
            selected = controller.sourceLanguage,
            onSelect = { controller.chooseSourceLanguage(it) }
        )

        Label("字幕来源")
        ChipRow(
            options = SubtitleSource.entries.map { it to it.label },
            selected = controller.source,
            onSelect = { controller.applySource(it) }
        )

        // AI 离线生成
        if (controller.source == SubtitleSource.AI_OFFLINE) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("生成译文", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "关掉只出原文",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = controller.bilingualOnGenerate,
                    onCheckedChange = { controller.setBilingual(it) }
                )
            }
            val working = aiState as? SubtitleState.Working
            Text(
                when (aiState) {
                    SubtitleState.Idle ->
                        if (controller.aiAvailable()) "模型已就绪" else "模型未安装"
                    is SubtitleState.Working -> aiState.message
                    is SubtitleState.NeedsModel -> aiState.message
                    is SubtitleState.Error -> aiState.message
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (aiState is SubtitleState.Error) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (working != null) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { working.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (working != null) {
                    TextButton(onClick = onAiCancel) { Text("取消") }
                } else {
                    TextButton(onClick = {
                        if (controller.aiAvailable()) onAiGenerate() else onAiInstall()
                    }) {
                        Text(if (controller.aiAvailable()) "生成字幕" else "下载模型（228MB）")
                    }
                }
            }
        }

        // 实时生成：独立翻译开关。
        // 只要这个视频具备实时生成条件就显示（模型可用 + 有可解码音源），
        // 不再依赖「当前是否已经在跑实时」，否则用户在有本地字幕时根本找不到这个开关。
        val livePossible = controller.source == SubtitleSource.AI_LIVE ||
            controller.liveActive ||
            controller.canLive()
        if (livePossible) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("实时翻译", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "关闭后只显示识别出的原文",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = controller.liveTranslate,
                    onCheckedChange = { controller.applyLiveTranslate(it) }
                )
            }
            if (controller.liveTranslate) {
                Label("翻译语言")
                ChipRow(
                    options = listOf(
                        "zh" to "中文",
                        "en" to "英文",
                        "ja" to "日文",
                        "ko" to "韩语"
                    ),
                    selected = controller.secondLanguage,
                    onSelect = { controller.chooseSecondLanguage(it) }
                )
            }
            Text(
                controller.liveStatusText(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 10.dp))

        // ------------------------------------------------------------ 样式
        Section("显示样式")
        Label("字号")
        ChipRow(
            options = listOf(0.85f to "小", 1.0f to "中", 1.25f to "大"),
            selected = controller.fontScale,
            onSelect = { controller.applyFontScale(it) }
        )
        Label("位置")
        ChipRow(
            options = listOf("bottom" to "底部", "middle" to "下方"),
            selected = controller.position,
            onSelect = { controller.applyPosition(it) }
        )
        Label("时间轴")
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { controller.bumpOffset(-200) }) { Text("−0.2s") }
            Text("%+.1fs".format(controller.offsetMs / 1000f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { controller.bumpOffset(200) }) { Text("+0.2s") }
            TextButton(onClick = { controller.resetOffset() }) { Text("归零") }
        }

        // ------------------------------------------------------------ 隐私
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 10.dp))
        Section("隐私")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("防截屏 / 防录屏", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "开启后截图、录屏、任务缩略图都会变黑（画中画缩略图也会）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = controller.secureScreen,
                onCheckedChange = { controller.applySecureScreen(it) }
            )
        }

        // ------------------------------------------------------------ 清晰度
        if (qualityLabels.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 10.dp))
            Section("清晰度")
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Chip(
                        label = "自动（自适应）",
                        selected = qualitySelected < 0,
                        onClick = { onSelectQuality(-1) }
                    )
                }
                qualityLabels.forEachIndexed { index, label ->
                    Chip(
                        label = label,
                        selected = qualitySelected == index,
                        onClick = { onSelectQuality(index) }
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("起播就选最高档", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "网速跟不上会自动退回",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = preferHd, onCheckedChange = onPreferHd)
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)
    )
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    // 选项多时换行（字幕来源有 5 个，单行 Row 会把 AI 实时挤出面板、点不到）
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label) }
            )
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
        )
    }
}
