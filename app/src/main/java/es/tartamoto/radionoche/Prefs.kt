package es.tartamoto.radionoche

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.util.Calendar

/** Ajustes guardados: horario de grabación, direcciones de la emisora y posiciones de escucha. */
object Prefs {

    data class Slot(val enabled: Boolean, val startMin: Int, val durMin: Int)

    val DEFAULT_URLS = listOf(
        "https://flucast09-h-cloud.flumotion.com/cope/net1.mp3",
        "https://flucast31-h-cloud.flumotion.com/cope/net2.mp3",
        "https://flucast22-h-cloud.flumotion.com/cope/net1.mp3",
        "https://flucast08-h-cloud.flumotion.com/cope/net1.mp3",
        "https://net1-cope-rrcast.flumotion.com/cope/net1.mp3"
    )

    /** Orden de los días en pantalla: lunes..domingo (constantes de Calendar). */
    val DAYS = listOf(
        Calendar.MONDAY to "Lunes",
        Calendar.TUESDAY to "Martes",
        Calendar.WEDNESDAY to "Miércoles",
        Calendar.THURSDAY to "Jueves",
        Calendar.FRIDAY to "Viernes",
        Calendar.SATURDAY to "Sábado",
        Calendar.SUNDAY to "Domingo"
    )

    fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences("radio_noche", Context.MODE_PRIVATE)

    /** Todos los días 23:30 - 01:30. */
    @Suppress("UNUSED_PARAMETER")
    private fun defaultSlot(dow: Int): Slot = Slot(true, 23 * 60 + 30, 120)

    fun slot(c: Context, dow: Int): Slot {
        val p = sp(c)
        val d = defaultSlot(dow)
        return Slot(
            p.getBoolean("en_$dow", d.enabled),
            p.getInt("ini_$dow", d.startMin),
            p.getInt("dur_$dow", d.durMin)
        )
    }

    fun setSlot(c: Context, dow: Int, s: Slot) {
        sp(c).edit()
            .putBoolean("en_$dow", s.enabled)
            .putInt("ini_$dow", s.startMin)
            .putInt("dur_$dow", s.durMin)
            .apply()
    }

    fun urls(c: Context): List<String> {
        val raw = sp(c).getString("urls", null) ?: return DEFAULT_URLS
        val l = raw.lines().map { it.trim() }.filter { it.startsWith("http") }
        return l.ifEmpty { DEFAULT_URLS }
    }

    fun setUrls(c: Context, text: String) {
        sp(c).edit().putString("urls", text).apply()
    }

    fun recDir(c: Context): File = File(c.filesDir, "grabaciones").apply { mkdirs() }

    /** Grabaciones de más nueva a más vieja. */
    fun recordings(c: Context): List<File> =
        (recDir(c).listFiles { f -> f.name.endsWith(".mp3") && f.length() > 0 } ?: emptyArray())
            .sortedByDescending { it.name }

    fun position(c: Context, name: String): Long = sp(c).getLong("pos_$name", 0L)
    fun setPosition(c: Context, name: String, ms: Long) {
        sp(c).edit().putLong("pos_$name", ms).apply()
    }

    /** Bytes por segundo de una grabación (para calcular cuánto lleva grabado). */
    fun bps(c: Context, name: String): Int = sp(c).getInt("bps_$name", 16000)
    fun setBps(c: Context, name: String, v: Int) {
        if (v in 4000..64000) sp(c).edit().putInt("bps_$name", v).apply()
    }

    /**
     * Borra las grabaciones viejas: solo interesan la de esa noche.
     * Se borra todo lo que empezó hace más de 18 horas (y nunca la que se está grabando).
     */
    fun deleteOld(c: Context) {
        val limit = System.currentTimeMillis() - 18L * 3600 * 1000
        val active = RecordService.activeFile?.absolutePath
        recordings(c).forEach { f ->
            if (f.absolutePath != active && startOf(f) < limit) {
                sp(c).edit().remove("pos_${f.name}").remove("bps_${f.name}").apply()
                f.delete()
            }
        }
    }

    /** Hora de inicio sacada del nombre del archivo. */
    fun startOf(f: File): Long {
        val m = Regex("""(\d{4})-(\d{2})-(\d{2})_(\d{2})(\d{2})""").find(f.name) ?: return f.lastModified()
        val (y, mo, d, h, mi) = m.destructured
        return Calendar.getInstance().apply {
            set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    fun recordedMs(c: Context, f: File): Long = f.length() * 1000L / bps(c, f.name)

    fun fmtMin(m: Int): String = "%02d:%02d".format((m / 60) % 24, m % 60)

    fun fmtDur(m: Int): String {
        val h = m / 60
        val mm = m % 60
        return if (mm == 0) "$h h" else "$h h $mm min"
    }

    fun fmtTime(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }

    /** "COPE_2026-11-25_2330.mp3" -> "mié 25/11 · 23:30" */
    fun prettyName(name: String): String {
        val m = Regex("""(\d{4})-(\d{2})-(\d{2})_(\d{2})(\d{2})""").find(name) ?: return name
        val (y, mo, d, h, mi) = m.destructured
        val cal = Calendar.getInstance().apply { set(y.toInt(), mo.toInt() - 1, d.toInt()) }
        val dias = arrayOf("", "dom", "lun", "mar", "mié", "jue", "vie", "sáb")
        return "${dias[cal.get(Calendar.DAY_OF_WEEK)]} $d/$mo · $h:$mi"
    }
}
