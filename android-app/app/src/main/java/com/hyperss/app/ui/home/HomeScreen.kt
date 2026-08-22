package com.hyperss.app.ui.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hyperss.app.R
import com.hyperss.app.service.FloatingToolbarService
import com.hyperss.app.ui.BlurTopBar
import com.hyperss.app.ui.ambientGlassBackground
import com.hyperss.app.ui.GlassFloatingButton
import com.hyperss.app.ui.GlassSurface
import com.hyperss.app.ui.rememberTopBarBlurFraction
import com.hyperss.app.ui.theme.Danger
import com.hyperss.app.ui.theme.LightTextSecondary
import com.hyperss.app.ui.theme.MiuixBlue
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import com.hyperss.app.util.Permissions
import com.hyperss.app.util.Settings
import uniffi.hyperss_core.CaptureMode
import uniffi.hyperss_core.ProjectInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun generatePrefix(name: String): String {
    val sb = StringBuilder()
    for (ch in name) {
        when {
            ch in '\u4e00'..'\u9fff' -> {
                val initial = PinyinUtil.getFirstLetter(ch)
                if (initial.isNotEmpty()) sb.append(initial)
            }
            ch.isLetterOrDigit() -> sb.append(ch.lowercaseChar())
            else -> { }
        }
    }
    return sb.toString().take(8).ifEmpty { "proj" }
}

