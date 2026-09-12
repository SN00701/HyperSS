package com.hyperss.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hyperss.app.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.hyperss.app.data.CaptureController
import com.hyperss.app.ui.GlassSurface
import com.hyperss.app.ui.glassStrokeBrush
import com.hyperss.app.util.LocaleHelper
import com.hyperss.app.util.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Forward
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import uniffi.hyperss_core.CaptureMode
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 悬浮工具条：单窗口 miuix Compose 悬浮窗。
 *
 * 交互（图标滑动规格）：
 *  1. 顶部「-」为长按拖动区，可自由拖动窗口；
 *  2. 拖动区下方为左箭头，点击将整个工具条折叠为应用图标，再点展开；
 *  3. 图标滑动模式提供「+」按钮（每次点按添加一个数字图标 1-9，长按清空）
 *     与「循环次数」按钮（点按打开输入面板，0=不限）；
 *  4. 数字图标悬浮于屏幕，可自由拖动；箭头按 1→2→3… 依次指向、不跨越，
 *     起止端点始终吸附在图标圆心；
 *  5. 图标滑动捕获：截图 → 按相邻图标点的完整间距滑动 → 截图 → 循环，
 *     每帧独立保存为项目图片（不拼接）；
 *  6. 底部启动键：参数就绪=绿色 → 点击后=黄色（暂停/继续）→ 运行结束=红色（重新开始）；
 *  7. 启动键下方为退出按钮，点击关闭工具条。
 */
class FloatingToolbarService : Service() {

