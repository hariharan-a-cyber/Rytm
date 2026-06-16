package com.hariharan.rytm.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hariharan.rytm.data.entity.CompletionStatus
import com.hariharan.rytm.data.entity.WaterLog
import com.hariharan.rytm.repository.HabitRepository
import com.hariharan.rytm.utils.AlarmScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

@AndroidEntryPoint
class DailySummaryReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: HabitRepository
    @Inject lateinit var alarmScheduler: AlarmScheduler

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        Log.d("RytmAlarm", "DailySummaryReceiver fired")
        
        val pendingResult = goAsync()
        scope.launch {
            try {
                val todayStart = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                
                val now = System.currentTimeMillis()
                
                // Get habit stats
                val habits = repository.getAllActiveHabits().first()
                val totalHabits = habits.size
                
                val logs = repository.getAllLogs().first()
                val completedToday = logs.count { 
                    it.status == CompletionStatus.COMPLETED && it.completedAt >= todayStart 
                }
                
                // Get water stats
                val todayDate = WaterLog.getCurrentDate()
                val waterLog = repository.getWaterLogForDate(todayDate).first()
                
                val activeWaterReminders = repository.getAllWaterRemindersOnce().filter { it.isActive }
                val targetMl = activeWaterReminders.sumOf { it.amountMl }.coerceAtLeast(2000)
                
                val waterPercent = if (waterLog != null && targetMl > 0) {
                    (waterLog.totalMl * 100) / targetMl
                } else 0
                
                alarmScheduler.postDailySummaryNotification(completedToday, totalHabits, waterPercent)
                
                // Reschedule for tomorrow
                alarmScheduler.scheduleDailySummary()
                
            } catch (e: Exception) {
                Log.e("RytmAlarm", "Error in DailySummaryReceiver", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
