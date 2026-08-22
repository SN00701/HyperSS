package com.hyperss.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hyperss.app.R
import com.hyperss.app.util.UpdateChecker
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/** 打开 GitHub Releases 页面（交由浏览器下载安装包）。 */
fun openReleasesPage(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.RELEASES_PAGE)),
        )
    }
}

/**
 * 「发现新版本」对话框：启动自动检查与设置页手动检查共用。
 * 「稍后」会记住该版本，启动时不再重复提醒。
 */
@Composable
fun UpdateAvailableDialog(
    newVersion: String,
    currentVersion: String,
    notes: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val summary = buildString {
        append(stringResource(R.string.update_new_version_summary, newVersion, currentVersion))
        if (notes.isNotBlank()) {
            append("\n\n")
            append(stringResource(R.string.update_notes_label))
            append(":\n")
            append(notes.take(400))
        }
    }
    OverlayDialog(
        show = true,
        title = stringResource(R.string.update_new_version_title),
        summary = summary,
        onDismissRequest = onDismiss,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(
                text = stringResource(R.string.update_later),
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(20.dp))
            TextButton(
                text = stringResource(R.string.update_download),
                onClick = {
                    onDismiss()
                    openReleasesPage(context)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}
