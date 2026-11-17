package app.magnetrail.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import app.magnetrail.shared.TorrentState

@Composable
fun AddMagnetCard(
    text: String,
    onTextChange: (String) -> Unit,
    onPaste: () -> Unit,
    onAdd: () -> Unit,
    onWatchOnline: () -> Unit,
    errorText: String?
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        val submit = {
            focus.clearFocus()
            keyboard?.hide()
            onAdd()
        }

        Column(Modifier.padding(12.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = 92.dp),
                label = { Text("磁力链接") },
                placeholder = {
                    Text(
                        "magnet:?xt=urn:btih:…\n粘贴后点「添加任务」",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2
                    )
                },
                minLines = 2,
                maxLines = 4,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                isError = errorText != null,
                supportingText = {
                    if (errorText != null) {
                        Text(errorText)
                    } else {
                        Text("支持 hex(40 位) / base32(32 位) infohash", style = MaterialTheme.typography.bodySmall)
                    }
                },
                trailingIcon = {
                    if (text.isNotEmpty()) {
                        IconButton(onClick = { onTextChange("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = "清空")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { submit() })
            )

            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { submit() },
                    enabled = text.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("添加任务")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onWatchOnline,
                    enabled = text.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.PlayCircle, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("缓冲观看")
                }
            }
            TextButton(
                onClick = onPaste,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.ContentPaste, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("从剪贴板粘贴")
            }
        }
    }
}

fun stateLabel(state: TorrentState): String = when (state) {
    TorrentState.METADATA -> "获取种子信息"
    TorrentState.CHECKING -> "校验已有文件"
    TorrentState.DOWNLOADING -> "下载中"
    TorrentState.SEEDING -> "做种中"
    TorrentState.FINISHED -> "已完成"
    TorrentState.PAUSED -> "已暂停"
    TorrentState.ERROR -> "出错"
    TorrentState.QUEUED -> "排队"
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}
