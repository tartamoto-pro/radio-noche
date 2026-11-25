package es.tartamoto.radionoche

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Graba la emisora en un archivo mp3 hasta la hora de fin. */
class RecordService : Service() {

    companion object {
        @Volatile var recording = false
        @Volatile var activeFile: File? = null
        @Volatile var endAt = 0L
        @Volatile var status = ""
        const val ACTION_STOP = "es.tartamoto.radionoche.STOP_REC"
        private const val CH = "grabacion"
        private const val NOTIF_ID = 7
    }

    @Volatile private var stopRequested = false
    private var thread: Thread? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // El usuario la ha parado: no volver a arrancarla en esta franja
            Prefs.sp(this).edit().putLong("parado_hasta", endAt).apply()
            stopRequested = true
            thread?.interrupt()
            if (!recording) stopSelf()
            return START_NOT_STICKY
        }

        val end = intent?.getLongExtra("end", 0L) ?: 0L
        startInForeground()

        if (recording) {
            if (end > endAt) endAt = end
            return START_NOT_STICKY
        }
        if (end <= System.currentTimeMillis()) {
            stopSelf()
            return START_NOT_STICKY
        }

        endAt = end
        stopRequested = false
        recording = true
        val name = "COPE_" + SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date()) + ".mp3"
        val file = File(Prefs.recDir(this), name)
        activeFile = file

        val pm = getSystemService(PowerManager::class.java)
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "radionoche:grabar").apply {
            acquire(end - System.currentTimeMillis() + 120_000L)
        }
        @Suppress("DEPRECATION")
        wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "radionoche:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }

        Scheduler.schedule(this) // deja ya programada la siguiente
        thread = Thread({ recordLoop(file) }, "grabador").also { it.start() }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, "Grabación", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Grabando COPE")
            .setContentText("Abre la app y pulsa «Desde el principio»")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Parar grabación", stop)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }

    private fun recordLoop(file: File) {
        val buf = ByteArray(16 * 1024)
        var written = 0L
        var connectedMs = 0L
        var urlIndex = 0
        try {
            FileOutputStream(file, true).use { out ->
                while (!stopRequested && System.currentTimeMillis() < endAt) {
                    val urls = Prefs.urls(this)
                    val url = urls[urlIndex % urls.size]
                    var conn: HttpURLConnection? = null
                    try {
                        status = "Conectando…"
                        conn = open(url)
                        val br = conn.getHeaderField("icy-br")?.split(",")?.firstOrNull()
                            ?.trim()?.toIntOrNull()
                        if (br != null) Prefs.setBps(this, file.name, br * 125)
                        val input = conn.inputStream
                        status = "Grabando"
                        val t0 = System.currentTimeMillis()
                        var lastSave = t0
                        var segBytes = 0L
                        while (!stopRequested && System.currentTimeMillis() < endAt) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                            segBytes += n
                            val now = System.currentTimeMillis()
                            if (br == null && now - lastSave > 20_000) {
                                lastSave = now
                                val ms = connectedMs + (now - t0)
                                if (ms > 10_000) Prefs.setBps(this, file.name, (written * 1000 / ms).toInt())
                            }
                        }
                        connectedMs += System.currentTimeMillis() - t0
                        if (segBytes < 50_000) urlIndex++ // se cortó enseguida: probar otra
                    } catch (e: Exception) {
                        urlIndex++
                        status = "Sin conexión, reintentando…"
                        try { Thread.sleep(3000) } catch (_: InterruptedException) {}
                    } finally {
                        conn?.disconnect()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            recording = false
            status = ""
            cleanup()
            Scheduler.schedule(this)
            try { wake?.release() } catch (_: Exception) {}
            try { wifi?.release() } catch (_: Exception) {}
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** Abre la conexión; si la dirección es una lista .m3u/.pls, sigue el primer enlace. */
    private fun open(url: String, depth: Int = 0): HttpURLConnection {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) RadioNoche")
            setRequestProperty("Icy-MetaData", "0")
        }
        val code = conn.responseCode
        if (code in 300..399 && depth < 5) {
            val loc = conn.getHeaderField("Location")
            conn.disconnect()
            if (loc != null) return open(URL(URL(url), loc).toString(), depth + 1)
        }
        if (code != 200) {
            conn.disconnect()
            throw RuntimeException("HTTP $code")
        }
        val type = conn.contentType ?: ""
        if ((type.contains("mpegurl") || type.contains("scpls") || url.endsWith(".m3u") ||
                url.endsWith(".pls")) && depth < 5
        ) {
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val next = body.lines().map { it.substringAfter("=").trim() }
                .firstOrNull { it.startsWith("http") } ?: throw RuntimeException("lista vacía")
            return open(next, depth + 1)
        }
        return conn
    }

    /** Borra grabaciones de más de 7 días (deja siempre las 3 últimas). */
    private fun cleanup() {
        val limit = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        Prefs.recordings(this).drop(3).filter { it.lastModified() < limit }.forEach { it.delete() }
    }

    override fun onDestroy() {
        stopRequested = true
        super.onDestroy()
    }
}
