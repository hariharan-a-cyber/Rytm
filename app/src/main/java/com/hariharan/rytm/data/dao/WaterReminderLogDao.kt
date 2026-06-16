package com.hariharan.rytm.data.dao

import androidx.room.*
import com.hariharan.rytm.data.entity.WaterReminderLog
import kotlinx.coroutines.flow.Flow

@Dao
interface WaterReminderLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: WaterReminderLog): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLogs(logs: List<WaterReminderLog>)

    @Query("SELECT * FROM water_reminder_logs WHERE reminderId = :reminderId AND scheduledAt = :scheduledAt LIMIT 1")
    suspend fun getLogForReminderAt(reminderId: Long, scheduledAt: Long): WaterReminderLog?

    @Query("SELECT * FROM water_reminder_logs WHERE reminderId = :reminderId AND scheduledAt >= :dayStart AND scheduledAt < :dayEnd LIMIT 1")
    suspend fun getLogForReminderOnDay(reminderId: Long, dayStart: Long, dayEnd: Long): WaterReminderLog?

    @Query("SELECT * FROM water_reminder_logs WHERE scheduledAt >= :startTime AND scheduledAt <= :endTime")
    suspend fun getLogsInRange(startTime: Long, endTime: Long): List<WaterReminderLog>

    @Query("SELECT * FROM water_reminder_logs WHERE reminderId = :reminderId ORDER BY scheduledAt DESC")
    fun getLogsForReminder(reminderId: Long): Flow<List<WaterReminderLog>>

    @Query("SELECT * FROM water_reminder_logs")
    suspend fun getAllLogsOnce(): List<WaterReminderLog>
}