    companion object {
        private const val EXTRA_PROJECT_ID = "extra_project_id"
        private const val EXTRA_MODE = "extra_mode"
        private const val EXTRA_STEP_DP = "extra_step_dp"
        private const val EXTRA_LOOP_COUNT = "extra_loop_count"
        private const val EXTRA_TARGET_PACKAGE = "extra_target_package"

        @Volatile var instance: FloatingToolbarService? = null
            private set

        /** 手动滚动模式：滑动停止后的自动截图等待时长（毫秒）。 */
        private const val SCROLL_IDLE_MS = 5000L

        /** 手动滚动模式：等待用户开始滑动的超时（毫秒），超时自动保存退出。 */
        private const val SCROLL_WAIT_TIMEOUT_MS = 30_000L

        fun start(
            context: Context,
            projectId: Long,
            mode: CaptureMode,
            stepDp: Int,
            loopCount: Int,
            targetPackage: String?,
        ) {
            val intent = Intent(context, FloatingToolbarService::class.java)
                .putExtra(EXTRA_PROJECT_ID, projectId)
                .putExtra(EXTRA_MODE, mode.name)
                .putExtra(EXTRA_STEP_DP, stepDp)
                .putExtra(EXTRA_LOOP_COUNT, loopCount)
                .putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
            context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingToolbarService::class.java))
        }
    }

    private data class CalibPoint(val x: Float, val y: Float)

    private data class ToolbarUi(
        val collapsed: Boolean = false,
        val status: String = "",
        val loopEditing: Boolean = false,
    )

    private val ReadyGreen = Color(0xFF34C759)
    private val ActiveYellow = Color(0xFFFFCC00)
    private val EndRed = Color(0xFFFF3B30)
    private val DisabledGray = Color(0x66333333)
    private val MiuixBlueBg = Color(0xFF3482FF)

    private lateinit var wm: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val running = AtomicBoolean(false)
    private val ui = MutableStateFlow(ToolbarUi())
    private val points = MutableStateFlow<List<CalibPoint>>(emptyList())

    private var rootView: ComposeView? = null
    private var arrowCollectorJob: kotlinx.coroutines.Job? = null
    private val badgeViews: MutableMap<Int, ComposeView> = mutableMapOf()
    private var params: WindowManager.LayoutParams? = null

    private var projectId = -1L
    private var mode = CaptureMode.AUTO_SCROLL
    private var stepDp = 0
    private var loopCount = 0
    private var targetPackage: String? = null
    @Volatile private var lastInteraction = System.currentTimeMillis()

    private var captureThread: Thread? = null

    /** 当前会话累计拼接帧数（跨暂停/恢复保留，新会话时清零）。 */
    private var captureFrames = 0

    /** Service 宿主 Compose 所需的生命周期 / 存储所有者。 */
    private val owners = object : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
        override val viewModelStore: ViewModelStore = ViewModelStore()

        init {
            savedStateController.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }
    }

    override fun attachBaseContext(newBase: Context) {
        // 与 Activity 一致：按应用内语言偏好解析服务资源，悬浮工具条文案跟随所选语言
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        instance = this
        SessionLifecycleObserver.register(this)
        observeController()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // 被系统杀死后重建（START_STICKY 空意图）：缺少会话参数，直接停止，
            // 避免以 projectId=-1 / 默认模式空转；中断的会话由冷启动恢复流程处理
            stopSelf()
            return START_NOT_STICKY
        }
        projectId = intent.getLongExtra(EXTRA_PROJECT_ID, -1L)
        mode = runCatching { CaptureMode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: "") }
            .getOrDefault(CaptureMode.AUTO_SCROLL)
        stepDp = intent.getIntExtra(EXTRA_STEP_DP, 0)
        loopCount = intent.getIntExtra(EXTRA_LOOP_COUNT, 0)
        targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
        CaptureController.targetPackage = targetPackage

        showWindow()
        if (mode == CaptureMode.CALIBRATED_DISTANCE) {
            showArrowWindow()
            restoreProjectPoints()
        }
        return START_NOT_STICKY
    }

    private fun restoreProjectPoints() {
        if (projectId <= 0) return
        val saved = Settings.getProjectPoints(projectId)
        if (saved.isNotEmpty()) {
            points.update { saved.map { CalibPoint(it.first, it.second) } }
            mainHandler.post { syncBadgeWindows() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        saveCurrentPoints()
        instance = null
        running.set(false)
        mainHandler.removeCallbacksAndMessages(null)
        SessionLifecycleObserver.unregister(this)
        scope.cancel()
        removeAllBadgeWindows()
        removeArrowWindow()
        removeWindow()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- 窗口 ----------

    private fun showWindow() {
        removeWindow()
        removeArrowWindow()
        removeAllBadgeWindows()
        val composeView = ComposeView(this)
        composeView.setViewTreeLifecycleOwner(owners)
        composeView.setViewTreeSavedStateRegistryOwner(owners)
        composeView.setViewTreeViewModelStoreOwner(owners)
        composeView.setContent { ToolbarContent() }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 240
        }
        rootView = composeView
        params = lp
        runCatching { wm.addView(composeView, lp) }
    }

    private fun removeWindow() {
        runCatching { rootView?.let { wm.removeView(it) } }
        rootView = null
        params = null
    }

    private fun moveWindow(dx: Float, dy: Float) {
        val lp = params ?: return
        lp.x += dx.toInt()
        lp.y += dy.toInt()
        runCatching { wm.updateViewLayout(rootView, lp) }
    }

    // ---------- 箭头同步 ----------

    /// 箭头连线由无障碍服务以 TYPE_ACCESSIBILITY_OVERLAY 绘制：
    /// MIUI 等定制系统会抑制应用级 NOT_TOUCHABLE 悬浮窗（防屏幕滤镜机制）
    /// 的渲染，无障碍覆盖层不受该限制，且可保持不可触摸、不拦截手势。
    private fun showArrowWindow() {
        removeArrowWindow()
        arrowCollectorJob = scope.launch {
            points.collect { pts ->
                CaptureAccessibilityService.instance
                    ?.updateArrowOverlay(pts.map { it.x to it.y })
            }
        }
    }

    private fun removeArrowWindow() {
        arrowCollectorJob?.cancel()
        arrowCollectorJob = null
        CaptureAccessibilityService.instance?.removeArrowOverlay()
    }

    // ---------- 徽标窗口 ----------

    private fun syncBadgeWindows() {
        val pts = points.value
        val density = resources.displayMetrics.density
        val badgePx = (22f * density).toInt()

        val existing = badgeViews.keys.toList()
        val needed = pts.indices.toSet()

        for (idx in existing) {
            if (idx !in needed) removeBadgeWindow(idx)
        }

        for (idx in needed) {
            val pt = pts[idx]
            if (badgeViews.containsKey(idx)) {
                moveBadgeWindowTo(idx, pt, badgePx)
            } else {
                showBadgeWindow(idx, pt, badgePx)
            }
        }
    }

    private fun showBadgeWindow(index: Int, point: CalibPoint, badgePx: Int) {
        val composeView = ComposeView(this)
        composeView.setViewTreeLifecycleOwner(owners)
        composeView.setViewTreeSavedStateRegistryOwner(owners)
        composeView.setViewTreeViewModelStoreOwner(owners)
        composeView.setContent {
            BadgeContent(
                num = index + 1,
                onDrag = { newX, newY ->
                    touchInteraction()
                    val w = resources.displayMetrics.widthPixels.toFloat()
                    val h = resources.displayMetrics.heightPixels.toFloat()
                    val cx = newX.coerceIn(0f, w)
                    val cy = newY.coerceIn(0f, h)
                    points.update { list ->
                        list.mapIndexed { idx, cur ->
                            if (idx == index) CalibPoint(cx, cy) else cur
                        }
                    }
                    moveBadgeWindowTo(index, CalibPoint(cx, cy), badgePx)
                },
                onDoubleTap = { removeBadgeAt(index) },
            )
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (point.x - badgePx / 2).toInt()
            y = (point.y - badgePx / 2).toInt()
        }
        badgeViews[index] = composeView
        runCatching { wm.addView(composeView, lp) }
    }

private fun moveBadgeWindowTo(index: Int, point: CalibPoint, badgePx: Int) {
        val view = badgeViews[index] ?: return
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        lp.x = (point.x - badgePx / 2f).toInt()
        lp.y = (point.y - badgePx / 2f).toInt()
        runCatching { wm.updateViewLayout(view, lp) }
    }

    private fun removeBadgeWindow(index: Int) {
        val view = badgeViews.remove(index) ?: return
        runCatching { wm.removeView(view) }
    }

    private fun removeAllBadgeWindows() {
        for ((_, view) in badgeViews) {
            runCatching { wm.removeView(view) }
        }
        badgeViews.clear()
    }

    // ---------- Compose UI ----------

    @Composable
    private fun ToolbarContent() {
        val darkTheme = isSystemInDarkTheme()
        MiuixTheme(colors = if (darkTheme) darkColorScheme() else lightColorScheme()) {
            val uiState = ui.collectAsState().value
            val session = CaptureController.state.collectAsState().value
            val appIcon = remember { appIconBitmap() }
            LaunchedEffect(Unit) {
                while (true) {
                    kotlinx.coroutines.delay(1000)
                    val elapsed = System.currentTimeMillis() - lastInteraction
                    if (elapsed >= 15_000 && !ui.value.collapsed && !running.get()) {
                        ui.update { it.copy(collapsed = true) }
                    }
                }
            }
            LaunchedEffect(uiState.loopEditing) {
                val lp = params ?: return@LaunchedEffect
                if (uiState.loopEditing) {
                    lp.flags = lp.flags and
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                } else {
                    lp.flags = lp.flags or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                }
                runCatching { wm.updateViewLayout(rootView, lp) }
            }
            if (uiState.collapsed) {
                CollapsedBubble(appIcon) {
                    touchInteraction()
                    ui.update { it.copy(collapsed = false) }
                }
            } else if (uiState.loopEditing) {
                LoopCountEditor()
            } else {
                ExpandedPanel(uiState, session)
            }
        }
    }

    @Composable
    private fun CollapsedBubble(appIcon: ImageBitmap?, onClick: () -> Unit) {
        // 静态玻璃：半透明主色垫 + 受光描边（系统覆盖窗无法采样背后画面，用静态玻璃体系）
        GlassSurface(
            modifier = Modifier
                .size(56.dp)
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress { change, dragAmount ->
                        change.consume()
                        touchInteraction()
                        moveWindow(dragAmount.x, dragAmount.y)
                    }
                }
                .clickable(onClick = onClick),
            cornerRadius = 28.dp,
            background = MiuixBlueBg.copy(alpha = 0.88f),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    MiuixIcons.Forward,
                    contentDescription = stringResource(R.string.float_expand),
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }

    @Composable
    private fun ExpandedPanel(uiState: ToolbarUi, session: CaptureController.State) {
        val pointsState = points.collectAsState().value
        val panelWidth = if (mode == CaptureMode.CALIBRATED_DISTANCE) 120.dp else 84.dp
        // 静态玻璃卡片：半透明深色垫 + 受光渐变描边，无投影
        GlassSurface(
            modifier = Modifier.width(panelWidth),
            cornerRadius = 22.dp,
            background = Color(0xF2212328),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                DragHandle()
                Spacer(Modifier.height(2.dp))
                IconButton(onClick = { touchInteraction(); ui.update { it.copy(collapsed = true) } }) {
                    Icon(MiuixIcons.Back, contentDescription = stringResource(R.string.float_collapse), tint = Color(0xCCFFFFFF))
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    uiState.status.ifEmpty { defaultStatus(session, pointsState.size) },
                    style = MiuixTheme.textStyles.footnote2,
                    color = Color(0x99FFFFFF),
                )
                if (mode == CaptureMode.CALIBRATED_DISTANCE) {
                    Spacer(Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AddIconButton(enabled = !running.get())
                        LoopCountButton(enabled = !running.get())
                    }
                }
                Spacer(Modifier.height(6.dp))
                MainButton(session, uiState, pointsState.size)
                Spacer(Modifier.height(6.dp))
                IconButton(onClick = { touchInteraction(); exitToolbar() }) {
                    Icon(MiuixIcons.Close, contentDescription = stringResource(R.string.float_exit), tint = Color(0xAAFFFFFF))
                }
            }
        }
    }

    @Composable
    private fun DragHandle() {
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(6.dp)
                .clip(CircleShape)
                .background(Color(0x55FFFFFF))
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress { change, dragAmount ->
                        change.consume()
                        touchInteraction()
                        moveWindow(dragAmount.x, dragAmount.y)
                    }
                },
        )
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun AddIconButton(enabled: Boolean) {
        GlassSurface(
            modifier = Modifier
                .width(56.dp)
                .height(40.dp)
                .combinedClickable(
                    enabled = enabled,
                    onClick = { onAddIcon() },
                    onLongClick = { onClearIcons() },
                ),
            cornerRadius = 20.dp,
            background = (if (enabled) ReadyGreen else DisabledGray).copy(alpha = 0.88f),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("+", style = MiuixTheme.textStyles.button, color = Color.White)
            }
        }
    }

    @Composable
    private fun LoopCountButton(enabled: Boolean) {
        GlassSurface(
            modifier = Modifier
                .width(56.dp)
                .height(40.dp)
                .clickable(enabled = enabled) { touchInteraction(); ui.update { it.copy(loopEditing = true) } },
            cornerRadius = 20.dp,
            background = (if (enabled) MiuixBlueBg else DisabledGray).copy(alpha = 0.88f),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (loopCount > 0) "$loopCount" else "∞",
                    style = MiuixTheme.textStyles.button,
                    color = Color.White,
                )
            }
        }
    }

    @Composable
    private fun LoopCountEditor() {
        var text by remember { mutableStateOf(loopCount.toString()) }
        GlassSurface(
            modifier = Modifier.width(220.dp),
            cornerRadius = 22.dp,
            background = Color(0xF2212328),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.project_settings_loop_count), style = MiuixTheme.textStyles.title3, color = Color.White)
                TextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() } },
                    label = stringResource(R.string.project_settings_loop_count_value),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        text = stringResource(R.string.cancel),
                        onClick = { ui.update { it.copy(loopEditing = false) } },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = stringResource(R.string.confirm),
                        onClick = {
                            loopCount = text.toIntOrNull() ?: 0
                            Settings.setProjectLoopCount(projectId, loopCount)
                            ui.update { it.copy(loopEditing = false) }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }

    @Composable
    private fun MainButton(session: CaptureController.State, uiState: ToolbarUi, pointCount: Int = 0) {
        val (bg, icon, enabled) = when (session) {
            is CaptureController.State.Active -> Triple(ActiveYellow, MiuixIcons.Pause, true)
            is CaptureController.State.Paused -> Triple(ActiveYellow, MiuixIcons.Play, true)
            is CaptureController.State.Finished,
            is CaptureController.State.Failed -> Triple(EndRed, MiuixIcons.Refresh, true)
            is CaptureController.State.Preparing -> Triple(DisabledGray, MiuixIcons.Play, false)
            is CaptureController.State.Idle -> {
                if (mode == CaptureMode.CALIBRATED_DISTANCE && pointCount < 2) {
                    Triple(DisabledGray, MiuixIcons.Play, false)
                } else {
                    Triple(ReadyGreen, MiuixIcons.Play, true)
                }
            }
        }
        // 状态玻璃键：状态色半透明垫 + 受光描边（绿=就绪 / 黄=运行 / 红=结束 / 灰=禁用）
        GlassSurface(
            modifier = Modifier
                .size(52.dp)
                .clickable(
                    interactionSource = null,
                    indication = null,
                    enabled = enabled,
                    onClick = { onMainButtonClick() },
                ),
            cornerRadius = 26.dp,
            background = bg.copy(alpha = 0.9f),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
    }

    // ---------- 徽标内容 ----------

    @Composable
    private fun BadgeContent(num: Int, onDrag: (Float, Float) -> Unit, onDoubleTap: () -> Unit) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(MiuixBlueBg)
                .border(2.dp, Color.White, CircleShape)
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { onDoubleTap() })
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        touchInteraction()
                        val current = points.value.getOrNull(num - 1) ?: return@detectDragGestures
                        onDrag(current.x + dragAmount.x, current.y + dragAmount.y)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("$num", style = MiuixTheme.textStyles.footnote1, color = Color.White)
        }
    }

    // ---------- 交互 ----------

    private fun onAddIcon() {
        touchInteraction()
        if (running.get()) return
        val pts = points.value
        if (pts.size >= 9) {
            ui.update { it.copy(status = getString(R.string.float_max_icons)) }
            return
        }
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        val count = pts.size
        val x = w * 0.7f
        val y = (h * (0.25f + 0.06f * count)).coerceAtMost(h * 0.9f)
        points.update { it + CalibPoint(x, y) }
        syncBadgeWindows()
        ui.update {
            it.copy(status = if (count + 1 < 2) getString(R.string.float_add_icon) else getString(R.string.float_drag_icons))
        }
    }

    private fun onClearIcons() {
        if (running.get()) return
        points.update { emptyList() }
        removeAllBadgeWindows()
        ui.update { it.copy(status = getString(R.string.float_add_icon)) }
    }

    private fun touchInteraction() {
        lastInteraction = System.currentTimeMillis()
    }

    private fun removeBadgeAt(index: Int) {
        if (running.get()) return
        val current = points.value
        if (index < 0 || index >= current.size) return
        removeBadgeWindow(index)
        points.update { list -> list.toMutableList().apply { removeAt(index) } }
        val remaining = points.value
        syncBadgeWindows()
        ui.update {
            it.copy(status = when {
                remaining.isEmpty() -> getString(R.string.float_add_icon)
                remaining.size < 2 -> getString(R.string.float_add_one_more)
                else -> getString(R.string.float_drag_icons)
            })
        }
    }

    private fun onMainButtonClick() {
        touchInteraction()
        Log.d("HyperSS", "onMainButtonClick: state=${CaptureController.state.value::class.simpleName} mode=$mode points=${points.value.size}")
        when (val session = CaptureController.state.value) {
            is CaptureController.State.Active -> pauseSession()
            is CaptureController.State.Paused -> resumeSession()
            is CaptureController.State.Preparing -> Unit
            is CaptureController.State.Finished,
            is CaptureController.State.Failed,
            is CaptureController.State.Idle -> {
                if (mode == CaptureMode.CALIBRATED_DISTANCE && points.value.size < 2) return
                startSession()
            }
        }
    }

    private fun startSession() {
        val metrics = resources.displayMetrics
        val startMode = mode
        Log.d("HyperSS", "startSession: projectId=$projectId mode=$mode startMode=$startMode stepDp=$stepDp loopCount=$loopCount")
        captureFrames = 0
        CaptureController.start(
            projectId, startMode, metrics.heightPixels, stepDp, metrics.densityDpi.toFloat(),
        )
        Settings.lastProjectId = projectId
        ui.update { it.copy(status = getString(R.string.float_status_stitching)) }
    }

    private fun pauseSession() {
        CaptureController.userPaused = true
        CaptureController.pause("user")
    }

    private fun resumeSession() {
        CaptureController.userPaused = false
        CaptureController.resume()
        startCaptureThread()
    }

    private fun exitToolbar() {
        saveCurrentPoints()
        when (val session = CaptureController.state.value) {
            is CaptureController.State.Active,
            is CaptureController.State.Paused -> CaptureController.finish()
            is CaptureController.State.Preparing -> CaptureController.reset()
            else -> Unit
        }
        stopSelf()
    }

    private fun saveCurrentPoints() {
        if (projectId > 0 && mode == CaptureMode.CALIBRATED_DISTANCE) {
            val pts = points.value.map { it.x to it.y }
            Settings.setProjectPoints(projectId, pts)
        }
    }

    // ---------- 会话状态 ----------

    private fun defaultStatus(session: CaptureController.State, pointCount: Int = 0): String = when (session) {
        is CaptureController.State.Idle ->
            if (mode == CaptureMode.CALIBRATED_DISTANCE && pointCount < 2) {
                if (pointCount == 0) getString(R.string.float_add_icon) else getString(R.string.float_add_one_more)
            } else {
                getString(R.string.float_status_ready)
            }
        is CaptureController.State.Preparing -> getString(R.string.float_status_preparing)
        is CaptureController.State.Active -> getString(R.string.float_status_stitching)
        is CaptureController.State.Paused -> getString(R.string.float_status_paused)
        is CaptureController.State.Finished -> getString(R.string.float_status_finished)
        is CaptureController.State.Failed -> getString(R.string.float_status_failed)
    }

    private fun observeController() {
        scope.launch {
            CaptureController.state.collect { state ->
                mainHandler.post {
                    when (state) {
                        is CaptureController.State.Active -> {
                            if (!running.get()) {
                                running.set(true)
                                startCaptureThread()
                            }
                            ui.update { it.copy(status = getString(R.string.float_status_stitching)) }
                        }
                        is CaptureController.State.Paused -> {
                            running.set(false)
                            ui.update { it.copy(status = getString(R.string.float_status_paused)) }
                        }
                        is CaptureController.State.Finished -> {
                            running.set(false)
                            ui.update { it.copy(status = getString(R.string.float_status_finished)) }
                        }
                        is CaptureController.State.Failed -> {
                            running.set(false)
                            Log.e("HyperSS", "Capture failed: ${state.message}")
                            ui.update { it.copy(status = getString(R.string.float_status_failed)) }
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    // ---------- 捕获循环 ----------

    private fun startCaptureThread() {
        if (captureThread?.isAlive == true) return
        if (CaptureController.state.value !is CaptureController.State.Active) return
        captureThread = Thread({ captureLoop() }, "hyperss-toolbar-loop").apply {
            isDaemon = true
            start()
        }
    }

    private fun captureLoop() {
        when (mode) {
            CaptureMode.CALIBRATED_DISTANCE -> captureLoopIcon()
            CaptureMode.MANUAL -> captureLoopManual()
            else -> captureLoopAuto()
        }
    }

    /** 长截图（原自动滚动）：自动滚动 + 拼接；达到项目设定的张数上限（默认 10）
     *  立即收尾保存，上限优先于循环次数。 */
    private fun captureLoopAuto() {
        val maxFrames = Settings.getProjectMaxFrames(projectId)
        Log.d("HyperSS", "captureLoopAuto: maxFrames=$maxFrames loopCount=$loopCount frames(已拼)=${captureFrames}")
        while (running.get()) {
            val state = CaptureController.state.value
            if (state !is CaptureController.State.Active) {
                Thread.sleep(150)
                continue
            }
            // 张数上限优先：达到即收尾（循环次数只可能更早停止）
            if (captureFrames >= maxFrames || (loopCount > 0 && captureFrames >= loopCount)) {
                CaptureController.finish()
                running.set(false)
                return
            }
            val bitmap = takeScreenshotBlocking()
            if (bitmap == null) {
                Thread.sleep(150)
                continue
            }
            val png = bitmapToPng(bitmap)
            bitmap.recycle()
            if (png == null) {
                Thread.sleep(150)
                continue
            }
            // 低置信度→Paused（用户可恢复/退出保存）；内容到底/安全上限→已收尾
            val ok = when (mode) {
                CaptureMode.FIXED_STEP -> CaptureController.appendFixed(png)
                else -> CaptureController.appendDetected(png)
            }
            if (!ok) {
                running.set(false)
                return
            }
            captureFrames++
            ui.update { it.copy(status = getString(R.string.float_status_stitching_count, captureFrames, maxFrames)) }

            val provider = CaptureAccessibilityService.instance ?: run {
                Thread.sleep(200)
                continue
            }
            val scrollDist = (provider.screenHeightPx() * 0.6f).toInt()
            if (!provider.dispatchScroll(scrollDist)) {
                Thread.sleep(200)
                continue
            }
            Thread.sleep(provider.scrollSettleMs())
        }
    }

    /** 手动滚动流程（每一步倒计时均显示）：
     *  ① 立即截一张图并拼接；
     *  ② 等待用户开始滑动——30s 无任何滑动则自动保存退出（显示倒计时）；
     *  ③ 检测到滑动后等待停止——停止满 5s（显示倒计时）自动截下一帧；
     *  ④ 回到 ②，直到达到张数上限（默认 10）收尾保存。
     *  单帧拼接失败（画面跳变/重叠不足）跳过该帧继续，不终止会话。 */
    private fun captureLoopManual() {
        val maxFrames = Settings.getProjectMaxFrames(projectId)
        Log.d("HyperSS", "captureLoopManual: maxFrames=$maxFrames frames(已拼)=${captureFrames}")
        while (running.get()) {
            val state = CaptureController.state.value
            if (state !is CaptureController.State.Active) {
                Thread.sleep(150)
                continue
            }
            if (captureFrames >= maxFrames) {
                ui.update { it.copy(status = getString(R.string.float_limit_reached, maxFrames)) }
                CaptureController.finish()
                running.set(false)
                return
            }
            // ① 截图并拼接当前画面
            val bitmap = takeScreenshotBlocking()
            if (bitmap == null) {
                Thread.sleep(150)
                continue
            }
            val png = bitmapToPng(bitmap)
            bitmap.recycle()
            if (png == null) {
                Thread.sleep(150)
                continue
            }
            if (!CaptureController.appendDetected(png)) {
                val st = CaptureController.state.value
                if (st is CaptureController.State.Failed || st is CaptureController.State.Finished) {
                    running.set(false)
                    return
                }
                // 低置信度被置为 Paused：恢复会话、跳过该帧，等待下一次滑动
                Log.d("HyperSS", "captureLoopManual: 本帧拼接跳过（画面跳变/重叠不足），继续等待")
                CaptureController.resume()
                ui.update { it.copy(status = getString(R.string.float_jump_skipped)) }
                if (!waitManualScroll(maxFrames)) return
                continue
            }
            captureFrames++
            Log.d("HyperSS", "captureLoopManual: 已拼接第 $captureFrames 帧")
            if (captureFrames >= maxFrames) {
                ui.update { it.copy(status = getString(R.string.float_limit_reached, maxFrames)) }
                CaptureController.finish()
                running.set(false)
                return
            }
            // ②③ 等待滑动开始 → 停止 → 5s 倒计时
            if (!waitManualScroll(maxFrames)) return
            Log.d("HyperSS", "captureLoopManual: 滑动停止 5s，截第 ${captureFrames + 1} 帧")
        }
    }

    /** 手动滚动的等待阶段：
     *  - 30s 内未检测到滑动 → 自动保存收尾，返回 false；
     *  - 检测到滑动后停止满 5s → 返回 true（可截下一帧）；
     *  - 暂停/线程退出 → 返回 false。所有倒计时在工具条上显示。 */
    private fun waitManualScroll(maxFrames: Int): Boolean {
        val waitStart = System.currentTimeMillis()
        var scrollSeen = false
        while (running.get() && CaptureController.state.value is CaptureController.State.Active) {
            val now = System.currentTimeMillis()
            val lastScroll = CaptureAccessibilityService.lastScrollEventAt
            if (lastScroll >= waitStart) {
                scrollSeen = true
            }
            if (!scrollSeen) {
                // 尚未检测到滑动：30s 倒计时，超时自动保存退出
                val idleRemain = SCROLL_WAIT_TIMEOUT_MS - (now - waitStart)
                if (idleRemain <= 0) {
                    Log.d("HyperSS", "waitManualScroll: 30s 未滑动，自动保存退出")
                    ui.update { it.copy(status = getString(R.string.float_wait_idle)) }
                    CaptureController.finish()
                    running.set(false)
                    return false
                }
                ui.update {
                    it.copy(status = getString(R.string.float_wait_scroll, captureFrames, maxFrames, (idleRemain + 999) / 1000))
                }
            } else {
                // 已滑动：等待停止，停止满 5s 截下一帧
                val remain = SCROLL_IDLE_MS - (now - lastScroll)
                if (remain <= 0) {
                    return true
                }
                ui.update {
                    it.copy(status = getString(R.string.float_wait_capture, captureFrames, maxFrames, (remain + 999) / 1000))
                }
            }
            Thread.sleep(300)
        }
        return false
    }

    /** 图标滑动模式：逐帧独立截图保存（**不拼接**）。
     *  循环：截图 → 按相邻图标点的完整间距滑动 → 截图 → ……
     *  结束判定：连续相似（到达底部 / 上下回弹，前 3 步不判定）或达到循环次数上限。 */
    private fun captureLoopIcon() {
        Log.d("HyperSS", "captureLoopIcon: points=${points.value.size}")
        val segments = points.value.zipWithNext()
        if (segments.isEmpty()) {
            running.set(false)
            return
        }
        Log.d("HyperSS", "captureLoopIcon: segments=${segments.size}（完整点间距滑动，逐帧独立保存）")
        var prevFrame: Bitmap? = null
        var segIndex = 0
        var steps = 0
        var similarStreak = 0
        while (running.get()) {
            val state = CaptureController.state.value
            if (state !is CaptureController.State.Active) {
                Thread.sleep(150)
                continue
            }
            if (loopCount > 0 && steps >= loopCount) {
                CaptureController.finishManual()
                running.set(false)
                prevFrame?.recycle()
                return
            }
            val provider = CaptureAccessibilityService.instance ?: run {
                Thread.sleep(200)
                continue
            }
            // ① 截图（悬浮元素已临时隐藏）
            val bitmap = takeScreenshotBlocking()
            Log.d("HyperSS", "captureLoopIcon: screenshot taken=${bitmap != null}")
            if (bitmap == null) {
                Thread.sleep(150)
                continue
            }
            val currFrame = downscaleForCompare(bitmap)
            val png = bitmapToPng(bitmap)
            bitmap.recycle()
            // 结束判定：需连续 2 帧都达到重复率阈值（真正到底/回弹）才结束；
            // 单帧相似可能只是滚动偏慢或内容懒加载的瞬时状态，不应终止循环。
            // 前 3 步跳过相似度检测，确保至少采集 3 帧后再判定。
            val duplicateRate = Settings.duplicateRate / 100f
            val similar = steps >= 3 && prevFrame != null && framesSimilar(prevFrame, currFrame, duplicateRate)
            similarStreak = if (similar) similarStreak + 1 else 0
            Log.d("HyperSS", "captureLoopIcon: step=$steps similar=$similar streak=$similarStreak dupRate=${Settings.duplicateRate} state=${CaptureController.state.value}")
            if (similarStreak >= 2) {
                Log.d("HyperSS", "captureLoopIcon: 连续无变化，到达底部或界面回弹，结束")
                prevFrame?.recycle()
                currFrame.recycle()
                CaptureController.finishManual()
                running.set(false)
                return
            }
            prevFrame?.recycle()
            prevFrame = currFrame
            // ② 当前帧独立落库（不拼接）
            if (png == null) {
                Log.d("HyperSS", "captureLoopIcon: PNG 编码失败，跳过该帧")
                Thread.sleep(150)
                continue
            }
            CaptureController.saveManualFrame(png)
            steps++
            // ③ 按图标完整间距滑动（起点→终点整个线段，不拆分）
            val seg = segments[segIndex % segments.size]
            segIndex++
            mainHandler.post {
                rootView?.visibility = View.INVISIBLE
                CaptureAccessibilityService.instance?.setArrowOverlayVisible(false)
                for ((_, view) in badgeViews) { runCatching { wm.removeView(view) } }
                badgeViews.clear()
            }
            Thread.sleep(150)
            val swipeOk = provider.dispatchSwipe(seg.first.x, seg.first.y, seg.second.x, seg.second.y)
            val dist = kotlin.math.sqrt(
                (seg.second.x - seg.first.x) * (seg.second.x - seg.first.x) +
                    (seg.second.y - seg.first.y) * (seg.second.y - seg.first.y),
            ).toInt()
            Log.d("HyperSS", "captureLoopIcon: swipe seg=(${seg.first.x.toInt()},${seg.first.y.toInt()})->(${seg.second.x.toInt()},${seg.second.y.toInt()}) dist=${dist}px ok=$swipeOk")
            if (!swipeOk) {
                mainHandler.post { syncBadgeWindows(); showOverlay() }
                Thread.sleep(200)
                continue
            }
            // 滑动结束后立即显示工具条，留出可点击「暂停」的窗口（滑动与截屏时再临时隐藏）
            mainHandler.post { rootView?.visibility = View.VISIBLE }
            Thread.sleep(provider.scrollSettleMs())
            mainHandler.post { syncBadgeWindows(); showOverlay() }
            Thread.sleep(80)
        }
        prevFrame?.recycle()
    }

    private fun takeScreenshotBlocking(): Bitmap? {
        hideOverlay()
        Thread.sleep(80)
        try {
            val provider = CaptureAccessibilityService.instance ?: return null
            val latch = java.util.concurrent.CountDownLatch(1)
            var result: Bitmap? = null
            mainHandler.post {
                provider.takeScreenshot { bmp ->
                    result = bmp
                    latch.countDown()
                }
            }
            latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
            return result
        } finally {
            showOverlay()
        }
    }

    private fun hideOverlay() {
        mainHandler.post {
            rootView?.visibility = View.INVISIBLE
            CaptureAccessibilityService.instance?.setArrowOverlayVisible(false)
            for ((_, view) in badgeViews) {
                view.visibility = View.INVISIBLE
            }
        }
    }

    private fun showOverlay() {
        mainHandler.post {
            rootView?.visibility = View.VISIBLE
            CaptureAccessibilityService.instance?.setArrowOverlayVisible(true)
            for ((_, view) in badgeViews) {
                view.visibility = View.VISIBLE
            }
        }
    }

    private fun appIconBitmap(): ImageBitmap? {
        return runCatching {
            val drawable = packageManager.getApplicationIcon(packageName)
            val bmp = if (drawable is BitmapDrawable) {
                drawable.bitmap
            } else {
                Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also { b ->
                    val canvas = android.graphics.Canvas(b)
                    drawable.setBounds(0, 0, 96, 96)
                    drawable.draw(canvas)
                }
            }
            bmp.asImageBitmap()
        }.getOrNull()
    }

    private fun bitmapToPng(bitmap: Bitmap): ByteArray? {
        val out = ByteArrayOutputStream()
        return try {
            if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) out.toByteArray() else null
        } catch (e: Throwable) {
            null
        }
    }

    /** 缩放为小图用于帧间相似度比较（降低开销）。 */
    private fun downscaleForCompare(bitmap: Bitmap): Bitmap {
        val w = 200
        val h = (bitmap.height * w / bitmap.width).coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    /**
     * 判断两帧是否「重复率达到阈值」（内容未滚动）。用于结束判定：
     * 到达底部（界面不再变化）或上下回弹（滑动后回到原位）即返回 true。
     * @param duplicateRate 相同像素占比阈值（0..1），达到即判定为重复。
     */
    private fun framesSimilar(a: Bitmap, b: Bitmap, duplicateRate: Float): Boolean {
        val w = a.width
        val h = a.height
        if (b.width != w || b.height != h) return false
        val pa = IntArray(w * h)
        val pb = IntArray(w * h)
        a.getPixels(pa, 0, w, 0, 0, w, h)
        b.getPixels(pb, 0, w, 0, 0, w, h)
        var same = 0
        val total = w * h
        for (i in 0 until total) {
            val ca = pa[i]
            val cb = pb[i]
            val dr = (ca shr 16 and 0xFF) - (cb shr 16 and 0xFF)
            val dg = (ca shr 8 and 0xFF) - (cb shr 8 and 0xFF)
            val db = (ca and 0xFF) - (cb and 0xFF)
            if (dr * dr + dg * dg + db * db <= 900) same++
        }
        val sameFraction = same.toFloat() / total
        return sameFraction >= duplicateRate
    }
}
