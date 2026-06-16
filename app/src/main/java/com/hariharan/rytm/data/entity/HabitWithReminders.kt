package com.hariharan.rytm.data.entity

import androidx.room.Embedded
import androidx.room.Relation

data class HabitWithReminders(
    @Embedded val habit: Habit,
    @Relation(
        parentColumn = "id",
        entityColumn = "habitId"
    )
    val reminders: List<Reminder>
)

