package com.hyperss.app.ui.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hyperss.app.R
import com.hyperss.app.ui.BlurTopBar
import com.hyperss.app.ui.ambientGlassBackground
import com.hyperss.app.ui.GlassSurface
import com.hyperss.app.ui.rememberTopBarBlurFraction
import com.hyperss.app.ui.theme.LightTextSecondary
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

@Composable
fun ProjectSettingsScreen(
    projectId: Long,
    onBack: () -> Unit,
    viewModel: ProjectSettingsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var showSaveConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(projectId) { viewModel.load(projectId) }

    // 顶部动态模糊：内容滚动时毛玻璃标题栏渐入
    val backdrop = rememberLayerBackdrop()
    val listState = rememberLazyListState()
    val blurFraction = rememberTopBarBlurFraction(listState)
    var barHeight by remember { mutableStateOf(0.dp) }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.loading -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                }
            }
            state.project == null -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(state.error ?: stringResource(R.string.error_title), color = LightTextSecondary)
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(text = stringResource(R.string.back), onClick = onBack)
                }
            }
            else -> {
                val project = state.project!!
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .layerBackdrop(backdrop).ambientGlassBackground(),
                    contentPadding = PaddingValues(
                        top = barHeight + 20.dp,
                        start = 20.dp,
                        end = 20.dp,
                        bottom = 40.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        SectionTitle(stringResource(R.string.project_settings_basic))
                        GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                TextField(
                                    value = state.name,
                                    onValueChange = viewModel::updateName,
                                    label = stringResource(R.string.new_project_name_label),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    stringResource(
                                        R.string.project_settings_prefix_hint,
                                        project.prefix,
                                        project.imageCount,
                                    ),
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = LightTextSecondary,
                                )
                            }
                        }
                    }

                    item {
                        SectionTitle(stringResource(R.string.project_settings_mode))
                        GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
                            Column(Modifier.selectableGroup()) {
                                ModeOption(
                                    "AUTO_SCROLL",
                                    stringResource(R.string.mode_auto),
                                    stringResource(R.string.mode_auto_desc),
                                    state.mode,
                                    viewModel::updateMode,
                                )
                                ModeOption(
                                    "MANUAL",
                                    stringResource(R.string.mode_manual),
                                    stringResource(R.string.mode_manual_desc),
                                    state.mode,
                                    viewModel::updateMode,
                                )
                                ModeOption(
                                    "FIXED_STEP",
                                    stringResource(R.string.mode_fixed),
                                    stringResource(R.string.mode_fixed_desc),
                                    state.mode,
                                    viewModel::updateMode,
                                )
                                ModeOption(
                                    "CALIBRATED_DISTANCE",
                                    stringResource(R.string.mode_calibrated),
                                    stringResource(R.string.mode_calibrated_desc),
                                    state.mode,
                                    viewModel::updateMode,
                                )
                            }
                        }
                    }

                    if (state.mode == "FIXED_STEP") {
                        item {
                            SectionTitle(stringResource(R.string.project_settings_step))
                            GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    var sliderValue by remember(state.stepDp) {
                                        mutableFloatStateOf(state.stepDp.toFloat())
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            state.stepDp.toString(),
                                            style = MiuixTheme.textStyles.title2,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            "dp",
                                            style = MiuixTheme.textStyles.body2,
                                            color = LightTextSecondary,
                                        )
                                    }
                                    Slider(
                                        value = sliderValue,
                                        onValueChange = { sliderValue = it },
                                        valueRange = 40f..1000f,
                                        onValueChangeFinished = { viewModel.updateStep(sliderValue.toInt()) },
                                    )
                                    Text(
                                        stringResource(R.string.project_settings_step_hint),
                                        style = MiuixTheme.textStyles.footnote2,
                                        color = LightTextSecondary,
                                    )
                                }
                            }
                        }
                    }

                    // 截图张数上限：长截图与手动滚动共用，优先级高于循环次数
                    if (state.mode == "AUTO_SCROLL" || state.mode == "MANUAL") {
                        item {
                            SectionTitle("截图张数上限（最高优先级）")
                            GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    var framesValue by remember(state.maxFrames) {
                                        mutableFloatStateOf(state.maxFrames.toFloat())
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            state.maxFrames.toString(),
                                            style = MiuixTheme.textStyles.title2,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            "张",
                                            style = MiuixTheme.textStyles.body2,
                                            color = LightTextSecondary,
                                        )
                                    }
                                    Slider(
                                        value = framesValue,
                                        onValueChange = { framesValue = it },
                                        valueRange = 3f..20f,
                                        steps = 16,
                                        onValueChangeFinished = {
                                            viewModel.updateMaxFrames(framesValue.toInt())
                                        },
                                    )
                                    Text(
                                        "达到该张数后立即收尾保存（默认 10，范围 3~20），优先于循环次数",
                                        style = MiuixTheme.textStyles.footnote2,
                                        color = LightTextSecondary,
                                    )
                                }
                            }
                        }
                    }

                    if (state.mode != "MANUAL") {
                        item {
                            SectionTitle(stringResource(R.string.project_settings_loop_count))
                            GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    var loopText by remember(state.loopCount) {
                                        mutableStateOf(state.loopCount.toString())
                                    }
                                    TextField(
                                        value = loopText,
                                        onValueChange = { input ->
                                            val digits = input.filter { it.isDigit() }
                                            loopText = digits
                                            viewModel.updateLoopCount(digits.toIntOrNull() ?: 0)
                                        },
                                        label = stringResource(R.string.project_settings_loop_count_value),
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Text(
                                        stringResource(R.string.project_settings_loop_count_hint),
                                        style = MiuixTheme.textStyles.footnote2,
                                        color = LightTextSecondary,
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { showSaveConfirm = true },
                            enabled = !state.saving && state.name.trim().isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) {
                            if (state.saving) {
                                CircularProgressIndicator(size = 20.dp, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.size(8.dp))
                                Text(stringResource(R.string.saving))
                            } else {
                                Text(stringResource(R.string.save_settings))
                            }
                        }
                    }
                }
            }
        }

        // 顶部栏最后声明：绘制与触摸均优先于列表
        BlurTopBar(
            backdrop = backdrop,
            fraction = blurFraction,
            modifier = Modifier.align(Alignment.TopCenter),
            onHeightChanged = { barHeight = it },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp, start = 4.dp, end = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(MiuixIcons.Back, contentDescription = stringResource(R.string.back))
                }
                Text(stringResource(R.string.project_settings), style = MiuixTheme.textStyles.title1)
            }
        }
    }

    state.error?.let { msg ->
        if (state.project != null && !state.loading && state.saving.not()) {
            OverlayDialog(
                show = true,
                title = stringResource(R.string.hint),
                summary = msg,
                onDismissRequest = { viewModel.consumeError() },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        text = stringResource(R.string.ok),
                        onClick = { viewModel.consumeError() },
                    )
                }
            }
        }
    }

    if (showSaveConfirm) {
        OverlayDialog(
            show = true,
            title = stringResource(R.string.save_settings_confirm_title),
            summary = stringResource(R.string.save_settings_confirm_message),
            onDismissRequest = { showSaveConfirm = false },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = { showSaveConfirm = false },
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.confirm),
                    onClick = {
                        showSaveConfirm = false
                        viewModel.save()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
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
private fun ModeOption(
    value: String,
    title: String,
    desc: String,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = value == selected, onClick = { onSelect(value) })
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = value == selected, onClick = null)
        Spacer(modifier = Modifier.size(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.primary)
            Text(desc, style = MiuixTheme.textStyles.footnote2, color = LightTextSecondary)
        }
    }
}