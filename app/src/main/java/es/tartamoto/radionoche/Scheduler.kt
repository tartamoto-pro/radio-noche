package es.tartamoto.radionoche

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.Calendar

/** Programa las alarmas que arrancan la grabación a la hora fijada. */
object Scheduler {

    private const val RC_NEXT = 1
    private const val RC_NOW = 2

    /** Franjas (inicio, fin) desde ayer hasta dentro de una semana, ordenadas. */
    fun slots(c: Context, now: Long): List<Pair<Long, Long>> {
        val out = mutableListOf<Pair<Long, Long>>()
        for (d in -1..7) {
            val cal = Calendar.getInstance().apply {
                timeInMillis = now
                add(Calendar.DAY_OF_YEAR, d)
            }
            val s = Prefs.slot(c, cal.get(Calendar.DAY_OF_WEEK))
            if (!s.enabled) continue
            cal.set(Calendar.HOUR_OF_DAY, s.startMin / 60)
            cal.set(Calendar.MINUTE, s.startMin % 60)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            out.add(start to start + s.durMin * 60_000L)
        }
        return out.sortedBy { it.first }
    }

    fun activeSlot(c: Context, now: Long = System.currentTimeMillis()) =
        slots(c, now).firstOrNull { it.first <= now && now < it.second }

    fun nextSlot(c: Context, now: Long = System.currentTimeMillis()) =
        slots(c, now).firstOrNull { it.first > now }

    private fun pi(c: Context, rc: Int, end: Long): PendingIntent {
        val i = Intent(c, AlarmReceiver::class.java).putExtra("end", end)
        return PendingIntent.getBroadcast(
            c, rc, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun canExact(am: AlarmManager) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        val now = System.currentTimeMillis()
        am.cancel(pi(c, RC_NEXT, 0))

        // Si estamos dentro de una franja y no se está grabando, arrancar ya
        val active = activeSlot(c, now)
        val stoppedUntil = Prefs.sp(c).getLong("parado_hasta", 0L)
        if (active != null && !RecordService.recording && stoppedUntil < active.second) {
            val p = pi(c, RC_NOW, active.second)
            if (canExact(am)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + 1500, p)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + 1500, p)
        }

        val next = nextSlot(c, now)
        if (next != null) {
            val p = pi(c, RC_NEXT, next.second)
            if (canExact(am)) {
                val show = PendingIntent.getActivity(
                    c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(next.first, show), p)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.first, p)
            }
        }
    }

    fun startRecording(c: Context, end: Long) {
        val i = Intent(c, RecordService::class.java).putExtra("end", end)
        ContextCompat.startForegroundService(c, i)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val end = intent.getLongExtra("end", 0L)
        if (end > System.currentTimeMillis()) {
            try {
                Scheduler.startRecording(c, end)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        Scheduler.schedule(c)
    }
}
