package com.singularity_universe.explorer.remote

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpException
import java.io.IOException
import java.io.InputStream
import kotlin.math.min
import kotlinx.coroutines.runBlocking

/**
 * Lets Media3 read `ssh://` files straight from the server (on a channel of their own), starting at
 * any position, so videos play and seek without being downloaded first.
 */
@UnstableApi
class SftpDataSource(private val ssh: SshConnections) : BaseDataSource(/* isNetwork = */ true) {
    private var channel: ChannelSftp? = null
    private var input: InputStream? = null
    private var uri: Uri? = null
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val path = SshPath.parse(dataSpec.uri.toString()) ?: throw IOException("Not an SSH address: ${dataSpec.uri}")
        try {
            // Media3 opens sources on its loading thread, where blocking is expected.
            val opened = runBlocking { ssh.openChannel(path.hostId) }
            channel = opened
            val size = opened.stat(path.path).size
            if (dataSpec.position > size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            // position is a Long: an Int here would pick JSch's deprecated get(path, monitor, mode),
            // which ignores it and reads from the start.
            input = opened.get(path.path, null, dataSpec.position)
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else size - dataSpec.position
        } catch (e: SftpException) {
            throw IOException(e.message, e)
        }
        this.opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val read = input?.read(buffer, offset, min(length.toLong(), bytesRemaining).toInt()) ?: return C.RESULT_END_OF_INPUT
        if (read < 0) return C.RESULT_END_OF_INPUT
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            input?.close()
        } catch (_: IOException) {
        } finally {
            input = null
            channel?.disconnect()
            channel = null
            uri = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory(private val ssh: SshConnections) : DataSource.Factory {
        override fun createDataSource(): DataSource = SftpDataSource(ssh)
    }
}
