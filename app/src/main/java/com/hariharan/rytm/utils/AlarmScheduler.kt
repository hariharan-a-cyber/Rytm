package com.hariharan.rytm.utils

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hariharan.rytm.R
import com.hariharan.rytm.data.entity.Habit
import com.hariharan.rytm.data.entity.HabitWithReminders
import com.hariharan.rytm.data.entity.Reminder
import com.hariharan.rytm.data.entity.WaterReminder
import com.hariharan.rytm.receiver.AlarmReceiver
import com.hariharan.rytm.ui.MainActivity
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlarmScheduler @Inject constructor(
    private val context: Context
) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun scheduleReminder(habit: Habit, reminder: Reminder) {
        if (!habit.isActive || !reminder.isActive) return

        val triggerTime = nextAlarmTime(reminder)
        
        val intent = buildAlarmIntent(habit, reminder, triggerTime)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminder.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            Log.d("RytmAlarm", "Scheduled habit alarm (inexact fallback): ${habit.name} at $triggerTime")
        } else {
            val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerTime, pendingIntent)
            alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
            Log.d("RytmAlarm", "Scheduled habit alarm (exact): ${habit.name} at $triggerTime")
        }
    }

    fun cancelReminder(reminderId: Long) {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminderId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        Log.d("RytmAlarm", "Cancelled habit alarm: $reminderId")
    }

    fun scheduleWaterReminder(reminder: WaterReminder) {
        if (!reminder.isActive) return

        val triggerTime = nextWaterAlarmTime(reminder)
        
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_TYPE, TYPE_WATER)
            putExtra(EXTRA_REMINDER_ID, reminder.id)
            putExtra(EXTRA_HABIT_NAME, "Water Intake")
            putExtra(EXTRA_HABIT_EMOJI, "")
            putExtra(EXTRA_WATER_AMOUNT, reminder.amountMl)
            putExtra(EXTRA_SCHEDULED_TIME, triggerTime)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminder.id.toInt() + WATER_ID_OFFSET,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            Log.d("RytmAlarm", "Scheduled water alarm (inexact fallback): ${reminder.id} at $triggerTime")
        } else {
            val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerTime, pendingIntent)
            alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
            Log.d("RytmAlarm", "Scheduled water alarm (exact): ${reminder.id} at $triggerTime")
        }
    }

    fun cancelWaterReminder(reminderId: Long) {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminderId.toInt() + WATER_ID_OFFSET,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    fun rescheduleForNextDay(habit: Habit, reminder: Reminder) {
        scheduleReminder(habit, reminder)
    }

    /** Snooze a habit: re-fire the SAME reminder after [minutes], without touching tomorrow's alarm. */
    fun snoozeReminder(habit: Habit, reminder: Reminder, minutes: Int) {
        val triggerTime = System.currentTimeMillis() + minutes * 60_000L
        val intent = buildAlarmIntent(habit, reminder, triggerTime).apply {
            putExtra("is_snoozed", true)
        }
        
        // Use a different request code for snooze so it doesn't cancel tomorrow's alarm
        val snoozeRequestCode = reminder.id.toInt() + SNOOZE_ID_OFFSET
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            snoozeRequestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
        } else {
            val info = AlarmManager.AlarmClockInfo(triggerTime, pendingIntent)
            alarmManager.setAlarmClock(info, pendingIntent)
        }
        Log.d("RytmAlarm", "Snoozed habit ${habit.name} for $minutes min")
    }

    /** Snooze a water reminder: re-fire the SAME reminder after [minutes]. */
    fun snoozeWaterReminder(reminder: WaterReminder, minutes: Int) {
        val triggerTime = System.currentTimeMillis() + minutes * 60_000L
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_TYPE, TYPE_WATER)
            putExtra(EXTRA_REMINDER_ID, reminder.id)
            putExtra(EXTRA_HABIT_NAME, "Water Intake")
            putExtra(EXTRA_HABIT_EMOJI, "💧")
            putExtra(EXTRA_WATER_AMOUNT, reminder.amountMl)
            putExtra(EXTRA_SCHEDULED_TIME, triggerTime)
            putExtra("is_snoozed", true)
        }
        
        // Use a different request code for snooze
        val snoozeRequestCode = reminder.id.toInt() + WATER_ID_OFFSET + SNOOZE_ID_OFFSET
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            snoozeRequestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
        } else {
            val info = AlarmManager.AlarmClockInfo(triggerTime, pendingIntent)
            alarmManager.setAlarmClock(info, pendingIntent)
        }
        Log.d("RytmAlarm", "Snoozed water ${reminder.id} for $minutes min")
    }

    suspend fun rescheduleWaterForNextDay(repository: com.hariharan.rytm.repository.HabitRepository, reminderId: Long) {
        repository.getWaterReminderById(reminderId)?.let { reminder ->
            val triggerTime = nextWaterAlarmTime(reminder)
            scheduleWaterReminder(reminder)
            repository.updateWaterReminderLastScheduledAt(reminderId, triggerTime)
        }
    }

    suspend fun rescheduleAllAlarms(repository: com.hariharan.rytm.repository.HabitRepository) {
        repository.getHabitsWithRemindersOnce().forEach { hwr ->
            hwr.reminders.forEach { reminder ->
                val triggerTime = nextAlarmTime(reminder)
                scheduleReminder(hwr.habit, reminder)
                repository.updateReminderLastScheduledAt(reminder.id, triggerTime)
            }
        }
        if (repository.isWaterRemindersEnabledOnce()) {
            repository.getAllWaterRemindersOnce().forEach { waterReminder ->
                val triggerTime = nextWaterAlarmTime(waterReminder)
                scheduleWaterReminder(waterReminder)
                repository.updateWaterReminderLastScheduledAt(waterReminder.id, triggerTime)
            }
        }
        scheduleDailySummary()
    }

    fun scheduleDailySummary() {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 20) // 8 PM
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        val intent = Intent(context, com.hariharan.rytm.receiver.DailySummaryReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            DAILY_SUMMARY_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pendingIntent)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pendingIntent)
        }
        Log.d("RytmAlarm", "Scheduled daily summary at ${cal.timeInMillis}")
    }

    fun postDailySummaryNotification(completed: Int, total: Int, waterPercent: Int) {
        val title = "🌙 Your Daily Summary"
        val message = "You completed $completed of $total habits today. Water intake is at $waterPercent%. Great job!"
        
        val intent = Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            context, DAILY_SUMMARY_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, SUMMARY_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        notificationManager.notify(DAILY_SUMMARY_ID, notification)
    }

    fun postMissedRoutineNotification(missedHabits: List<HabitWithReminders>) {
        if (missedHabits.isEmpty()) return

        val names = missedHabits.joinToString(", ") { it.habit.name }
        val title = "📅 You missed your routine"
        val message = if (missedHabits.size == 1) {
            "You missed: ${missedHabits[0].habit.name}. Keep your streak alive by completing it now!"
        } else {
            "You missed $names. Stay on track and complete them now!"
        }

        val intent = Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            context, MISSED_NOTIF_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Rytm Alarms",
            NotificationManager.IMPORTANCE_HIGH
        )
        notificationManager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        notificationManager.notify(MISSED_NOTIF_ID, notification)
    }

    fun postMissedWaterNotification(reminder: WaterReminder) {
        val title = "Missed Water Reminder"
        val message = "You missed your ${reminder.toDisplayTime()} reminder to drink ${reminder.amountMl}ml of water."

        val intent = Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            context, reminder.id.toInt() + WATER_ID_OFFSET + 100, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, WATER_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        notificationManager.notify(reminder.id.toInt() + WATER_ID_OFFSET + 100, notification)
    }

    fun postMissedWaterSummaryNotification(missed: List<WaterReminder>) {
        if (missed.isEmpty()) return
        if (missed.size == 1) {
            postMissedWaterNotification(missed[0])
            return
        }

        val totalMl = missed.sumOf { it.amountMl }
        val title = "Missed Water Reminders"
        val message = "You missed ${missed.size} reminders (${totalMl}ml total). Stay hydrated and drink up now!"

        val intent = Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            context, MISSED_WATER_SUMMARY_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, WATER_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        notificationManager.notify(MISSED_WATER_SUMMARY_ID, notification)
    }

    fun postMissedHabitNotification(habitName: String, reminderId: Long, missedYesterday: Boolean = false) {
        val title = if (missedYesterday) "⚠️ Don't miss twice" else "📅 Missed Routine"
        val message = if (missedYesterday) {
            "You already missed $habitName yesterday. Never miss twice — do it now to stay who you want to be."
        } else {
            "You missed your reminder for $habitName. Keep your streak alive by completing it now!"
        }

        val intent = Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            context, reminderId.toInt() + 500, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        notificationManager.notify(reminderId.toInt() + 500, notification)
    }

    private fun buildAlarmIntent(habit: Habit, reminder: Reminder, triggerTime: Long): Intent {
        return Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_TYPE, TYPE_HABIT)
            putExtra(EXTRA_HABIT_ID, habit.id)
            putExtra(EXTRA_HABIT_NAME, habit.name)
            putExtra(EXTRA_HABIT_EMOJI, habit.iconEmoji)
            putExtra(EXTRA_HABIT_DESCRIPTION, habit.description)
            putExtra(EXTRA_REMINDER_ID, reminder.id)
            putExtra(EXTRA_ALARM_SOUND_URI, habit.alarmSoundUri)
            putExtra(EXTRA_SCHEDULED_TIME, triggerTime)
        }
    }

    private fun nextAlarmTime(reminder: Reminder): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, reminder.hour)
            set(Calendar.MINUTE, reminder.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return cal.timeInMillis
    }

    private fun nextWaterAlarmTime(reminder: WaterReminder): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, reminder.hour)
            set(Calendar.MINUTE, reminder.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return cal.timeInMillis
    }

    companion object {
        const val EXTRA_TYPE = "type"
        const val TYPE_HABIT = "habit"
        const val TYPE_WATER = "water"
        const val WATER_ID_OFFSET = 100000

        const val EXTRA_WATER_AMOUNT = "water_amount"

        const val EXTRA_HABIT_ID = "habit_id"
        const val EXTRA_HABIT_NAME = "habit_name"
        const val EXTRA_HABIT_EMOJI = "habit_emoji"
        const val EXTRA_HABIT_DESCRIPTION = "habit_description"
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_ALARM_SOUND_URI = "alarm_sound_uri"
        const val EXTRA_SCHEDULED_TIME = "scheduled_time"
        const val NOTIFICATION_CHANNEL_ID = "rytm_habit_alarms"
        const val WATER_NOTIFICATION_CHANNEL_ID = "rytm_water_reminders"
        const val SUMMARY_NOTIFICATION_CHANNEL_ID = "rytm_daily_summary"
        private const val MISSED_NOTIF_ID = 9999
        private const val MISSED_WATER_SUMMARY_ID = 9998
        private const val DAILY_SUMMARY_ID = 9997
        const val SNOOZE_ID_OFFSET = 20000
    }
}
