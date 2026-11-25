package es.tartamoto.radionoche

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@UnstableApi
class MainActivity : Activity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var recStatus: TextView
    private lateinit var btnResume: Button
    private lateinit var nowPlaying: TextView
    private lateinit var posText: TextView
    private lateinit var btnPlayPause: Button
    private lateinit var sleepText: TextView
    private lateinit var recList: LinearLayout
    private lateinit var schedList: LinearLayout
    private lateinit var nextText: TextView
    private lateinit var btnRecNow: Button
    private lateinit var btnBattery: Button
    private var refreshCount = 0

    private val BG = Color.parseColor("#101418")
    private val CARD = Color.parseColor("#1C232B")
    private val GREEN = Color.parseColor("#2E7D32")
    private val BLUE = Color.parseColor("#1565C0")
    private val GREY = Color.parseColor("#37474F")
    private val RED = Color.parseColor("#B71C1C")
    private val YELLOW = Color.parseColor("#FFD54F")

    private val ticker = object : Runnable {
        override fun run() {
            refreshPlayer()
            if (++refreshCount % 15 == 0) refreshLists()
            handler.postDelayed(this, 1000)
        }
    }

    // ---------- ciclo de vida ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        setContentView(buildUi())

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlayerService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        controllerFuture = f
        f.addListener({
            try {
                controller = f.get()
                refreshPlayer()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onResume() {
        super.onResume()
        Scheduler.schedule(this)
        refreshLists()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onStop()
    }

    // ---------- reproducción ----------

    private fun latestRecording(): File? {
        if (RecordService.recording) RecordService.activeFile?.let { if (it.exists()) return it }
        return Prefs.recordings(this).firstOrNull()
    }

    private fun isGrowing(f: File?): Boolean =
        f != null && RecordService.recording && RecordService.activeFile?.absolutePath == f.absolutePath

    private fun currentFile(): File? {
        val id = controller?.currentMediaItem?.mediaId ?: return null
        if (id == PlayerService.LIVE_ID) return null
        return File(Prefs.recDir(this), id)
    }

    private fun playFile(f: File, posMs: Long) {
        val c = controller ?: return toast("Un momento, arrancando el reproductor…")
        val item = MediaItem.Builder()
            .setMediaId(f.name)
            .setUri(Uri.fromFile(f))
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder().setMediaUri(Uri.fromFile(f)).build()
            )
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Grabación " + Prefs.prettyName(f.name))
                    .setArtist("COPE · Radio Noche")
                    .build()
            )
            .build()
        c.setMediaItem(item, posMs)
        c.prepare()
        c.play()
        refreshPlayer()
    }

    private fun playLive() {
        val c = controller ?: return toast("Un momento, arrancando el reproductor…")
        val s = PlayerService.instance
        if (s != null) s.playLive() else {
            c.setMediaItem(PlayerService.liveItem(Prefs.urls(this).first()))
            c.prepare()
            c.play()
        }
        refreshPlayer()
    }

    private fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        if (c.currentMediaItem?.mediaId == PlayerService.LIVE_ID) return toast("En directo no se puede mover")
        val f = currentFile()
        var target = (c.currentPosition + deltaMs).coerceAtLeast(0)
        if (f != null && isGrowing(f)) target = target.coerceAtMost(Prefs.recordedMs(this, f) - 4000)
        else if (c.duration != C.TIME_UNSET) target = target.coerceAtMost(c.duration - 1000)
        c.seekTo(target.coerceAtLeast(0))
    }

    private fun goToLatest() {
        val c = controller ?: return
        val f = currentFile() ?: return toast("Ya estás en directo")
        if (!isGrowing(f)) return toast("Esta grabación ya terminó")
        c.seekTo((Prefs.recordedMs(this, f) - 6000).coerceAtLeast(0))
    }

    private fun setSleep(min: Int) {
        val s = PlayerService.instance ?: return toast("Primero pon algo a sonar")
        s.setSleep(min)
        refreshPlayer()
        if (min > 0) toast("Se apagará en $min minutos")
    }

    // ---------- refrescos ----------

    private fun refreshPlayer() {
        val c = controller
        // estado de grabación
        recStatus.text = if (RecordService.recording) {
            val f = RecordService.activeFile
            val dur = if (f != null) Prefs.fmtTime(Prefs.recordedMs(this, f)) else ""
            val end = SimpleDateFormat("HH:mm", Locale.US).format(Date(RecordService.endAt))
            "⏺ Grabando · $dur grabado · hasta las $end\n${RecordService.status}"
        } else "No se está grabando ahora"
        recStatus.setTextColor(if (RecordService.recording) YELLOW else Color.LTGRAY)
        btnRecNow.text = if (RecordService.recording) "⏹  Parar la grabación" else "⏺  Grabar ahora (2 horas)"

        val latest = latestRecording()
        val saved = latest?.let { Prefs.position(this, it.name) } ?: 0L
        if (latest != null && saved > 10_000) {
            btnResume.visibility = View.VISIBLE
            btnResume.text = "⏯  SEGUIR DONDE IBA (${Prefs.fmtTime(saved)})"
        } else btnResume.visibility = View.GONE

        if (c == null || c.currentMediaItem == null) {
            nowPlaying.text = "Nada sonando"
            posText.text = ""
            btnPlayPause.text = "▶"
        } else {
            val id = c.currentMediaItem?.mediaId
            btnPlayPause.text = if (c.playWhenReady) "⏸" else "▶"
            if (id == PlayerService.LIVE_ID) {
                nowPlaying.text = "📻 COPE en directo"
                posText.text = if (c.isPlaying) "Sonando en vivo" else "Cargando…"
            } else {
                val f = File(Prefs.recDir(this), id ?: "")
                nowPlaying.text = "🎙 Grabación " + Prefs.prettyName(f.name)
                val pos = c.currentPosition
                posText.text = if (isGrowing(f)) {
                    val rec = Prefs.recordedMs(this, f)
                    val behind = ((rec - pos) / 60_000).coerceAtLeast(0)
                    "${Prefs.fmtTime(pos)} · grabado ${Prefs.fmtTime(rec)} · vas $behind min por detrás del directo"
                } else {
                    val d = if (c.duration != C.TIME_UNSET) c.duration else Prefs.recordedMs(this, f)
                    "${Prefs.fmtTime(pos)} de ${Prefs.fmtTime(d)}"
                }
            }
        }

        val sleepAt = PlayerService.instance?.sleepAt ?: 0L
        sleepText.text = if (sleepAt > 0) {
            val left = (sleepAt - System.currentTimeMillis()).coerceAtLeast(0)
            "😴 Se apaga en ${Prefs.fmtTime(left)}"
        } else "Temporizador apagado"

        val pm = getSystemService(PowerManager::class.java)
        btnBattery.visibility = if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
    }

    private fun refreshLists() {
        // grabaciones
        recList.removeAllViews()
        val recs = Prefs.recordings(this)
        if (recs.isEmpty()) recList.addView(text("Todavía no hay grabaciones.", 15f, Color.LTGRAY))
        recs.forEach { f ->
            val pos = Prefs.position(this, f.name)
            val extra = if (pos > 10_000) " · ibas por ${Prefs.fmtTime(pos)}" else ""
            val live = if (isGrowing(f)) " · grabando" else ""
            recList.addView(button(
                "${Prefs.prettyName(f.name)} — ${Prefs.fmtTime(Prefs.recordedMs(this, f))}$live$extra",
                CARD, 15f
            ) { recordingMenu(f) })
        }

        // siguiente grabación
        val next = Scheduler.nextSlot(this)
        nextText.text = if (next != null) {
            val cal = Calendar.getInstance().apply { timeInMillis = next.first }
            val today = Calendar.getInstance()
            val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
            val day = when {
                cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) -> "hoy"
                cal.get(Calendar.DAY_OF_YEAR) == tomorrow.get(Calendar.DAY_OF_YEAR) -> "mañana"
                else -> SimpleDateFormat("EEEE d", Locale("es", "ES")).format(cal.time)
            }
            "Próxima grabación: $day a las " + SimpleDateFormat("HH:mm", Locale.US).format(cal.time)
        } else "No hay ningún día activado"
    }

    private fun recordingMenu(f: File) {
        val pos = Prefs.position(this, f.name)
        val opts = mutableListOf("▶ Desde el principio")
        if (pos > 10_000) opts.add("⏯ Seguir por ${Prefs.fmtTime(pos)}")
        opts.add("🗑 Borrar")
        AlertDialog.Builder(this)
            .setTitle(Prefs.prettyName(f.name))
            .setItems(opts.toTypedArray()) { _, which ->
                when (opts[which].substring(0, 1)) {
                    "▶" -> playFile(f, 0)
                    "⏯" -> playFile(f, pos)
                    else -> {
                        if (isGrowing(f)) toast("No se puede borrar mientras graba")
                        else AlertDialog.Builder(this)
                            .setMessage("¿Borrar esta grabación?")
                            .setPositiveButton("Borrar") { _, _ -> f.delete(); refreshLists() }
                            .setNegativeButton("Cancelar", null)
                            .show()
                    }
                }
            }
            .show()
    }

    private fun rebuildSchedule() {
        schedList.removeAllViews()
        Prefs.DAYS.forEach { (dow, label) ->
            val s = Prefs.slot(this, dow)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, dp(2))
            }
            val cb = CheckBox(this).apply {
                text = label
                textSize = 17f
                setTextColor(Color.WHITE)
                isChecked = s.enabled
                buttonTintList = android.content.res.ColorStateList.valueOf(YELLOW)
                setOnCheckedChangeListener { _, on ->
                    Prefs.setSlot(this@MainActivity, dow, Prefs.slot(this@MainActivity, dow).copy(enabled = on))
                    Scheduler.schedule(this@MainActivity)
                    refreshLists()
                }
            }
            row.addView(cb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(button(Prefs.fmtMin(s.startMin), GREY, 17f) {
                val cur = Prefs.slot(this, dow)
                TimePickerDialog(this, { _, h, m ->
                    Prefs.setSlot(this, dow, cur.copy(startMin = h * 60 + m))
                    Scheduler.schedule(this)
                    rebuildSchedule(); refreshLists()
                }, cur.startMin / 60, cur.startMin % 60, true).show()
            }, LinearLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
            row.addView(button(Prefs.fmtDur(s.durMin), GREY, 15f) {
                val choices = (1..10).map { it * 30 }
                AlertDialog.Builder(this)
                    .setTitle("¿Cuánto rato grabar el $label?")
                    .setItems(choices.map { Prefs.fmtDur(it) }.toTypedArray()) { _, w ->
                        Prefs.setSlot(this, dow, Prefs.slot(this, dow).copy(durMin = choices[w]))
                        Scheduler.schedule(this)
                        rebuildSchedule(); refreshLists()
                    }.show()
            }, LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT))
            schedList.addView(row)
        }
    }

    // ---------- interfaz ----------

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(40))
        }
        col.addView(text("🌙 Radio Noche", 28f, Color.WHITE, bold = true))
        col.addView(text("El Partidazo y Tiempo de Juego, desde el principio", 15f, Color.LTGRAY))
        recStatus = text("", 16f, Color.LTGRAY).also { col.addView(it, topMargin(10)) }

        col.addView(bigButton("▶  DESDE EL PRINCIPIO", GREEN) {
            val f = latestRecording()
            if (f == null) toast("Todavía no hay nada grabado. Mira abajo la próxima grabación.")
            else playFile(f, 0)
        }, topMargin(14))
        btnResume = bigButton("⏯  SEGUIR DONDE IBA", BLUE) {
            val f = latestRecording() ?: return@bigButton
            playFile(f, Prefs.position(this, f.name))
        }
        col.addView(btnResume, topMargin(8))
        col.addView(bigButton("📻  DIRECTO", GREY) { playLive() }, topMargin(8))

        // tarjeta de reproducción
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = round(CARD)
        }
        nowPlaying = text("Nada sonando", 18f, Color.WHITE, bold = true).also { card.addView(it) }
        posText = text("", 15f, Color.LTGRAY).also { card.addView(it, topMargin(4)) }
        val ctrl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun addCtrl(b: Button) = ctrl.addView(b, LinearLayout.LayoutParams(0, dp(60), 1f).apply {
            marginEnd = dp(4)
        })
        addCtrl(button("−30 s", GREY, 17f) { seekBy(-30_000) })
        btnPlayPause = button("▶", BLUE, 24f) {
            val c = controller ?: return@button
            if (c.playWhenReady) c.pause() else {
                if (c.playbackState == androidx.media3.common.Player.STATE_IDLE) c.prepare()
                c.play()
            }
            refreshPlayer()
        }
        addCtrl(btnPlayPause)
        addCtrl(button("+30 s", GREY, 17f) { seekBy(30_000) })
        addCtrl(button("⏭ Último", GREY, 15f) { goToLatest() })
        card.addView(ctrl, topMargin(10))

        card.addView(text("Apagar en:", 15f, Color.LTGRAY), topMargin(12))
        val sleepRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(15, 30, 45, 60, 90).forEach { m ->
            sleepRow.addView(button("$m′", GREY, 16f) { setSleep(m) },
                LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(4) })
        }
        sleepRow.addView(button("No", RED, 16f) { setSleep(0) },
            LinearLayout.LayoutParams(0, dp(52), 1f))
        card.addView(sleepRow, topMargin(4))
        sleepText = text("", 15f, Color.LTGRAY).also { card.addView(it, topMargin(6)) }
        col.addView(card, topMargin(16))

        // grabaciones
        col.addView(header("Grabaciones"), topMargin(22))
        recList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(recList)

        // horario
        col.addView(header("Horario de grabación"), topMargin(22))
        col.addView(text("Marca los días, la hora de empezar y cuánto rato grabar.", 14f, Color.LTGRAY))
        schedList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(schedList, topMargin(6))
        rebuildSchedule()
        nextText = text("", 16f, YELLOW).also { col.addView(it, topMargin(8)) }

        btnRecNow = bigButton("⏺  Grabar ahora (2 horas)", RED) {
            if (RecordService.recording) {
                startService(Intent(this, RecordService::class.java).setAction(RecordService.ACTION_STOP))
            } else {
                Prefs.sp(this).edit().putLong("parado_hasta", 0L).apply()
                Scheduler.startRecording(this, System.currentTimeMillis() + 2 * 3600_000L)
            }
            handler.postDelayed({ refreshPlayer(); refreshLists() }, 1500)
        }
        col.addView(btnRecNow, topMargin(12))

        btnBattery = bigButton("🔋  Que el móvil no corte la grabación", GREY) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        col.addView(btnBattery, topMargin(8))

        // avanzado
        col.addView(header("Avanzado: dirección de la emisora"), topMargin(26))
        col.addView(text("Una por línea. Si una falla, prueba la siguiente.", 14f, Color.LTGRAY))
        val urls = EditText(this).apply {
            setText(Prefs.urls(this@MainActivity).joinToString("\n"))
            textSize = 13f
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_VARIATION_URI
            background = round(CARD)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        col.addView(urls, topMargin(6))
        val rowUrl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowUrl.addView(button("Guardar", BLUE, 15f) {
            Prefs.setUrls(this, urls.text.toString()); toast("Guardado")
        }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(6) })
        rowUrl.addView(button("Restaurar", GREY, 15f) {
            Prefs.sp(this).edit().remove("urls").apply()
            urls.setText(Prefs.DEFAULT_URLS.joinToString("\n")); toast("Restaurado")
        }, LinearLayout.LayoutParams(0, dp(50), 1f))
        col.addView(rowUrl, topMargin(6))

        return ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(col)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun topMargin(v: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(v) }

    private fun round(color: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(12).toFloat()
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun header(s: String) = text(s, 20f, Color.WHITE, bold = true)

    private fun button(s: String, color: Int, size: Float, onClick: () -> Unit) = Button(this).apply {
        text = s
        textSize = size
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = round(color)
        setPadding(dp(8), dp(6), dp(8), dp(6))
        stateListAnimator = null
        setOnClickListener { onClick() }
    }.also {
        it.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) }
    }

    private fun bigButton(s: String, color: Int, onClick: () -> Unit) =
        button(s, color, 20f, onClick).apply {
            minHeight = dp(68)
            setTypeface(typeface, Typeface.BOLD)
        }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    }
}
