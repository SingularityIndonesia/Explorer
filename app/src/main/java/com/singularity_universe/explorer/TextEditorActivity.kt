package com.singularity_universe.explorer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.singularity_universe.explorer.ui.TextEditorScreen
import com.singularity_universe.explorer.ui.TextEditorViewModel
import com.singularity_universe.explorer.ui.theme.ExplorerTheme

/**
 * Edits a text file on this device. Changes are saved automatically: a moment after typing stops,
 * and whenever the editor is left.
 */
class TextEditorActivity : ComponentActivity() {
    private val path: String? get() = intent.getStringExtra(EXTRA_PATH)

    private val vm: TextEditorViewModel by viewModels {
        viewModelFactory { initializer { TextEditorViewModel(application, path!!) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (path == null) return finish()
        enableEdgeToEdge()
        setContent {
            ExplorerTheme {
                TextEditorScreen(vm, onBack = ::finish)
            }
        }
    }

    override fun onStop() {
        // Leaving the editor (Back, Home, another app) never loses text.
        if (path != null) vm.saveNow()
        super.onStop()
    }

    companion object {
        private const val EXTRA_PATH = "path"

        fun start(context: Context, path: String) {
            context.startActivity(
                Intent(context, TextEditorActivity::class.java).putExtra(EXTRA_PATH, path)
            )
        }
    }
}
