package com.hariharan.rytm.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hariharan.rytm.data.entity.WaterLog
import com.hariharan.rytm.data.entity.WaterReminder
import com.hariharan.rytm.repository.HabitRepository
import com.hariharan.rytm.utils.AlarmScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WaterViewModel @Inject constructor(
    private val repository: HabitRepository,
    private val alarmScheduler: AlarmScheduler,
) : ViewModel() {

    private val _events = MutableSharedFlow<String>()
    val events: SharedFlow<String> = _events.asSharedFlow()

    val waterLog: Flow<WaterLog?> = repository.getWaterLogForDate(WaterLog.getCurrentDate())
        .onEach { log ->
            if (log == null) {
                repository.ensureWaterLogExists(WaterLog.getCurrentDate())
            }
        }

    val reminders: Flow<List<WaterReminder>> = repository.getAllWaterReminders()

    data class WaterUiState(
        val count: Int = 0,
        val manualGoal: Int = 0,
        val totalMl: Int = 0,
        val remindersSum: Int = 0,
        val trueTargetMl: Int = 0,
        val reminders: List<WaterReminder> = emptyList()
    )

    val uiState: StateFlow<WaterUiState> = combine(waterLog, reminders) { log, reminderList ->
        val activeReminders = reminderList.filter { it.isActive }
        val remindersSum = activeReminders.sumOf { it.amountMl }
        val manualGoal = log?.goal ?: 0
        
        // Logic: Use manual goal unless reminders sum exceeds it
        val trueTargetMl = if (manualGoal > 0) {
            if (remindersSum > manualGoal) remindersSum else manualGoal
        } else {
            remindersSum
        }

        WaterUiState(
            count = log?.count ?: 0,
            manualGoal = manualGoal,
            totalMl = log?.totalMl ?: 0,
            remindersSum = remindersSum,
            trueTargetMl = trueTargetMl,
            reminders = reminderList
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = WaterUiState()
    )

    private val _waterRemindersEnabled = MutableStateFlow(value = true)
    val waterRemindersEnabled = _waterRemindersEnabled.asStateFlow()

    init {
        viewModelScope.launch {
            _waterRemindersEnabled.value = repository.isWaterRemindersEnabledOnce()
        }
    }

    fun toggleWaterReminders(enabled: Boolean) {
        viewModelScope.launch {
            repository.saveSetting(HabitRepository.KEY_WATER_REMINDERS_ENABLED, enabled.toString())
            _waterRemindersEnabled.value = enabled
            
            if (enabled) {
                // Reschedule all active reminders
                repository.getAllWaterRemindersOnce().forEach { reminder ->
                    if (reminder.isActive) {
                        alarmScheduler.scheduleWaterReminder(reminder)
                    }
                }
            } else {
                // Cancel all water alarms
                repository.getAllWaterRemindersOnce().forEach { reminder ->
                    alarmScheduler.cancelWaterReminder(reminder.id)
                }
            }
        }
    }

    fun addWater() {
        viewModelScope.launch {
            val success = repository.addWaterWithLimit(WaterLog.getCurrentDate(), 250) // Default 250ml for quick add
            if (!success) {
                _events.emit("Hydration target already reached!")
            }
        }
    }

    fun setGoal(goal: Int) {
        viewModelScope.launch {
            repository.updateWaterGoal(WaterLog.getCurrentDate(), goal)
        }
    }

    fun addReminder(hour: Int, minute: Int, amountMl: Int) {
        viewModelScope.launch {
            val id = repository.insertWaterReminder(
                WaterReminder(hour = hour, minute = minute, amountMl = amountMl)
            )
            if (_waterRemindersEnabled.value) {
                alarmScheduler.scheduleWaterReminder(
                    WaterReminder(id = id, hour = hour, minute = minute, amountMl = amountMl)
                )
            }
        }
    }

    fun updateReminder(reminder: WaterReminder) {
        viewModelScope.launch {
            repository.updateWaterReminder(reminder)
            if (_waterRemindersEnabled.value) {
                alarmScheduler.scheduleWaterReminder(reminder)
            }
        }
    }

    fun deleteReminder(reminder: WaterReminder) {
        viewModelScope.launch {
            repository.deleteWaterReminder(reminder)
            alarmScheduler.cancelWaterReminder(reminder.id)
        }
    }
}
