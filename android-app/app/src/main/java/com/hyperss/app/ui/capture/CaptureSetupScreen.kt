package com.hyperss.app.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hyperss.app.data.CaptureController
import com.hyperss.app.service.FloatingToolbarService
import com.hyperss.app.service.MediaProjectionCaptureService
import com.hyperss.app.ui.BlurTopBar
import com.hyperss.app.ui.ambientGlassBackground
import com.hyperss.app.ui.GlassSurface
import com.hyperss.app.ui.theme.Danger
import com.hyperss.app.ui.theme.LightTextSecondary
import com.hyperss.app.ui.rememberTopBarBlurFraction
import com.hyperss.app.util.Permissions
import com.hyperss.app.util.Settings
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import uniffi.hyperss_core.CaptureMode

@Composable
fun CaptureSetupScreen(
    onBack: () -> Unit,
    viewModel: CaptureViewModel = viewModel(),
) {
    val setup by viewModel.setup.collectAsState()
    val controllerState by viewModel.controllerState.collectAsState()
    val context = LocalContext.current

    var permissionDialog by remember { mutableStateOf<String?>(null) }
    var startFailed by remember { mutableStateOf<String?>(null) }
    var pendingStart by remember { mutableStateOf(false) }

    var doStart: () -> Unit = {}
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) doStart() else pendingStart = false
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            MediaProjectionCaptureService.start(context, result.resultCode, result.data!!)
            startToolbar(viewModel, context)
        } else {
            startFailed = "未授予屏幕录制权限，无法截屏"
        }
    }

    val onStartFailed: (String) -> Unit = { msg ->
        when (msg) {
            "notification" -> notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            "accessibility" -> permissionDialog = "accessibility"
            "overlay" -> permissionDialog = "overlay"
            "media_projection" -> {
                val mpm = context.getSystemService(android.media.projection.MediaProjectionManager::class.java)
                mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
            }
            else -> startFailed = msg
        }
    }

    // 统一的启动入口：从任一权限设置页返回后由 onResume 自动重查并继续。
    doStart = {
        pendingStart = false
        checkAndStart(viewModel, context, onStartFailed = onStartFailed)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && pendingStart) doStart()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        viewModel.loadProjects()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            doStart()
        }
    }

    // 顶部动态模糊：内容滚动时毛玻璃标题栏渐入
    val backdrop = rememberLayerBackdrop()
    val listState = rememberLazyListState()
    val blurFraction = rememberTopBarBlurFraction(listState)
    var barHeight by remember { mutableStateOf(0.dp) }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop).ambientGlassBackground(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = barHeight + 20.dp, end = 20.dp, bottom = 40.dp,
            ),
        ) {
            item {
                Text(
                    "选择目标项目",
                    style = MiuixTheme.textStyles.title3,
                    color = LightTextSecondary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                ProjectPicker(setup = setup, viewModel = viewModel)
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    "截屏模式",
                    style = MiuixTheme.textStyles.title3,
                    color = LightTextSecondary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
                    Column {
                        ModeOption(
                            "① 长截图",
                            "系统自动滑动，重叠检测拼接，到底自动完成",
                            setup.mode == CaptureMode.AUTO_SCROLL,
                        ) { viewModel.selectMode(CaptureMode.AUTO_SCROLL) }
                        ModeOption(
                            "② 手动滚动",
                            "你手动滑动，每帧独立保存",
                            setup.mode == CaptureMode.MANUAL,
                        ) { viewModel.selectMode(CaptureMode.MANUAL) }
                        ModeOption(
                            "③ 自定义步长",
                            "按固定步长滚动拼接（步长 ≥ 一屏有风险）",
                            setup.mode == CaptureMode.FIXED_STEP,
                        ) { viewModel.selectMode(CaptureMode.FIXED_STEP) }
                        ModeOption(
                            "④ 双点取距",
                            "用标定距离代替步长",
                            setup.mode == CaptureMode.CALIBRATED_DISTANCE,
                        ) { viewModel.selectMode(CaptureMode.CALIBRATED_DISTANCE) }
                    }
                }
            }

            if (setup.mode == CaptureMode.FIXED_STEP) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    TextField(
                        value = setup.stepDp.toString(),
                        onValueChange = { viewModel.setStepDp(it.toIntOrNull() ?: 0) },
                        label = "步长（dp）",
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Text(
                        "注意：步长 ≥ 一屏高度可能导致拼接失败",
                        style = MiuixTheme.textStyles.footnote2,
                        color = Danger,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(28.dp))
                Button(
                    onClick = {
                        pendingStart = true
                        doStart()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = androidx.compose.foundation.layout.PaddingValues(vertical = 14.dp),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(MiuixIcons.Play, contentDescription = null)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("启动悬浮工具条")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                CaptureStatus(controllerState)
            }
        }
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
                    Icon(MiuixIcons.Back, contentDescription = "返回")
                }
                Text("新建录制器", style = MiuixTheme.textStyles.title1)
            }
        }
    }

    permissionDialog?.let { which ->
        val message = when (which) {
            "accessibility" -> "请开启「HyperSS 截图助手」无障碍服务，以支持自动滚动与截屏。"
            "overlay" -> "请允许 HyperSS 显示悬浮窗，悬浮工具条依赖该权限。"
            else -> ""
        }
        OverlayDialog(
            show = true,
            title = "需要权限",
            summary = message,
            onDismissRequest = { permissionDialog = null; pendingStart = false },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    text = "取消",
                    onClick = { permissionDialog = null; pendingStart = false },
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(20.dp))
                TextButton(
                    text = "去开启",
                    onClick = {
                        pendingStart = true
                        when (which) {
                            "accessibility" -> context.startActivity(Permissions.accessibilitySettingsIntent())
                            "overlay" -> {
                                runCatching { context.startActivity(Permissions.requestOverlayIntent(context)) }
                            }
                        }
                        permissionDialog = null
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }

    startFailed?.let { msg ->
        OverlayDialog(
            show = true,
            title = "无法启动",
            summary = msg,
            onDismissRequest = { startFailed = null; pendingStart = false },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    text = "知道了",
                    onClick = { startFailed = null; pendingStart = false },
                )
            }
        }
    }
}

