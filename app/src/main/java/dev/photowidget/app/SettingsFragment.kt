package dev.photowidget.app

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dev.photowidget.app.databinding.FragmentSettingsBinding
import dev.photowidget.model.TapAction
import dev.photowidget.model.WidgetMode
import dev.photowidget.widget.WidgetRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * App-wide defaults and the two system controls that affect widget timing.
 * Everything here is optional: the app works without granting either.
 */
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val graph get() = WidgetRuntime.get(requireContext())
    private val prefs get() = graph.preferences

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentSettingsBinding.bind(view)

        bindDefaults()
        bindScheduling()
        bindTools()
        bindAbout()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) refreshSchedulingState()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun bindDefaults() {
        val context = requireContext()

        binding.defaultMode.setSimpleItems(
            WidgetMode.entries.map { Labels.mode(context, it) }.toTypedArray(),
        )
        binding.defaultMode.setText(Labels.mode(context, prefs.defaultMode), false)
        binding.defaultMode.setOnItemClickListener { _, _, position, _ ->
            prefs.defaultMode = WidgetMode.entries[position]
        }

        binding.defaultInterval.setOnClickListener {
            IntervalDialog.show(context, prefs.defaultIntervalSeconds) { seconds ->
                prefs.defaultIntervalSeconds = seconds
                updateIntervalButton()
            }
        }
        updateIntervalButton()

        // The slider only accepts values on its 5% grid.
        binding.defaultCooldownSlider.value = (prefs.defaultCooldownPercent / 5 * 5).toFloat()
        binding.defaultCooldownValue.text = getString(
            R.string.cooldown_value,
            prefs.defaultCooldownPercent,
        )
        binding.defaultCooldownSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val percent = value.roundToInt()
            prefs.defaultCooldownPercent = percent
            binding.defaultCooldownValue.text = getString(R.string.cooldown_value, percent)
        }

        val taps = TapAction.entries
        binding.defaultTap.setSimpleItems(
            taps.map { Labels.tapAction(context, it) }.toTypedArray(),
        )
        binding.defaultTap.setText(Labels.tapAction(context, prefs.defaultTapAction), false)
        binding.defaultTap.setOnItemClickListener { _, _, position, _ ->
            prefs.defaultTapAction = taps[position]
        }

        binding.defaultTransitions.isChecked = prefs.defaultTransitions
        binding.defaultTransitions.setOnCheckedChangeListener { _, checked ->
            prefs.defaultTransitions = checked
        }
    }

    private fun updateIntervalButton() {
        binding.defaultInterval.text = getString(R.string.default_interval) +
            " · " + Format.duration(prefs.defaultIntervalSeconds)
    }

    private fun bindScheduling() {
        binding.exactAlarmsButton.setOnClickListener {
            SchedulingHelp.openExactAlarmSettings(requireContext())
        }
        binding.batteryButton.setOnClickListener {
            SchedulingHelp.openBatterySettings(requireContext())
        }
        refreshSchedulingState()
    }

    private fun refreshSchedulingState() {
        val context = requireContext()
        binding.exactAlarmsState.setText(
            if (SchedulingHelp.canUseExactAlarms(context)) {
                R.string.exact_alarms_allowed
            } else {
                R.string.exact_alarms_not_allowed
            },
        )
        binding.batteryState.setText(
            if (SchedulingHelp.isIgnoringBatteryOptimizations(context)) {
                R.string.battery_unrestricted
            } else {
                R.string.battery_optimised
            },
        )
    }

    private fun bindTools() {
        binding.refreshAll.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                graph.coordinator.refreshAll()
                snack(R.string.refresh_all_done)
            }
        }
        binding.resetAllHistories.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.reset_all_histories)
                .setMessage(R.string.reset_all_warning)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_reset_history) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        graph.repository.resetAllHistories()
                        snack(R.string.reset_all_histories_done)
                    }
                }
                .show()
        }
    }

    private fun bindAbout() {
        val version = runCatching {
            requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0)
                .versionName
        }.getOrNull() ?: "1.0"
        binding.aboutVersion.text = getString(R.string.about_version, version)

        binding.viewLicense.setOnClickListener {
            showAsset(R.string.about_license_action, "LICENSE")
        }
        binding.viewDependencies.setOnClickListener {
            showAsset(R.string.about_dependencies, "THIRD_PARTY_LICENSES.md")
        }
    }

    private fun showAsset(titleRes: Int, asset: String) {
        val context = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.assets.open(asset).bufferedReader().use { it.readText() }
                }.getOrNull()
            }
            if (_binding == null) return@launch
            MaterialAlertDialogBuilder(context)
                .setTitle(titleRes)
                .setMessage(text?.takeIf { it.isNotBlank() } ?: getString(R.string.license_unavailable))
                .setPositiveButton(R.string.action_close, null)
                .show()
        }
    }

    private fun snack(messageRes: Int) {
        val root = _binding?.root ?: return
        Snackbar.make(root, messageRes, Snackbar.LENGTH_SHORT).show()
    }
}
