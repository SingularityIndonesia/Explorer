package com.singularity_universe.explorer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.singularity_universe.explorer.ui.ImageEditorScreen
import com.singularity_universe.explorer.ui.ImageEditorViewModel
import com.singularity_universe.explorer.ui.theme.ExplorerTheme

/** Edits a picture on this device: crop and rotate, draw, highlight and speech balloons. */
class ImageEditorActivity : ComponentActivity() {
    private val path: String? get() = intent.getStringExtra(EXTRA_PATH)

    private val vm: ImageEditorViewModel by viewModels {
        viewModelFactory { initializer { ImageEditorViewModel(application, path!!) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (path == null) return finish()
        // The editor is always dark, like the viewer.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            ExplorerTheme {
                ImageEditorScreen(
                    vm = vm,
                    onClose = ::finish,
                    onSaved = { file ->
                        Toast.makeText(this, "Saved ${file.name}", Toast.LENGTH_SHORT).show()
                        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_SAVED, file.path))
                        finish()
                    },
                    onError = { Toast.makeText(this, it, Toast.LENGTH_LONG).show() },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_PATH = "path"

        /** The saved file's path, in the result. */
        const val EXTRA_SAVED = "saved"

        fun intent(context: Context, path: String): Intent =
            Intent(context, ImageEditorActivity::class.java).putExtra(EXTRA_PATH, path)
    }
}
