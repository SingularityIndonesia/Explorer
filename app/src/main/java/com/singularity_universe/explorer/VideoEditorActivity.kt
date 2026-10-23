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
import com.singularity_universe.explorer.ui.VideoEditorScreen
import com.singularity_universe.explorer.ui.VideoEditorViewModel
import com.singularity_universe.explorer.ui.theme.ExplorerTheme

/** Trims a video on this device and crops its frame, saving the result as MP4. */
class VideoEditorActivity : ComponentActivity() {
    private val path: String? get() = intent.getStringExtra(EXTRA_PATH)

    private val vm: VideoEditorViewModel by viewModels {
        viewModelFactory { initializer { VideoEditorViewModel(application, path!!) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (path == null) return finish()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            ExplorerTheme {
                VideoEditorScreen(
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

    override fun onStop() {
        // Saving keeps going in the background; only the preview pauses.
        if (path != null) vm.player.pause()
        super.onStop()
    }

    companion object {
        private const val EXTRA_PATH = "path"
        const val EXTRA_SAVED = "saved"

        fun intent(context: Context, path: String): Intent =
            Intent(context, VideoEditorActivity::class.java).putExtra(EXTRA_PATH, path)
    }
}
