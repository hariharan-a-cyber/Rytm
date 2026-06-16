package com.hariharan.rytm.ui.water

import android.app.TimePickerDialog
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hariharan.rytm.R
import com.hariharan.rytm.data.entity.WaterReminder
import com.hariharan.rytm.databinding.FragmentWaterReminderBinding
import com.hariharan.rytm.viewmodel.WaterViewModel
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import nl.dionsegijn.konfetti.core.Party
import nl.dionsegijn.konfetti.core.Position
import nl.dionsegijn.konfetti.core.emitter.Emitter
import java.util.concurrent.TimeUnit

@AndroidEntryPoint
class WaterReminderFragment : Fragment() {

    private var _binding: FragmentWaterReminderBinding? = null
    private val binding get() = _binding!!

    private val viewModel: WaterViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWaterReminderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnChangeGoal.setOnClickListener { showSetGoalDialog() }
        binding.btnAddWater.setOnClickListener { 
            viewModel.addWater() 
        }
        binding.btnAddReminder.setOnClickListener { showTimePicker() }
        binding.switchReminders.setOnClickListener {
            val isChecked = binding.switchReminders.isChecked
            viewModel.toggleWaterReminders(isChecked)
            val message = if (isChecked) "Water reminders enabled" else "Water reminders disabled"
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        updateUi(state)
                        refreshReminderChips(state.reminders)
                    }
                }
                launch {
                    viewModel.waterRemindersEnabled.collect { enabled: Boolean ->
                        binding.switchReminders.isChecked = enabled
                        binding.chipGroupReminders.alpha = if (enabled) 1.0f else 0.5f
                        binding.btnAddReminder.isEnabled = enabled
                        for (i in 0 until binding.chipGroupReminders.childCount) {
                            binding.chipGroupReminders.getChildAt(i).isEnabled = enabled
                        }
                    }
                }
                launch {
                    viewModel.events.collectLatest { event ->
                        Toast.makeText(requireContext(), event, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun showTimePicker(existing: WaterReminder? = null) {
        val hour = existing?.hour ?: 8
        val minute = existing?.minute ?: 0
        TimePickerDialog(requireContext(), { _, h, m ->
            showAmountDialog(existing, h, m)
        }, hour, minute, false).show()
    }

    private fun showAmountDialog(existing: WaterReminder?, hour: Int, minute: Int) {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(existing?.amountMl?.toString() ?: "250")
            setSelection(text.length)
        }

        val container = FrameLayout(requireContext()).apply {
            val params = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 48
                marginEnd = 48
                topMargin = 16
                bottomMargin = 16
            }
            addView(input, params)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Set Water Amount")
            .setMessage("How many ml should you drink at this time?")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val amount = input.text.toString().toIntOrNull() ?: 250
                if (existing != null) {
                    viewModel.updateReminder(existing.copy(hour = hour, minute = minute, amountMl = amount))
                    Toast.makeText(requireContext(), "Reminder updated", Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.addReminder(hour, minute, amount)
                    Toast.makeText(requireContext(), "Reminder added", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshReminderChips(reminders: List<WaterReminder>) {
        binding.chipGroupReminders.removeAllViews()
        val enabled = viewModel.waterRemindersEnabled.value
        reminders.sortedBy { it.hour * 60 + it.minute }.forEach { reminder ->
            val chip = Chip(requireContext()).apply {
                text = "${reminder.toDisplayTime()} • ${reminder.amountMl}ml"
                isCloseIconVisible = true
                isEnabled = enabled
                setOnClickListener { showTimePicker(reminder) }
                setOnCloseIconClickListener {
                    viewModel.deleteReminder(reminder)
                    Toast.makeText(requireContext(), "Reminder removed", Toast.LENGTH_SHORT).show()
                }
            }
            binding.chipGroupReminders.addView(chip)
        }
    }

    private fun updateUi(state: WaterViewModel.WaterUiState) {
        binding.tvWaterCount.text = state.count.toString()
        binding.tvWaterMl.text = getString(R.string.water_ml_format, state.totalMl, state.trueTargetMl)
        binding.tvWaterGoal.text = getString(R.string.water_target_only_format, state.trueTargetMl)
        
        binding.tvGoalHint.visibility = if (state.manualGoal <= 0 && state.remindersSum <= 0) View.VISIBLE else View.GONE
        
        binding.progressWater.max = if (state.trueTargetMl > 0) state.trueTargetMl else 2000
        binding.progressWater.progress = state.totalMl

        val previousMlString = binding.tvWaterMl.tag?.toString() ?: "0"
        val previousTotalMl = previousMlString.toIntOrNull() ?: 0
        binding.tvWaterMl.tag = state.totalMl.toString()

        if (state.totalMl >= state.trueTargetMl && previousTotalMl < state.trueTargetMl && state.trueTargetMl > 0) {
            triggerCelebration()
            Toast.makeText(requireContext(), "Goal reached! Amazing job!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun triggerCelebration() {
        val party = Party(
            speed = 0f,
            maxSpeed = 30f,
            damping = 0.9f,
            spread = 360,
            colors = listOf(0xfce18a, 0xff726d, 0xb48def, 0xf4306d),
            position = Position.Relative(0.5, 0.3),
            emitter = Emitter(duration = 100, TimeUnit.MILLISECONDS).max(100)
        )
        binding.konfettiView.start(party)
    }

    private fun showSetGoalDialog() {
        val state = viewModel.uiState.value
        val displayGoal = state.trueTargetMl
        
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(displayGoal.toString())
            setSelection(text.length)
        }
        
        val container = FrameLayout(requireContext()).apply {
            val params = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 48
                marginEnd = 48
                topMargin = 16
                bottomMargin = 16
            }
            addView(input, params)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Set Daily Hydration Target")
            .setMessage("Enter your target in milliliters (ml):")
            .setView(container)
            .setPositiveButton("Set") { _, _ ->
                val newGoal = input.text.toString().toIntOrNull() ?: displayGoal
                viewModel.setGoal(newGoal)
                Toast.makeText(requireContext(), "Daily target updated to ${newGoal}ml", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

