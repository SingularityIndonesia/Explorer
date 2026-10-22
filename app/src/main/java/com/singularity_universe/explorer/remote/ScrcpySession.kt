package com.singularity_universe.explorer.remote

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.Random

/**
 * Mirrors and controls another Android device, using the scrcpy server (Apache-2.0,
 * github.com/Genymobile/scrcpy) bundled in assets.
 *
 * The server is pushed over the device's existing ADB connection and runs as its `shell`
 * user; video (H.264) and control travel over that same connection, and nothing is installed
 * on the device. A session lives only while the remote control screen shows it.
 */
class ScrcpySession(
    private val context: Context,
    private val devices: AdbDevices,
    private val deviceId: String,
) {
    sealed interface State {
        data object Starting : State
        data object Running : State
        data class Failed(val message: String) : State
        data object Stopped : State
    }

    var state by mutableStateOf<State>(State.Starting)
        private set

    /** Size of the device screen as streamed; touches are mapped onto it. */
    var videoSize by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val controlQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private var server: AdbStream? = null
    private var video: AdbStream? = null
    private var control: AdbStream? = null
    private var decoder: MediaCodec? = null
    private var job: Job? = null
    private var surface: Surface? = null

    /** Starts streaming onto [surface]. Call [stop] when the surface goes away. */
    fun start(surface: Surface) {
        this.surface = surface
        if (job?.isActive == true) return
        state = State.Starting
        job = scope.launch {
            try {
                run(surface)
            } catch (e: Exception) {
                if (isActive) {
                    Log.w(TAG, "Remote control session ended", e)
                    state = State.Failed(e.message ?: "Remote control stopped")
                }
            } finally {
                closeStreams()
            }
        }
    }

    fun stop() {
        if (state is State.Failed) state = State.Stopped
        job?.cancel()
        job = null
        closeStreams()
        if (state !is State.Failed) state = State.Stopped
    }

    /** Reconnects (e.g. through another address) onto the same surface. */
    fun restart() {
        val current = surface ?: return
        stop()
        start(current)
    }

    /** Releases everything; the session can't be restarted afterwards. */
    fun close() {
        stop()
        scope.cancel()
    }

    // ---- Input ----

    /** One finger's down/move/up at ([x], [y]) in video pixels; [pointerId] tells fingers apart. */
    fun touch(action: Int, pointerId: Long, x: Int, y: Int) {
        val (width, height) = videoSize ?: return
        val pressed = action != ACTION_UP
        controlQueue.trySend(
            ByteBuffer.allocate(32)
                .put(TYPE_INJECT_TOUCH_EVENT.toByte())
                .put(action.toByte())
                .putLong(pointerId)
                .putInt(x).putInt(y)
                .putShort(width.toShort()).putShort(height.toShort())
                .putShort(if (pressed) 0xffff.toShort() else 0) // pressure
                .putInt(0) // action button (fingers have none)
                .putInt(0) // buttons
                .array()
        )
    }

    /** Presses and releases an Android key (e.g. back, home, recent apps). */
    fun key(keycode: Int) {
        listOf(ACTION_DOWN, ACTION_UP).forEach { action ->
            controlQueue.trySend(
                ByteBuffer.allocate(14)
                    .put(TYPE_INJECT_KEYCODE.toByte())
                    .put(action.toByte())
                    .putInt(keycode)
                    .putInt(0) // repeat
                    .putInt(0) // meta state
                    .array()
            )
        }
    }

    /** Types text into the focused field on the device. */
    fun text(value: String) {
        value.chunked(MAX_TEXT_LENGTH).forEach { chunk ->
            val bytes = chunk.toByteArray()
            controlQueue.trySend(
                ByteBuffer.allocate(5 + bytes.size)
                    .put(TYPE_INJECT_TEXT.toByte())
                    .putInt(bytes.size)
                    .put(bytes)
                    .array()
            )
        }
    }

    // ---- Session ----

    private suspend fun CoroutineScope.run(surface: Surface) {
        // 1. Push the server (a plain jar; nothing gets installed).
        val jar = File(context.cacheDir, "scrcpy-server.jar")
        if (!jar.exists()) context.assets.open(ASSET).use { input -> jar.outputStream().use { input.copyTo(it) } }
        devices.push(deviceId, jar, DEVICE_JAR)

        // 2. Start it, listening on an abstract socket named after a random session id.
        val scid = Random().nextInt(Int.MAX_VALUE)
        val socket = "localabstract:scrcpy_%08x".format(scid)
        server = devices.openStream(deviceId, "shell:" + serverCommand(scid)).also { stream ->
            launch { drainLog(stream) }
        }

        // 3. Connect video then control (the server accepts them in that order).
        val videoStream = connectWhenReady(socket).also { video = it }
        val videoInput = videoStream.openInputStream()
        videoInput.readExactly(1) // dummy byte: the server is up
        control = devices.openStream(deviceId, socket)
        launch { sendControlMessages() }

        // 4. Stream: codec id, then session headers (size) and frames.
        val codecId = ByteBuffer.wrap(videoInput.readExactly(4)).int
        if (codecId != CODEC_H264) throw IOException("The device couldn't start screen capture")
        state = State.Running
        decode(videoInput, surface)
    }

    private fun serverCommand(scid: Int) = listOf(
        "CLASSPATH=$DEVICE_JAR", "app_process", "/", "com.genymobile.scrcpy.Server", SERVER_VERSION,
        "scid=%08x".format(scid),
        "log_level=info",
        "audio=false",
        "video_codec=h264",
        "max_size=$MAX_SIZE",
        "video_bit_rate=$BIT_RATE",
        "max_fps=60",
        "clipboard_autosync=false",
        "tunnel_forward=true",
        "send_device_meta=false",
        "send_dummy_byte=true",
        "send_stream_meta=true",
        "send_frame_meta=true",
    ).joinToString(" ")

    /**
     * The server needs a moment to start listening: several seconds on its first run after being
     * pushed, while Android compiles it.
     */
    private suspend fun connectWhenReady(socket: String): AdbStream {
        val deadline = System.currentTimeMillis() + SERVER_START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val stream = runCatching { devices.openStream(deviceId, socket) }.getOrNull()
            if (stream != null) return stream
            delay(150)
        }
        throw IOException("Remote control didn't start on the device")
    }

    private suspend fun CoroutineScope.decode(input: InputStream, surface: Surface) {
        val info = MediaCodec.BufferInfo()
        while (isActive) {
            val header = ByteBuffer.wrap(input.readExactly(12))
            val flags = header.getInt(0)
            if ((flags and SESSION_FLAG) != 0) {
                // A new capture session (start, rotation): restart the decoder at the new size.
                val width = header.getInt(4)
                val height = header.getInt(8)
                videoSize = width to height
                restartDecoder(surface, width, height)
                continue
            }
            val ptsAndFlags = header.getLong(0)
            val size = header.getInt(8)
            val packet = input.readExactly(size)
            val codec = decoder ?: continue
            val config = (ptsAndFlags and PACKET_FLAG_CONFIG) != 0L
            val pts = ptsAndFlags and PACKET_PTS_MASK
            queue(codec, packet, if (config) 0 else pts, if (config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0)
            // Render whatever frames are ready, as soon as they are.
            while (true) {
                val index = codec.dequeueOutputBuffer(info, 0)
                if (index >= 0) codec.releaseOutputBuffer(index, true)
                else if (index != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) break
            }
        }
    }

    private fun queue(codec: MediaCodec, data: ByteArray, pts: Long, flags: Int) {
        var index: Int
        do {
            index = codec.dequeueInputBuffer(10_000)
        } while (index < 0)
        codec.getInputBuffer(index)!!.apply { clear(); put(data) }
        codec.queueInputBuffer(index, 0, data.size, pts, flags)
    }

    private fun restartDecoder(surface: Surface, width: Int, height: Int) {
        releaseDecoder()
        decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height), surface, null, 0)
            start()
        }
    }

    private fun releaseDecoder() {
        decoder?.let { runCatching { it.stop() }; runCatching { it.release() } }
        decoder = null
    }

    private suspend fun sendControlMessages() {
        val output = control?.openOutputStream() ?: return
        for (message in controlQueue) {
            output.write(message)
            output.flush()
        }
    }

    /** Reads (and logs) the server's output so it never blocks on a full pipe. */
    private fun drainLog(stream: AdbStream) {
        val input = stream.openInputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val read = try {
                input.read(buffer)
            } catch (_: IOException) {
                -1
            }
            if (read < 0) break
            Log.d(TAG, String(buffer, 0, read).trim())
        }
    }

    private fun closeStreams() {
        listOf(video, control, server).forEach { stream -> runCatching { stream?.close() } }
        video = null
        control = null
        server = null
        releaseDecoder()
    }

    companion object {
        private const val TAG = "ExplorerRemoteControl"
        private const val ASSET = "scrcpy-server.jar"
        private const val DEVICE_JAR = "/data/local/tmp/explorer-scrcpy-server.jar"
        private const val SERVER_VERSION = "5.0.1"
        private const val MAX_SIZE = 1920
        private const val BIT_RATE = 8_000_000
        private const val SERVER_START_TIMEOUT_MS = 20_000L

        private const val CODEC_H264 = 0x68_32_36_34 // "h264"
        private const val SESSION_FLAG = 1 shl 31
        private const val PACKET_FLAG_CONFIG = 1L shl 62
        private const val PACKET_PTS_MASK = (1L shl 61) - 1

        private const val TYPE_INJECT_KEYCODE = 0
        private const val TYPE_INJECT_TEXT = 1
        private const val TYPE_INJECT_TOUCH_EVENT = 2
        private const val MAX_TEXT_LENGTH = 300

        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2

        const val KEYCODE_HOME = 3
        const val KEYCODE_BACK = 4
        const val KEYCODE_APP_SWITCH = 187
        const val KEYCODE_DEL = 67
        const val KEYCODE_ENTER = 66
    }
}
