package com.hariharan.rytm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Application
import android.app.Notification
import com.hariharan.rytm.utils.AlarmScheduler
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class RytmApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        val habitChannel = NotificationChannel(
            AlarmScheduler.NOTIFICATION_CHANNEL_ID,
            "Rytm Habit Alarms",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Habit reminder alarms and missed-habit alerts"
            enableVibration(true); setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(habitChannel)

        val waterChannel = NotificationChannel(
            AlarmScheduler.WATER_NOTIFICATION_CHANNEL_ID,
            "Rytm Water Reminders",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Water reminders and missed-water alerts"
            enableVibration(true); setBypassDnd(true); setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(waterChannel)

        val summaryChannel = NotificationChannel(
            AlarmScheduler.SUMMARY_NOTIFICATION_CHANNEL_ID,
            "Rytm Daily Summary",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Your end-of-day progress summary"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(summaryChannel)
    }
}