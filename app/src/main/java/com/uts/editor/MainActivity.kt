package com.uts.editor

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uts.editor.data.AppSettings
import com.uts.editor.ui.AppRoot
import com.uts.editor.ui.theme.UtsTheme
import com.uts.editor.util.LocaleManager
import com.uts.editor.viewmodel.EditorViewModel

class MainActivity : ComponentActivity() {

    private var viewModel: EditorViewModel? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val vm: EditorViewModel = viewModel()
            viewModel = vm
            val settings by vm.settingsStore.settings.collectAsState(initial = AppSettings())
            UtsTheme(themeMode = settings.theme) {
                AppRoot(
                    viewModel = vm,
                    settings = settings,
                    onLanguageApplied = { recreate() },
                )
            }
        }

        // Open a file the app was launched with (VIEW/EDIT/SEND).
        if (savedInstanceState == null) {
            // Defer until the ViewModel exists.
            window.decorView.post { viewModel?.handleViewIntent(intent) }
        }
    }

    override fun onStop() {
        super.onStop()
        // Flush a recovery snapshot whenever we leave the foreground.
        viewModel?.autosaveNow()
    }
}
