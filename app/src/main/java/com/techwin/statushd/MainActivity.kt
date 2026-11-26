package com.techwin.statushd

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.techwin.statushd.ui.StatusHdApp
import com.techwin.statushd.ui.theme.AppearanceViewModel
import com.techwin.statushd.ui.theme.StatusHDTheme
import com.techwin.statushd.ui.theme.resolveDarkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val appearanceViewModel: AppearanceViewModel = viewModel()
            val themeMode by appearanceViewModel.themeMode.collectAsStateWithLifecycle()
            val darkTheme = resolveDarkTheme(
                mode = themeMode,
                systemIsDark = isSystemInDarkTheme()
            )
            StatusHDTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    StatusHdApp(appearanceViewModel = appearanceViewModel)
                }
            }
        }
    }
}