private object PinyinUtil {
    private val PINYIN_MAP: Map<Char, String> by lazy {
        mapOf(
            '\u6211' to "wo", '\u4f60' to "ni", '\u4ed6' to "ta", '\u5b83' to "ta",
            '\u8fd9' to "zhe", '\u90a3' to "na", '\u4ec0' to "shen", '\u4e48' to "me",
            '\u597d' to "hao", '\u662f' to "shi", '\u7684' to "de", '\u5427' to "ba",
            '\u4e86' to "le", '\u5417' to "ma", '\u54ea' to "na", '\u513f' to "er",
            '\u5927' to "da", '\u4e2d' to "zhong", '\u56fd' to "guo", '\u4eba' to "ren",
            '\u5927' to "da", '\u5c0f' to "xiao", '\u591a' to "duo", '\u5c11' to "shao",
            '\u5929' to "tian", '\u5730' to "di", '\u65e5' to "ri", '\u6708' to "yue",
            '\u5e74' to "nian", '\u4eca' to "jin", '\u660e' to "ming", '\u53bb' to "qu",
            '\u6765' to "lai", '\u4e0a' to "shang", '\u4e0b' to "xia", '\u5de6' to "zuo",
            '\u53f3' to "you", '\u524d' to "qian", '\u540e' to "hou", '\u91cc' to "li",
            '\u5916' to "wai", '\u5185' to "nei", '\u4e1c' to "dong", '\u897f' to "xi",
            '\u5357' to "nan", '\u5317' to "bei", '\u957f' to "chang", '\u77ed' to "duan",
            '\u9ad8' to "gao", '\u4f4e' to "di", '\u65b0' to "xin", '\u65e7' to "jiu",
            '\u5feb' to "kuai", '\u6162' to "man", '\u6253' to "da", '\u5f00' to "kai",
            '\u5173' to "guan", '\u7528' to "yong", '\u505a' to "zuo", '\u770b' to "kan",
            '\u542c' to "ting", '\u8bf4' to "shuo", '\u8bfb' to "du", '\u5199' to "xie",
            '\u5b57' to "zi", '\u6587' to "wen", '\u5355' to "dan", '\u53cc' to "shuang",
            '\u957f' to "chang", '\u62fc' to "pin", '\u63a5' to "jie", '\u56fe' to "tu",
            '\u7247' to "pian", '\u7167' to "zhao", '\u622a' to "jie", '\u5c4f' to "ping",
            '\u6eda' to "gun", '\u52a8' to "dong", '\u81ea' to "zi", '\u7531' to "you",
            '\u624b' to "shou", '\u52a8' to "dong", '\u5b9a' to "ding", '\u957f' to "chang",
            '\u6b65' to "bu", '\u8ddd' to "ju", '\u70b9' to "dian", '\u9009' to "xuan",
            '\u62d6' to "tuo", '\u6296' to "dou", '\u5f2f' to "wan", '\u66f2' to "qu",
            '\u76f4' to "zhi", '\u6298' to "zhe", '\u6ce8' to "zhu", '\u610f' to "yi",
            '\u4e49' to "yi", '\u540d' to "ming", '\u79f0' to "cheng", '\u53f7' to "hao",
            '\u7b80' to "jian", '\u7e41' to "fan", '\u7b80' to "jian", '\u7269' to "wu",
            '\u4ef6' to "jian", '\u90e8' to "bu", '\u5206' to "fen", '\u7c7b' to "lei",
            '\u7cfb' to "xi", '\u7edf' to "tong", '\u8bbe' to "she", '\u8ba1' to "ji",
            '\u7b97' to "suan", '\u8fc7' to "guo", '\u901a' to "tong", '\u8fde' to "lian",
            '\u63a5' to "jie", '\u65ad' to "duan", '\u7ee7' to "ji", '\u627f' to "cheng",
            '\u8f6c' to "zhuan", '\u53d1' to "fa", '\u73b0' to "xian", '\u70b9' to "dian",
            '\u89e6' to "chu", '\u6ed1' to "hua", '\u5c4f' to "ping", '\u853d' to "bi",
            '\u6697' to "an", '\u4eae' to "liang", '\u8272' to "se", '\u5f69' to "cai",
            '\u56fe' to "tu", '\u50cf' to "xiang", '\u753b' to "hua", '\u62cd' to "pai",
            '\u6444' to "she", '\u5f55' to "lu", '\u97f3' to "yin", '\u89c6' to "shi",
            '\u9891' to "pin", '\u505c' to "ting", '\u7a0b' to "cheng", '\u5e8f' to "xu",
            '\u5217' to "lie", '\u8868' to "biao", '\u683c' to "ge", '\u7a97' to "chuang",
            '\u53e3' to "kou", '\u95e8' to "men", '\u7a97' to "chuang", '\u95f4' to "jian",
            '\u901a' to "tong", '\u9053' to "dao", '\u8def' to "lu", '\u8857' to "jie",
            '\u533a' to "qu", '\u57df' to "yu", '\u5730' to "di", '\u5740' to "zhi",
            '\u5c31' to "jiu", '\u662f' to "shi", '\u5426' to "fou", '\u5f53' to "dang",
            '\u7136' to "ran", '\u540e' to "hou", '\u5148' to "xian", '\u65e9' to "zao",
            '\u665a' to "wan", '\u5feb' to "kuai", '\u6162' to "man", '\u65e9' to "zao",
            '\u591c' to "ye", '\u5348' to "wu", '\u6668' to "chen", '\u58f0' to "sheng",
            '\u97f3' to "yin", '\u8272' to "se", '\u5f69' to "cai", '\u7ea2' to "hong",
            '\u7eff' to "lv", '\u84dd' to "lan", '\u9ec4' to "huang", '\u767d' to "bai",
            '\u9ed1' to "hei", '\u7070' to "hui", '\u7c89' to "fen", '\u7d2b' to "zi",
            '\u91d1' to "jin", '\u94f6' to "yin", '\u94c1' to "tie", '\u94dc' to "tong",
            '\u538b' to "ya", '\u8f6f' to "ruan", '\u786c' to "ying", '\u8f7b' to "qing",
            '\u91cd' to "zhong", '\u5927' to "da", '\u5c0f' to "xiao", '\u957f' to "chang",
            '\u77ed' to "duan", '\u7c97' to "cu", '\u7ec6' to "xi", '\u5bbd' to "kuan",
            '\u7a84' to "zhai", '\u6df1' to "shen", '\u6d45' to "qian", '\u539a' to "hou",
            '\u8584' to "bao", '\u5feb' to "kuai", '\u6162' to "man", '\u65e9' to "zao",
            '\u665a' to "wan", '\u65b0' to "xin", '\u65e7' to "jiu", '\u5e74' to "nian",
            '\u6708' to "yue", '\u65e5' to "ri", '\u65f6' to "shi", '\u95f4' to "jian",
            '\u7a7a' to "kong", '\u6ee1' to "man", '\u5c11' to "shao", '\u591a' to "duo",
            '\u6709' to "you", '\u65e0' to "wu", '\u6709' to "you", '\u6ca1' to "mei",
            '\u524d' to "qian", '\u540e' to "hou", '\u5de6' to "zuo", '\u53f3' to "you",
            '\u4e0a' to "shang", '\u4e0b' to "xia", '\u91cc' to "li", '\u5916' to "wai",
            '\u5185' to "nei", '\u4e2d' to "zhong", '\u8fb9' to "bian", '\u89d2' to "jiao",
            '\u6838' to "he", '\u5fc3' to "xin", '\u5b9e' to "shi", '\u540d' to "ming",
            '\u5b57' to "zi", '\u53f7' to "hao", '\u6570' to "shu", '\u5b57' to "zi",
            '\u7b2c' to "di", '\u521d' to "chu", '\u672b' to "mo", '\u59cb' to "shi",
            '\u7ec8' to "zhong", '\u603b' to "zong", '\u5206' to "fen", '\u96c6' to "ji",
            '\u5408' to "he", '\u5e76' to "bing", '\u5206' to "fen", '\u5f00' to "kai",
            '\u5173' to "guan", '\u542f' to "qi", '\u505c' to "ting", '\u7ee7' to "ji",
            '\u7eed' to "xu", '\u91cd' to "chong", '\u65b0' to "xin", '\u521b' to "chuang",
            '\u5efa' to "jian", '\u8bbe' to "she", '\u914d' to "pei", '\u7f6e' to "zhi",
            '\u5b89' to "an", '\u88c5' to "zhuang", '\u5378' to "xie", '\u8f7d' to "zai",
            '\u8fd0' to "yun", '\u8f93' to "shu", '\u9001' to "song", '\u53d6' to "qu",
            '\u6536' to "shou", '\u5b58' to "cun", '\u50a8' to "chu", '\u8bfb' to "du",
            '\u5199' to "xie", '\u5220' to "shan", '\u9664' to "chu", '\u6dfb' to "tian",
            '\u52a0' to "jia", '\u51cf' to "jian", '\u6539' to "gai", '\u4fee' to "xiu",
            '\u66f4' to "geng", '\u66ff' to "ti", '\u6362' to "huan", '\u8c03' to "tiao",
            '\u8bd5' to "shi", '\u68c0' to "jian", '\u9a8c' to "yan", '\u6d4b' to "ce",
            '\u6821' to "xiao", '\u5bf9' to "dui", '\u6bd4' to "bi", '\u8f83' to "jiao",
            '\u901a' to "tong", '\u8fc7' to "guo", '\u5230' to "dao", '\u8fbe' to "da",
            '\u8f6c' to "zhuan", '\u56de' to "hui", '\u8fd4' to "fan", '\u5165' to "ru",
            '\u51fa' to "chu", '\u8fdb' to "jin", '\u9000' to "tui", '\u8df3' to "tiao",
            '\u8dd1' to "pao", '\u8d70' to "zou", '\u884c' to "xing", '\u7acb' to "li",
            '\u5750' to "zuo", '\u5367' to "wo", '\u4f11' to "xiu", '\u606f' to "xi",
            '\u73b0' to "xian", '\u51fa' to "chu", '\u73b0' to "xian", '\u663e' to "xian",
            '\u8868' to "biao", '\u8fbe' to "da", '\u901a' to "tong", '\u8fc7' to "guo",
            '\u8fde' to "lian", '\u63a5' to "jie", '\u7ec4' to "zu", '\u5408' to "he",
            '\u96c6' to "ji", '\u56e2' to "tuan", '\u961f' to "dui", '\u7fa4' to "qun",
            '\u7ec4' to "zu", '\u4ef6' to "jian", '\u6761' to "tiao", '\u6863' to "dang",
            '\u5757' to "kuai", '\u7247' to "pian", '\u533a' to "qu", '\u57df' to "yu",
            '\u5730' to "di", '\u5740' to "zhi", '\u7801' to "ma", '\u53f7' to "hao",
            '\u5e45' to "fu", '\u5bbd' to "kuan", '\u9ad8' to "gao", '\u6df1' to "shen",
            '\u957f' to "chang", '\u8fdc' to "yuan", '\u8fd1' to "jin", '\u5927' to "da",
            '\u5c0f' to "xiao", '\u591a' to "duo", '\u5c11' to "shao", '\u5168' to "quan",
            '\u90e8' to "bu", '\u5206' to "fen", '\u7c7b' to "lei", '\u79cd' to "zhong",
            '\u578b' to "xing", '\u5f0f' to "shi", '\u683c' to "ge", '\u680f' to "lan",
            '\u9879' to "xiang", '\u76ee' to "mu", '\u6807' to "biao", '\u9898' to "ti",
            '\u5185' to "nei", '\u5bb9' to "rong", '\u6587' to "wen", '\u5b57' to "zi",
            '\u56fe' to "tu", '\u7247' to "pian", '\u8868' to "biao", '\u683c' to "ge",
            '\u8bbe' to "she", '\u8ba1' to "ji", '\u5236' to "zhi", '\u4f5c' to "zuo",
            '\u54c1' to "pin", '\u7248' to "ban", '\u672c' to "ben", '\u526f' to "fu",
            '\u521d' to "chu", '\u672b' to "mo", '\u59cb' to "shi", '\u7ec8' to "zhong",
        )
    }

