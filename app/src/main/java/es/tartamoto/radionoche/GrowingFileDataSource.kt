package es.tartamoto.radionoche

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.File
import java.io.InterruptedIOException
import java.io.RandomAccessFile

/**
 * Lee un archivo que se está grabando todavía: cuando llega al final y la
 * grabación sigue, espera a que haya más audio en vez de terminar.
 */
@UnstableApi
class GrowingFileDataSource : BaseDataSource(false) {
    private var raf: RandomAccessFile? = null
    private var file: File? = null
    private var uri: Uri? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val f = File(dataSpec.uri.path!!)
        file = f
        raf = RandomAccessFile(f, "r").also { it.seek(dataSpec.position) }
        remaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            stillGrowing() -> C.LENGTH_UNSET.toLong() // sigue grabándose: longitud desconocida
            else -> (f.length() - dataSpec.position).coerceAtLeast(0)
        }
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    private fun stillGrowing(): Boolean {
        val active = RecordService.activeFile
        return RecordService.recording && active != null && active.absolutePath == file?.absolutePath
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val r = raf ?: return C.RESULT_END_OF_INPUT
        val want = if (remaining == C.LENGTH_UNSET.toLong()) length
        else minOf(length.toLong(), remaining).toInt()
        while (true) {
            val n = r.read(buffer, offset, want)
            if (n > 0) {
                if (remaining != C.LENGTH_UNSET.toLong()) remaining -= n
                bytesTransferred(n)
                return n
            }
            if (!stillGrowing()) return C.RESULT_END_OF_INPUT
            try {
                Thread.sleep(300)
            } catch (e: InterruptedException) {
                throw InterruptedIOException()
            }
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            raf?.close()
        } finally {
            raf = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}

/** Usa el lector de archivo en crecimiento para grabaciones y HTTP para el directo. */
@UnstableApi
class SwitchDataSource(private val http: DataSource.Factory) : DataSource {
    private var current: DataSource? = null
    private val listeners = mutableListOf<TransferListener>()

    override fun addTransferListener(transferListener: TransferListener) {
        listeners.add(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val ds: DataSource = if (dataSpec.uri.scheme == "file") GrowingFileDataSource()
        else http.createDataSource()
        listeners.forEach { ds.addTransferListener(it) }
        current = ds
        return ds.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        current?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        current?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            current?.close()
        } finally {
            current = null
        }
    }

    class Factory(@Suppress("UNUSED_PARAMETER") c: Context) : DataSource.Factory {
        private val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Android) RadioNoche")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)

        override fun createDataSource(): DataSource = SwitchDataSource(http)
    }
}
