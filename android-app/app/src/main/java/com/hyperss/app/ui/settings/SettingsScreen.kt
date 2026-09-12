package com.hyperss.app.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hyperss.app.R
import com.hyperss.app.util.Permissions
import com.hyperss.app.util.Settings
import com.hyperss.app.ui.AmbientGlassLayer
import com.hyperss.app.ui.UpdateAvailableDialog
import com.hyperss.app.ui.GlassChipButton
import com.hyperss.app.ui.GlassDialog
import com.hyperss.app.ui.GlassSurface
import com.hyperss.app.ui.LiquidTopBar
import com.hyperss.app.ui.liquidGlassLayer
import com.hyperss.app.ui.rememberLiquidBackdrop
import com.hyperss.app.ui.rememberLiquidContentBackdrop
import com.hyperss.app.ui.rememberTopBarBlurFraction
import com.hyperss.app.ui.theme.LightTextSecondary

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val overlayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { viewModel.refresh() }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refresh() }

    var showDisclaimer by remember { mutableStateOf(false) }
    var showRestartDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    val restartApp: () -> Unit = {
        showRestartDialog = false
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (intent != null) {
            context.startActivity(intent)
            Handler(Looper.getMainLooper()).postDelayed({
                Process.killProcess(Process.myPid())
            }, 500)
        }
    }

    // 顶部液态玻璃：内容滚动时玻璃标题栏渐入
    val liquidAmbient = rememberLiquidBackdrop()
    val liquidContent = rememberLiquidContentBackdrop()
    val listState = rememberLazyListState()
    val blurFraction = rememberTopBarBlurFraction(listState)
    var barHeight by remember { mutableStateOf(0.dp) }

    Box(modifier = Modifier.fillMaxSize()) {
        // 环境光斑玻璃层：设置卡片液态玻璃采样它
        AmbientGlassLayer(liquidAmbient)
        // 滚动列表置于 backdrop 层下
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .liquidGlassLayer(liquidContent),
            contentPadding = PaddingValues(
                top = barHeight + 20.dp,
                start = 20.dp,
                end = 20.dp,
                bottom = 40.dp,
            ),
        ) {
            item {
                SectionTitle(stringResource(R.string.settings_permissions))
                GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp, liquidBackdrop = liquidAmbient) {
                    Column {
                        PermissionRow(
                            title = stringResource(R.string.settings_accessibility),
                            subtitle = stringResource(R.string.settings_accessibility_desc),
                            checked = state.hasAccessibility,
                            onClick = {
                                context.startActivity(Permissions.accessibilitySettingsIntent())
                            },
                        )
                        PermissionRow(
                            title = stringResource(R.string.settings_overlay),
                            subtitle = stringResource(R.string.settings_overlay_desc),
                            checked = state.hasOverlay,
                            onClick = {
                                if (!state.hasOverlay) {
                                    runCatching {
                                        context.startActivity(Permissions.requestOverlayIntent(context))
                                    }
                                } else {
                                    overlayLauncher.launch(Permissions.requestOverlayIntent(context))
                                }
                            },
                        )
                        PermissionRow(
                            title = stringResource(R.string.settings_notifications),
                            subtitle = stringResource(R.string.settings_notifications_desc),
                            checked = state.hasNotifications,
                            onClick = {
                                if (android.os.Build.VERSION.SDK_INT >= 33 &&
                                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                                    PackageManager.PERMISSION_GRANTED
                                ) {
                                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            },
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
                SectionTitle(stringResource(R.string.settings_appearance))
                GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp, liquidBackdrop = liquidAmbient) {
                    Column {
                        RadioGroup(
                            title = stringResource(R.string.settings_language),
                            options = listOf(
                                "system" to stringResource(R.string.settings_language_system),
                                "zh" to stringResource(R.string.settings_language_zh),
                                "en" to stringResource(R.string.settings_language_en),
                            ),
                            selected = state.language,
                            onSelect = {
                                viewModel.setLanguage(it)
                                showRestartDialog = true
                            },
                        )
                        RadioGroup(
                            title = stringResource(R.string.settings_theme),
                            options = listOf(
                                "system" to stringResource(R.string.settings_theme_system),
                                "light" to stringResource(R.string.settings_theme_light),
                                "dark" to stringResource(R.string.settings_theme_dark),
                            ),
                            selected = state.theme,
                            onSelect = {
                                viewModel.setTheme(it)
                                showRestartDialog = true
                            },
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
                SectionTitle(stringResource(R.string.settings_capture))
                GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp, liquidBackdrop = liquidAmbient) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        var rate by remember { mutableStateOf(state.duplicateRate) }
                        Text(
                            stringResource(R.string.settings_duplicate_rate),
                            style = MiuixTheme.textStyles.title3,
                        )
                        Text(
                            stringResource(R.string.settings_duplicate_rate_desc),
                            style = MiuixTheme.textStyles.footnote2,
                            color = LightTextSecondary,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Slider(
                                value = rate.toFloat(),
                                onValueChange = { rate = it.toInt().coerceIn(1, 100) },
                                onValueChangeFinished = { viewModel.setDuplicateRate(rate) },
                                valueRange = 1f..100f,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                "$rate%",
                                style = MiuixTheme.textStyles.body1,
                                modifier = Modifier.width(48.dp),
                            )
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
                SectionTitle(stringResource(R.string.settings_about))
                GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp, liquidBackdrop = liquidAmbient) {
                    Column {
                        AboutRow(stringResource(R.string.settings_version_label), state.version)
                        AboutRow(stringResource(R.string.settings_license), "MIT")
                        // 隐私说明：描述较长，标题在上、描述作为多行副标题在下（避免并排挤压标题）
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(stringResource(R.string.settings_privacy), style = MiuixTheme.textStyles.body2)
                                Text(
                                    stringResource(R.string.settings_privacy_desc),
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = LightTextSecondary,
                                )
                            }
                        }
                        AboutRow(stringResource(R.string.settings_feedback), "GitHub Issues")
                        val updateState by viewModel.updateState.collectAsState()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.update_check),
                                style = MiuixTheme.textStyles.body2,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                text = if (updateState == UpdateUi.Checking) {
                                    stringResource(R.string.update_checking)
                                } else {
                                    stringResource(R.string.update_check_action)
                                },
                                onClick = { viewModel.checkUpdate() },
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.settings_disclaimer), style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f))
                            TextButton(text = stringResource(R.string.settings_view), onClick = { showDisclaimer = true })
                        }
                    }
                }
            }
        }
        LiquidTopBar(
            backdrop = liquidContent,
            fraction = blurFraction,
            refreshKey = listState.firstVisibleItemIndex * 1_000_000 + listState.firstVisibleItemScrollOffset,
            modifier = Modifier.align(Alignment.TopCenter),
            onHeightChanged = { barHeight = it },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp, start = 4.dp, end = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 返回玻璃圆钮：折射透出滚动内容
                GlassChipButton(
                    onClick = onBack,
                    modifier = Modifier.size(40.dp),
                    liquidBackdrop = liquidContent,
                    redrawKey = listState.firstVisibleItemIndex * 1_000_000 + listState.firstVisibleItemScrollOffset,
                    shape = CircleShape,
                ) {
                    Icon(
                        MiuixIcons.Back,
                        contentDescription = stringResource(R.string.back),
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(stringResource(R.string.settings_title), style = MiuixTheme.textStyles.title1)
            }
        }
    }

    if (showRestartDialog) {
        GlassDialog(
            show = true,
            title = stringResource(R.string.settings_saved_title),
            summary = stringResource(R.string.settings_restart_message),
            onDismissRequest = { showRestartDialog = false },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    text = stringResource(R.string.settings_later),
                    onClick = { showRestartDialog = false },
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.settings_restart_now),
                    onClick = restartApp,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }

    if (showDisclaimer) {
        GlassDialog(
            show = true,
            title = stringResource(R.string.settings_disclaimer),
            summary = stringResource(R.string.settings_disclaimer_content),
            onDismissRequest = { showDisclaimer = false },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    text = stringResource(R.string.ok),
                    onClick = { showDisclaimer = false },
                )
            }
        }
    }

    // 手动检查更新的结果对话框
    val updateState by viewModel.updateState.collectAsState()
    when (val u = updateState) {
        is UpdateUi.Available -> UpdateAvailableDialog(
            newVersion = u.version,
            currentVersion = state.version,
            notes = u.notes,
            onDismiss = {
                Settings.updateSkipVersion = u.version
                viewModel.dismissUpdateDialog()
            },
        )
        UpdateUi.UpToDate -> GlassDialog(
            show = true,
            title = stringResource(R.string.update_check),
            summary = stringResource(R.string.update_up_to_date),
            onDismissRequest = { viewModel.dismissUpdateDialog() },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(text = stringResource(R.string.ok), onClick = { viewModel.dismissUpdateDialog() })
            }
        }
        is UpdateUi.Failed -> GlassDialog(
            show = true,
            title = stringResource(R.string.update_check),
            summary = stringResource(R.string.update_check_failed_reason, u.reason),
            onDismissRequest = { viewModel.dismissUpdateDialog() },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(text = stringResource(R.string.ok), onClick = { viewModel.dismissUpdateDialog() })
            }
        }
        else -> {}
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MiuixTheme.textStyles.title3,
        color = LightTextSecondary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun PermissionRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MiuixTheme.textStyles.body1)
            Text(subtitle, style = MiuixTheme.textStyles.footnote2, color = LightTextSecondary)
        }
        Switch(checked = checked, onCheckedChange = { onClick() })
    }
}

@Composable
private fun RadioGroup(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            title,
            style = MiuixTheme.textStyles.title3,
            color = LightTextSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Column(Modifier.selectableGroup()) {
            options.forEach { (value, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = value == selected, onClick = { onSelect(value) })
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = value == selected, onClick = null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(label, style = MiuixTheme.textStyles.body1)
                }
            }
        }
    }
}

@Composable
private fun AboutRow(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f))
        Text(value, style = MiuixTheme.textStyles.body2, color = LightTextSecondary)
    }
}