    fun getFirstLetter(ch: Char): String {
        if (ch.isLetter()) return ch.lowercaseChar().toString()
        if (ch.isDigit()) return ch.toString()
        return PINYIN_MAP[ch]?.firstOrNull()?.toString() ?: ""
    }
}

@Composable
fun HomeScreen(
    onOpenProject: (Long) -> Unit,
    onProjectSettings: (Long) -> Unit,
    onSettings: () -> Unit,
    onOpenReader: () -> Unit = {},
    viewModel: HomeViewModel = viewModel(),
) {
    val projects by viewModel.projects.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val error by viewModel.error.collectAsState()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refresh()
    }

    var showNewProject by remember { mutableStateOf(false) }
    var deleteMode by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ProjectInfo?>(null) }
    var pendingStart by remember { mutableStateOf<ProjectInfo?>(null) }
    var showPermissionDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current

    // 顶部动态模糊：内容滚动时毛玻璃标题栏渐入
    val backdrop = rememberLayerBackdrop()
    val gridState = rememberLazyGridState()
    val blurFraction = rememberTopBarBlurFraction(gridState)
    var barHeight by remember { mutableStateOf(0.dp) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (projects.isEmpty() && !busy) {
            EmptyState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = barHeight),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = gridState,
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop).ambientGlassBackground(),
                contentPadding = PaddingValues(
                    top = barHeight + 20.dp,
                    start = 20.dp,
                    end = 20.dp,
                    bottom = 20.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                    items(projects, key = { it.id }) { project ->
                        ProjectCard(
                            project = project,
                            deleteMode = deleteMode,
                            onClick = { if (deleteMode) { deleteMode = false } else onOpenProject(project.id) },
                            onLongPress = { deleteMode = true },
                            onDelete = { pendingDelete = project },
                            onSettings = { onProjectSettings(project.id) },
                            onStart = {
                                if (!Permissions.hasOverlay(context) || !Permissions.hasAccessibility(context)) {
                                    pendingStart = project
                                    showPermissionDialog = true
                                } else {
                                    startCapture(context, project)
                                }
                            },
                        )
                    }
                }
            }

        BlurTopBar(
            backdrop = backdrop,
            fraction = blurFraction,
            modifier = Modifier.align(Alignment.TopCenter),
            onHeightChanged = { barHeight = it },
        ) {
            Header()
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GlassFloatingButton(
                icon = MiuixIcons.File,
                contentDescription = "PDF 阅读器",
                onClick = onOpenReader,
                size = 52.dp,
                background = MiuixBlue,
            )
            GlassFloatingButton(
                icon = MiuixIcons.Settings,
                contentDescription = stringResource(R.string.home_settings),
                onClick = onSettings,
                size = 52.dp,
                background = LightTextSecondary,
            )
            GlassFloatingButton(
                icon = MiuixIcons.Add,
                contentDescription = stringResource(R.string.home_new_project),
                onClick = { showNewProject = true },
                size = 64.dp,
                background = MiuixBlue,
            )
        }
    }

    if (showNewProject) {
        NewProjectDialog(
            onCreate = { name ->
                viewModel.createProject(generatePrefix(name), name) { created ->
                    if (created != null) onProjectSettings(created.id)
                }
                showNewProject = false
            },
            onDismiss = { showNewProject = false },
        )
    }

    pendingDelete?.let { project ->
        OverlayDialog(
            show = true,
            title = stringResource(R.string.delete_project_title),
            summary = stringResource(R.string.delete_project_confirm, project.displayName),
            onDismissRequest = { pendingDelete = null },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = { pendingDelete = null },
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.delete),
                    onClick = {
                        viewModel.deleteProject(project.id) { ok -> if (ok) deleteMode = false }
                        pendingDelete = null
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColors(textColor = Danger),
                )
            }
        }
    }

    if (showPermissionDialog) {
        OverlayDialog(
            show = true,
            title = stringResource(R.string.permission_needed_title),
            summary = stringResource(R.string.permission_needed_message),
            onDismissRequest = { showPermissionDialog = false },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = { showPermissionDialog = false },
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.permission_go),
                    onClick = {
                        showPermissionDialog = false
                        onSettings()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }

    error?.let { msg ->
        OverlayDialog(
            show = true,
            title = stringResource(R.string.error_title),
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

private fun startCapture(context: android.content.Context, project: ProjectInfo) {
    val mode = runCatching {
        CaptureMode.valueOf(Settings.getProjectMode(project.id))
    }.getOrDefault(CaptureMode.AUTO_SCROLL)
    val stepDp = Settings.getProjectStepDp(project.id)
    val loopCount = Settings.getProjectLoopCount(project.id)
    FloatingToolbarService.start(context, project.id, mode, stepDp, loopCount, null)
}

@Composable
private fun Header() {
    Column(modifier = Modifier.padding(start = 20.dp, top = 56.dp, end = 20.dp, bottom = 8.dp)) {
        Text(stringResource(R.string.home_title), style = MiuixTheme.textStyles.headline1)
        Text(
            stringResource(R.string.app_slogan),
            style = MiuixTheme.textStyles.body2,
            color = LightTextSecondary,
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.home_empty_title), style = MiuixTheme.textStyles.title2)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.home_empty_hint),
                style = MiuixTheme.textStyles.body2,
                color = LightTextSecondary,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectCard(
    project: ProjectInfo,
    deleteMode: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onDelete: () -> Unit,
    onSettings: () -> Unit,
    onStart: () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "shake")
    val shake by transition.animateFloat(
        initialValue = -2f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(120), RepeatMode.Reverse),
        label = "shake-rot",
    )
    val rotation = if (deleteMode) shake else 0f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp),
    ) {
        GlassSurface(
            modifier = Modifier
                .fillMaxSize()
                .rotate(rotation)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongPress,
                )
                .padding(14.dp),
            cornerRadius = 20.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        project.displayName,
                        style = MiuixTheme.textStyles.title2,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = 28.dp),
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.project_card_images, project.imageCount),
                        style = MiuixTheme.textStyles.footnote1,
                        color = LightTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val dateStr = remember(project.createdAt) {
                    val date = Date(project.createdAt * 1000)
                    val yearFmt = SimpleDateFormat("yyyy", Locale.getDefault())
                    val monthDayFmt = SimpleDateFormat("MM.dd", Locale.getDefault())
                    val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                    "${yearFmt.format(date)}\n${monthDayFmt.format(date)}\n${timeFmt.format(date)}"
                }
                Text(
                    dateStr,
                    style = MiuixTheme.textStyles.footnote2,
                    color = LightTextSecondary,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
        }
        if (deleteMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(shape = RoundedCornerShape(11.dp), color = Danger) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(22.dp),
                    ) {
                        Text("\u00d7", color = androidx.compose.ui.graphics.Color.White)
                    }
                }
            }
        } else {
            IconButton(
                onClick = onSettings,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(30.dp),
            ) {
                Icon(
                    MiuixIcons.Settings,
                    contentDescription = stringResource(R.string.project_settings),
                    tint = LightTextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(
                onClick = onStart,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp)
                    .size(34.dp),
            ) {
                Icon(
                    MiuixIcons.Play,
                    contentDescription = stringResource(R.string.start_capture),
                    tint = LightTextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun NewProjectDialog(
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()
    val isValid = trimmed.isNotEmpty() && trimmed.all { it.isLetterOrDigit() || it in '\u4e00'..'\u9fff' || it == ' ' }
    val prefix = if (trimmed.isNotEmpty()) generatePrefix(trimmed) else ""
    var dialogOffset by remember { mutableStateOf(Offset.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // 弹窗用高不透明度玻璃面板保证可读性，层次由受光描边表达
            GlassSurface(
                modifier = Modifier
                    .fillMaxWidth(0.86f)
                    .padding(vertical = 24.dp)
                    .graphicsLayer {
                        translationX = dialogOffset.x
                        translationY = dialogOffset.y
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            dialogOffset += dragAmount
                        }
                    },
                cornerRadius = 28.dp,
                background = MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        stringResource(R.string.new_project_title),
                        style = MiuixTheme.textStyles.title2,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextField(
                        value = name,
                        onValueChange = { name = it },
                        label = stringResource(R.string.new_project_name_label),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.new_project_prefix_hint, prefix.ifEmpty { "..." }) +
                            " · " + stringResource(R.string.new_project_name_validation),
                        style = MiuixTheme.textStyles.footnote1,
                        color = LightTextSecondary,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(
                            text = stringResource(R.string.cancel),
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(20.dp))
                        TextButton(
                            text = stringResource(R.string.new_project_create),
                            enabled = isValid,
                            onClick = { onCreate(trimmed) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                }
            }
        }
    }
}