private fun checkAndStart(
    viewModel: CaptureViewModel,
    context: android.content.Context,
    onStartFailed: (String) -> Unit,
    forceCheck: Boolean = false,
) {
    val app = context.applicationContext
    if (!Permissions.hasOverlay(app)) {
        onStartFailed("overlay")
        return
    }
    if (!Permissions.hasAccessibility(app)) {
        onStartFailed("accessibility")
        return
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        onStartFailed("notification")
        return
    }
    // 所有 API 级别都请求 MediaProjection（MIUI 上 AccessibilityService.takeScreenshot 静默失败）
    onStartFailed("media_projection")
    return
}

private fun startToolbar(viewModel: CaptureViewModel, context: android.content.Context) {
    viewModel.ensureProject { projectId ->
        if (projectId > 0) {
            val s = viewModel.setup.value
            FloatingToolbarService.start(
                context,
                projectId,
                s.mode,
                s.stepDp,
                Settings.getProjectLoopCount(projectId),
                targetPackage = null,
            )
            Settings.lastProjectId = projectId
        } else {
            // 项目创建失败：回退到已有项目或忽略。
        }
    }
}

@Composable
private fun ProjectPicker(setup: CaptureSetupState, viewModel: CaptureViewModel) {
    var showList by remember { mutableStateOf(false) }
    val selected = setup.projects.firstOrNull { it.id == setup.selectedProjectId }

    GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    selected?.let { "${it.prefix} · ${it.displayName}" } ?: "（请先创建项目）",
                    style = MiuixTheme.textStyles.body1,
                    modifier = Modifier.weight(1f),
                )
                TextButton(text = "选择", onClick = { showList = true })
            }
        }
    }

    if (showList) {
        OverlayDialog(
            show = true,
            title = "选择目标项目",
            onDismissRequest = { showList = false },
        ) {
            Column {
                if (setup.projects.isEmpty()) {
                    Text("（请先创建项目）", style = MiuixTheme.textStyles.body2, color = LightTextSecondary)
                } else {
                    setup.projects.forEach { p ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = p.id == setup.selectedProjectId, onClick = {
                                    viewModel.selectProject(p.id)
                                    showList = false
                                })
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = p.id == setup.selectedProjectId, onClick = null)
                            Spacer(modifier = Modifier.size(8.dp))
                            Text("${p.prefix} · ${p.displayName}", style = MiuixTheme.textStyles.body1)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(text = "取消", onClick = { showList = false })
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = setup.newProjectPrefix,
            onValueChange = viewModel::setNewProjectPrefix,
            label = "新前缀",
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextField(
            value = setup.newProjectName,
            onValueChange = viewModel::setNewProjectName,
            label = "新项目名",
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(text = "新建", onClick = {
            viewModel.setNewProjectPrefix(setup.newProjectPrefix.ifEmpty { "hs" })
            viewModel.setNewProjectName(setup.newProjectName.ifEmpty { "新项目" })
            viewModel.ensureProject { id -> if (id > 0) viewModel.selectProject(id) }
        })
    }
}

@Composable
private fun ModeOption(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MiuixTheme.textStyles.body1)
            Text(subtitle, style = MiuixTheme.textStyles.footnote2, color = LightTextSecondary)
        }
    }
}

@Composable
private fun CaptureStatus(state: CaptureController.State) {
    GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp) {
        Column(modifier = Modifier.padding(16.dp)) {
            val (label, detail) = when (state) {
                is CaptureController.State.Idle -> "空闲" to "点击上方按钮开始长截图"
                is CaptureController.State.Preparing -> "准备中…" to "正在创建会话"
                is CaptureController.State.Active -> "进行中" to
                    "已完成 ${state.progress.frameCount} 帧 · 高度 ${state.progress.compositeHeight}px · 置信度 ${"%.2f".format(state.progress.confidence)}"
                is CaptureController.State.Paused -> "已暂停" to state.reason
                is CaptureController.State.Finished -> "已完成" to (state.image?.fileName ?: "（已丢弃）")
                is CaptureController.State.Failed -> "失败" to state.message
            }
            Text(label, style = MiuixTheme.textStyles.title2)
            Text(detail, style = MiuixTheme.textStyles.body2, color = LightTextSecondary)
        }
    }
}