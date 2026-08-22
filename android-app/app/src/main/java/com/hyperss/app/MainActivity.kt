package com.hyperss.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import com.hyperss.app.ui.AppNavHost
import com.hyperss.app.ui.UpdateAvailableDialog
import com.hyperss.app.ui.theme.HyperSSTheme
import com.hyperss.app.util.LocaleHelper
import com.hyperss.app.util.Settings
import com.hyperss.app.util.UpdateChecker
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themePref = remember { Settings.theme }
            HyperSSTheme(darkTheme = when (themePref) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }) {
                val navigationEventDispatcherOwner = rememberNavigationEventDispatcherOwner(parent = null)
                CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides navigationEventDispatcherOwner) {
                    AppNavHost()
                    MiuixPopupUtils.MiuixPopupHost()
                }

                // 启动自动检查更新：仅当有新版本且未被「稍后」跳过时弹窗（结果由 HyperSSApp 后台发布）。
                val startupUpdate by UpdateChecker.startupResult.collectAsState()
                if (startupUpdate is UpdateChecker.Result.UpdateAvailable) {
                    val found = startupUpdate as UpdateChecker.Result.UpdateAvailable
                    UpdateAvailableDialog(
                        newVersion = found.version,
                        currentVersion = BuildConfig.VERSION_NAME,
                        notes = found.notes,
                        onDismiss = {
                            Settings.updateSkipVersion = found.version
                            UpdateChecker.publishStartupResult(null)
                        },
                    )
                }
            }
        }
    }
}