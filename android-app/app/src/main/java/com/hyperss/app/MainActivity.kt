package com.hyperss.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import com.hyperss.app.ui.AppNavHost
import com.hyperss.app.ui.theme.HyperSSTheme
import com.hyperss.app.util.LocaleHelper
import com.hyperss.app.util.Settings
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
            }
        }
    }
}