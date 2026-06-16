package com.hariharan.rytm.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "habits")
data class Habit(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val description: String = "",
    val iconEmoji: String = "⚡",
    val colorHex: String = "#6200EE",
    val alarmSoundUri: String = "",      // Empty = default ringtone
    val isActive: Boolean = true,
    val repeatDays: String = "1,2,3,4,5,6,7", // 1=Mon..7=Sun, comma-separated
    val createdAt: Long = System.currentTimeMillis(),
    // --- Atomic Habits fields ---
    val identity: String = "",         // Law 4: "I am a healthy person"
    val cue: String = "",              // Habit stacking: "After I pour my morning coffee"
    val twoMinuteVersion: String = ""  // 2-minute rule: "Just put on running shoes"
)

