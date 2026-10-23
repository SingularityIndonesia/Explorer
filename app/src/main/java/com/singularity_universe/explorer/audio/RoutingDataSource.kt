package com.singularity_universe.explorer.audio

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import com.singularity_universe.explorer.remote.SftpDataSource
import com.singularity_universe.explorer.remote.SshConnections

/** Reads `ssh://` addresses from the server (see [SftpDataSource]) and everything else as usual. */
@UnstableApi
class RoutingDataSource(context: Context) : DataSource {
    private val default = DefaultDataSource.Factory(context).createDataSource()
    private val sftp = SftpDataSource(SshConnections.get(context))
    private var current: DataSource? = null

    override fun open(dataSpec: DataSpec): Long {
        val source = if (dataSpec.uri.scheme == "ssh") sftp else default
        current = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current?.read(buffer, offset, length) ?: -1

    override fun getUri(): Uri? = current?.uri

    override fun close() {
        current?.close()
        current = null
    }

    override fun addTransferListener(transferListener: TransferListener) {
        default.addTransferListener(transferListener)
        sftp.addTransferListener(transferListener)
    }

    class Factory(private val context: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource = RoutingDataSource(context.applicationContext)
    }
}
