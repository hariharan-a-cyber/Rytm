package com.hariharan.rytm.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import com.hariharan.rytm.R
import com.hariharan.rytm.data.entity.CompletionLog
import com.hariharan.rytm.data.entity.CompletionStatus
import com.hariharan.rytm.repository.HabitRepository
import com.hariharan.rytm.ui.alarm.AlarmRingActivity
import com.hariharan.rytm.ui.alarm.WaterRingActivity
import com.hariharan.rytm.utils.AlarmScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AlarmService : Service() {

    @Inject lateinit var repository: HabitRepository
    @Inject lateinit var alarmScheduler: AlarmScheduler

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    @Volatile private var alreadyHandled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Log.d("RytmAlarm", "Service: Null intent, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent.action == ACTION_STOP_ALARM) {
            Log.d("RytmAlarm", "Service: Stop action received")
            alreadyHandled = true
            stopAlarm()
            return START_NOT_STICKY
        }

        cleanupServiceState()
        acquireWakeLock()
        alreadyHandled = false

        val type = intent.getStringExtra(AlarmScheduler.EXTRA_TYPE) ?: AlarmScheduler.TYPE_HABIT
        val habitId = intent.getLongExtra(AlarmScheduler.EXTRA_HABIT_ID, -1L)
        val habitName = intent.getStringExtra(AlarmScheduler.EXTRA_HABIT_NAME) ?: "Habit"
        val habitEmoji = intent.getStringExtra(AlarmScheduler.EXTRA_HABIT_EMOJI) ?: "⚡"
        val habitDescription = intent.getStringExtra(AlarmScheduler.EXTRA_HABIT_DESCRIPTION) ?: ""
        val reminderId = intent.getLongExtra(AlarmScheduler.EXTRA_REMINDER_ID, -1L)
        val soundUri = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_SOUND_URI) ?: ""
        val scheduledTime = intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULED_TIME, 0L)
        val isSnoozed = intent.getBooleanExtra("is_snoozed", false)
        
        Log.d("RytmAlarm", "Service started: type=$type, reminderId=$reminderId, isSnoozed=$isSnoozed")

        if (scheduledTime == 0L || System.currentTimeMillis() - scheduledTime > 15 * 60 * 1000) {
            Log.d("RytmAlarm", "Stale alarm -> mark missed instead of dropping (time=$scheduledTime)")
            if (scheduledTime != 0L && !alreadyHandled) {
                alreadyHandled = true
                handleMiss(type, habitName, habitId, reminderId, scheduledTime)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannels()
        val notification = buildNotification(type, habitName, habitEmoji, habitId, habitDescription, reminderId, scheduledTime, intent, isSnoozed)
        
        startForeground(FOREGROUND_ID, notification)

        if (type == AlarmScheduler.TYPE_HABIT) {
            playAlarmSound(soundUri)
            startVibration()
        } else if (type == AlarmScheduler.TYPE_WATER) {
            startVibration()
        }
        
        scheduleTimeout(type, habitName, habitId, reminderId, scheduledTime)
        
        launchRingActivity(type, habitId, habitName, habitEmoji, habitDescription, reminderId, scheduledTime, intent)

        return START_NOT_STICKY
    }

    private fun launchRingActivity(
        type: String, habitId: Long, habitName: String, habitEmoji: String,
        habitDescription: String, reminderId: Long, scheduledTime: Long, incomingIntent: Intent?
    ) {
        val targetActivity = if (type == AlarmScheduler.TYPE_WATER) {
            WaterRingActivity::class.java
        } else {
            AlarmRingActivity::class.java
        }

        val ringIntent = Intent(this, targetActivity).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(AlarmScheduler.EXTRA_TYPE, type)
            putExtra(AlarmScheduler.EXTRA_HABIT_ID, habitId)
            putExtra(AlarmScheduler.EXTRA_HABIT_NAME, habitName)
            putExtra(AlarmScheduler.EXTRA_HABIT_EMOJI, habitEmoji)
            putExtra(AlarmScheduler.EXTRA_HABIT_DESCRIPTION, habitDescription)
            putExtra(AlarmScheduler.EXTRA_REMINDER_ID, reminderId)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_TIME, scheduledTime)
            
            if (type == AlarmScheduler.TYPE_WATER) {
                val amount = incomingIntent?.getIntExtra(AlarmScheduler.EXTRA_WATER_AMOUNT, 250) ?: 250
                putExtra(AlarmScheduler.EXTRA_WATER_AMOUNT, amount)
            }
        }
        startActivity(ringIntent)
    }

    private fun playAlarmSound(soundUriString: String) {
        try {
            val uri: Uri = if (soundUriString.isNotEmpty() && !soundUriString.startsWith("resource://")) {
                soundUriString.toUri()
            } else {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            }

            // Using create() is more standard for simple URIs and handles preparation automatically
            mediaPlayer = MediaPlayer.create(applicationContext, uri)?.apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                isLooping = true
                start()
            }
        } catch (e: Exception) {
            Log.e("RytmAlarm", "Error playing alarm sound", e)
        }
    }

    private fun startVibration() {
        try {
            vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            val pattern = longArrayOf(0, 800, 400, 800, 400)
            vibrator?.vibrate(
                VibrationEffect.createWaveform(pattern, 0)
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun buildNotification(
        type: String, habitName: String, habitEmoji: String,
        habitId: Long, habitDescription: String, reminderId: Long, scheduledTime: Long, 
        incomingIntent: Intent?, isSnoozed: Boolean
    ): Notification {
        val isWater = type == AlarmScheduler.TYPE_WATER
        val titlePrefix = if (isSnoozed) "[Snoozed] " else ""
        
        val targetActivity = if (isWater) WaterRingActivity::class.java else AlarmRingActivity::class.java
        
        val ringIntent = Intent(this, targetActivity).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(AlarmScheduler.EXTRA_TYPE, type)
            putExtra(AlarmScheduler.EXTRA_HABIT_ID, habitId)
            putExtra(AlarmScheduler.EXTRA_HABIT_NAME, habitName)
            putExtra(AlarmScheduler.EXTRA_HABIT_EMOJI, habitEmoji)
            putExtra(AlarmScheduler.EXTRA_HABIT_DESCRIPTION, habitDescription)
            putExtra(AlarmScheduler.EXTRA_REMINDER_ID, reminderId)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_TIME, scheduledTime)
            
            if (isWater) {
                val amount = incomingIntent?.getIntExtra(AlarmScheduler.EXTRA_WATER_AMOUNT, 250) ?: 250
                putExtra(AlarmScheduler.EXTRA_WATER_AMOUNT, amount)
            }
        }

        // Use reminderId for request code to ensure uniqueness for both habits and water
        val baseRequestCode = if (isWater) reminderId.toInt() + AlarmScheduler.WATER_ID_OFFSET else reminderId.toInt()

        val pi = PendingIntent.getActivity(
            this, baseRequestCode, ringIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = if (isWater) {
            AlarmScheduler.WATER_NOTIFICATION_CHANNEL_ID
        } else {
            AlarmScheduler.NOTIFICATION_CHANNEL_ID
        }

        val stopIntent = Intent(this, AlarmService::class.java).apply {
            action = ACTION_STOP_ALARM
        }
        val stopPi = PendingIntent.getService(
            this, baseRequestCode + 500, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$titlePrefix$habitEmoji Time for $habitName")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setDeleteIntent(stopPi)
            .addAction(0, "Dismiss", stopPi)
            .setColor(android.graphics.Color.BLACK)
            .setColorized(true)
            .apply {
                if (!isWater) {
                    setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                } else {
                    setSound(null)
                }
            }
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(false)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Habit Channel (High importance, with sound)
        val habitChannel = NotificationChannel(
            AlarmScheduler.NOTIFICATION_CHANNEL_ID,
            "Rytm Habit Alarms",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Habit reminder alarms"
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            enableVibration(true)
            setBypassDnd(true)
        }
        nm.createNotificationChannel(habitChannel)

        // Water Channel (High importance, but SILENT)
        val waterChannel = NotificationChannel(
            AlarmScheduler.WATER_NOTIFICATION_CHANNEL_ID,
            "Rytm Water Reminders",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Silent water reminders"
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            enableVibration(true)
            setBypassDnd(true)
            setSound(null, null)
        }
        nm.createNotificationChannel(waterChannel)
    }

    private fun scheduleTimeout(
        type: String, habitName: String, habitId: Long,
        reminderId: Long, scheduledTime: Long
    ) {
        timeoutRunnable = Runnable {
            if (!alreadyHandled) {
                alreadyHandled = true
                handleMiss(type, habitName, habitId, reminderId, scheduledTime)
            }
            stopAlarm()
        }
        handler.postDelayed(timeoutRunnable!!, 60000L)
    }

    // Runs on its OWN scope so stopSelf()/onDestroy() can never cancel the miss notification.
    private fun handleMiss(
        type: String, habitName: String, habitId: Long,
        reminderId: Long, scheduledTime: Long
    ) {
        val missScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        missScope.launch {
            if (type == AlarmScheduler.TYPE_WATER) {
                val reminder = repository.getWaterReminderById(reminderId)
                if (reminder != null) {
                    alarmScheduler.postMissedWaterNotification(reminder)
                    repository.logWaterReminderCompletion(reminderId, CompletionStatus.MISSED, scheduledTime)
                }
                alarmScheduler.rescheduleWaterForNextDay(repository, reminderId)
            } else {
                val missedYesterday = wasHabitMissedYesterday(habitId)
                alarmScheduler.postMissedHabitNotification(habitName, reminderId, missedYesterday)
                repository.logCompletion(
                    CompletionLog(
                        habitId = habitId,
                        reminderId = reminderId,
                        status = CompletionStatus.MISSED,
                        scheduledAt = scheduledTime
                    )
                )
                // A missed habit MUST still be rescheduled for the next day.
                val hwr = repository.getHabitWithReminders(habitId)
                hwr?.reminders?.find { it.id == reminderId }?.let { rem ->
                    alarmScheduler.rescheduleForNextDay(hwr.habit, rem)
                }
            }
        }
    }

    private suspend fun wasHabitMissedYesterday(habitId: Long): Boolean {
        val todayStart = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val yesterdayStart = todayStart - 24L * 60 * 60 * 1000
        val logs = repository.getLogsForHabitInRange(habitId, yesterdayStart, todayStart)
        return logs.any { it.status == CompletionStatus.MISSED }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Rytm:AlarmWakeLock"
        )
        wakeLock?.acquire(10 * 60 * 1000L /*10 minutes*/)
    }

    private fun cleanupServiceState() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        vibrator?.cancel()
        vibrator = null
        timeoutRunnable?.let { 
            Log.d("RytmAlarm", "Service: Cancelling timeout")
            handler.removeCallbacks(it) 
        }
        timeoutRunnable = null
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    fun stopAlarm() {
        cleanupServiceState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d("RytmAlarm", "Task removed, stopping alarm")
        stopAlarm()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.d("RytmAlarm", "Service destroyed")
        cleanupServiceState()
        super.onDestroy()
    }

    companion object {
        const val FOREGROUND_ID = 1001
        const val ACTION_STOP_ALARM = "com.hariharan.rytm.action.STOP_ALARM"
    }
}
