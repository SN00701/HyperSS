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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hyperss.app.R
import com.hyperss.app.data.CaptureController
import com.hyperss.app.service.FloatingToolbarService
import com.hyperss.app.service.MediaProjectionCaptureService
import com.hyperss.app.ui.AmbientGlassLayer
import com.hyperss.app.ui.GlassButton
import com.hyperss.app.ui.GlassChipButton
import com.hyperss.app.ui.GlassDialog
import com.hyperss.app.ui.GlassSurface
import com.hyperss.app.ui.LiquidTopBar
import com.hyperss.app.ui.liquidGlassLayer
import com.hyperss.app.ui.rememberLiquidBackdrop
import com.hyperss.app.ui.rememberLiquidContentBackdrop
import com.hyperss.app.ui.theme.Danger
import com.hyperss.app.ui.theme.LightTextSecondary
import com.hyperss.app.ui.rememberTopBarBlurFraction
import com.hyperss.app.util.Permissions
import com.hyperss.app.util.Settings
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
            startFailed = context.getString(R.string.capture_screenrecord_failed)
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

    // 顶部液态玻璃：内容滚动时玻璃标题栏渐入
    val liquidAmbient = rememberLiquidBackdrop()
    val liquidContent = rememberLiquidContentBackdrop()
    val listState = rememberLazyListState()
    val blurFraction = rememberTopBarBlurFraction(listState)
    var barHeight by remember { mutableStateOf(0.dp) }

    Box(modifier = Modifier.fillMaxSize()) {
        // 环境光斑玻璃层：设置卡片液态玻璃采样它
        AmbientGlassLayer(liquidAmbient)
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .liquidGlassLayer(liquidContent),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = barHeight + 20.dp, end = 20.dp, bottom = 40.dp,
            ),
        ) {
            item {
                Text(
                    stringResource(R.string.capture_select_project),
                    style = MiuixTheme.textStyles.title3,
                    color = LightTextSecondary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                ProjectPicker(setup = setup, viewModel = viewModel, liquidBackdrop = liquidAmbient)
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    stringResource(R.string.capture_mode),
                    style = MiuixTheme.textStyles.title3,
                    color = LightTextSecondary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp, liquidBackdrop = liquidAmbient) {
                    Column {
                        ModeOption(
                            stringResource(R.string.capture_mode_auto),
                            stringResource(R.string.capture_mode_auto_desc),
                            setup.mode == CaptureMode.AUTO_SCROLL,
                        ) { viewModel.selectMode(CaptureMode.AUTO_SCROLL) }
                        ModeOption(
                            stringResource(R.string.capture_mode_manual),
                            stringResource(R.string.capture_mode_manual_desc),
                            setup.mode == CaptureMode.MANUAL,
                        ) { viewModel.selectMode(CaptureMode.MANUAL) }
                        ModeOption(
                            stringResource(R.string.capture_mode_fixed),
                            stringResource(R.string.capture_mode_fixed_desc),
                            setup.mode == CaptureMode.FIXED_STEP,
                        ) { viewModel.selectMode(CaptureMode.FIXED_STEP) }
                        ModeOption(
                            stringResource(R.string.capture_mode_calibrated),
                            stringResource(R.string.capture_mode_calibrated_desc),
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
                        label = stringResource(R.string.capture_step_hint),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Text(
                        stringResource(R.string.capture_step_warning),
                        style = MiuixTheme.textStyles.footnote2,
                        color = Danger,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(28.dp))
                GlassButton(
                    onClick = {
                        pendingStart = true
                        doStart()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    liquidBackdrop = liquidAmbient,
                    // CTA 玻璃垫色偏实，保证白色文案在深浅主题下都可读
                    background = MiuixTheme.colorScheme.primary.copy(alpha = 0.78f),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(MiuixIcons.Play, contentDescription = null, tint = MiuixTheme.colorScheme.onPrimary)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            stringResource(R.string.capture_start),
                            color = MiuixTheme.colorScheme.onPrimary,
                            style = MiuixTheme.textStyles.button,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                CaptureStatus(controllerState, liquidAmbient)
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
                Text(stringResource(R.string.capture_setup_title), style = MiuixTheme.textStyles.title1)
            }
        }
    }

    permissionDialog?.let { which ->
        val message = when (which) {
            "accessibility" -> stringResource(R.string.capture_accessibility_msg)
            "overlay" -> stringResource(R.string.capture_overlay_msg)
            else -> ""
        }
        GlassDialog(
            show = true,
            title = stringResource(R.string.permission_needed_title),
            summary = message,
            onDismissRequest = { permissionDialog = null; pendingStart = false },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = { permissionDialog = null; pendingStart = false },
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.permission_go),
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
        GlassDialog(
            show = true,
            title = stringResource(R.string.capture_start_failed_title),
            summary = msg,
            onDismissRequest = { startFailed = null; pendingStart = false },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    text = stringResource(R.string.ok),
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
private fun ProjectPicker(
    setup: CaptureSetupState,
    viewModel: CaptureViewModel,
    liquidBackdrop: com.kyant.backdrop.Backdrop? = null,
) {
    var showList by remember { mutableStateOf(false) }
    val selected = setup.projects.firstOrNull { it.id == setup.selectedProjectId }

    GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp, liquidBackdrop = liquidBackdrop) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    selected?.let { "${it.prefix} · ${it.displayName}" } ?: stringResource(R.string.capture_create_project_first),
                    style = MiuixTheme.textStyles.body1,
                    modifier = Modifier.weight(1f),
                )
                TextButton(text = stringResource(R.string.select), onClick = { showList = true })
            }
        }
    }

    if (showList) {
        GlassDialog(
            show = true,
            title = stringResource(R.string.capture_select_project),
            onDismissRequest = { showList = false },
        ) {
            Column {
                if (setup.projects.isEmpty()) {
                    Text(stringResource(R.string.capture_create_project_first), style = MiuixTheme.textStyles.body2, color = LightTextSecondary)
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
                TextButton(text = stringResource(R.string.cancel), onClick = { showList = false })
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
            label = stringResource(R.string.capture_new_prefix),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextField(
            value = setup.newProjectName,
            onValueChange = viewModel::setNewProjectName,
            label = stringResource(R.string.capture_new_project_name),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        val defaultProjectName = stringResource(R.string.capture_default_project_name)
        TextButton(text = stringResource(R.string.capture_new_btn), onClick = {
            viewModel.setNewProjectPrefix(setup.newProjectPrefix.ifEmpty { "hs" })
            viewModel.setNewProjectName(setup.newProjectName.ifEmpty { defaultProjectName })
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
private fun CaptureStatus(
    state: CaptureController.State,
    liquidBackdrop: com.kyant.backdrop.Backdrop? = null,
) {
    GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp, liquidBackdrop = liquidBackdrop) {
        Column(modifier = Modifier.padding(16.dp)) {
            val reasonText = when (state) {
                is CaptureController.State.Paused -> when (state.reason) {
                    "user" -> stringResource(R.string.float_paused_user)
                    "jump" -> stringResource(R.string.float_pause_reason_jump)
                    "screen_off" -> stringResource(R.string.float_interrupt_screen_off)
                    "left_app" -> stringResource(R.string.float_interrupt_app_left)
                    "a11y" -> stringResource(R.string.float_interrupt_a11y)
                    else -> state.reason
                }
                else -> ""
            }
            val (label, detail) = when (state) {
                is CaptureController.State.Idle ->
                    stringResource(R.string.capture_status_idle) to stringResource(R.string.capture_status_idle_hint)
                is CaptureController.State.Preparing ->
                    stringResource(R.string.capture_status_preparing) to stringResource(R.string.capture_status_preparing_hint)
                is CaptureController.State.Active ->
                    stringResource(R.string.capture_status_active) to stringResource(
                        R.string.capture_status_active_detail,
                        state.progress.frameCount,
                        state.progress.compositeHeight,
                        "%.2f".format(state.progress.confidence),
                    )
                is CaptureController.State.Paused ->
                    stringResource(R.string.toolbar_paused_appearance) to reasonText
                is CaptureController.State.Finished ->
                    stringResource(R.string.capture_status_finished) to
                        (state.image?.fileName ?: stringResource(R.string.capture_status_discarded))
                is CaptureController.State.Failed ->
                    stringResource(R.string.capture_status_failed) to state.message
            }
            Text(label, style = MiuixTheme.textStyles.title2)
            Text(detail, style = MiuixTheme.textStyles.body2, color = LightTextSecondary)
        }
    }
}