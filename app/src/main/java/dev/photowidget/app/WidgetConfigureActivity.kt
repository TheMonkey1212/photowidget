package dev.photowidget.app

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import dev.photowidget.app.databinding.ActivityWidgetConfigureBinding
import dev.photowidget.app.databinding.ItemSourceBinding
import dev.photowidget.model.AlbumInfo
import dev.photowidget.model.BackgroundStyle
import dev.photowidget.model.ImageFit
import dev.photowidget.model.OverlayPosition
import dev.photowidget.model.PhotoRef
import dev.photowidget.model.PhotoSource
import dev.photowidget.model.PhotoSourceType
import dev.photowidget.model.RenderQuality
import dev.photowidget.model.TapAction
import dev.photowidget.model.WidgetAppearance
import dev.photowidget.model.WidgetConfig
import dev.photowidget.model.WidgetLimits
import dev.photowidget.model.WidgetMode
import dev.photowidget.model.WidgetStyle
import dev.photowidget.photos.DocumentAccess
import dev.photowidget.photos.PhotoAccessException
import dev.photowidget.photos.PhotoPermissions
import dev.photowidget.widget.UpdateReason
import dev.photowidget.widget.WidgetGraph
import dev.photowidget.widget.DUPLICATE_WINDOW_MS
import dev.photowidget.widget.WidgetIntents
import dev.photowidget.widget.WidgetRuntime
import dev.photowidget.widget.isUnconfiguredStub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/**
 * Photo selection, mode, timing, appearance and tap action for one widget.
 *
 * The screen edits an in-memory draft and only writes to the database on Save, so
 * dismissing it never leaves a half-configured widget behind. A duplicate that
 * the launcher is pinning is adopted as the starting draft.
 */
open class WidgetConfigureActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWidgetConfigureBinding
    private lateinit var graph: WidgetGraph

    private var widgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    private var config: WidgetConfig = WidgetConfig(widgetId = -1)
    private var sources: MutableList<PhotoSource> = mutableListOf()
    private var previewPhotos: List<PhotoRef> = emptyList()
    /** Why the sources could not be read, shown in the preview when nothing resolves. */
    private var previewIssue: String? = null
    private var existing = false
    private var loaded = false
    private var updatingControls = false
    private var intervalSeconds: Long = WidgetLimits.DEFAULT_INTERVAL_SECONDS
    private var intervalUnitIndex = 0
    private var previewJob: Job? = null
    private var photosJob: Job? = null

    private val intervalUnits: Array<String> by lazy {
        arrayOf(
            getString(R.string.interval_unit_seconds),
            getString(R.string.interval_unit_minutes),
            getString(R.string.interval_unit_hours),
        )
    }

    private val pickPhotos = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(100),
    ) { uris -> addPhotos(uris) }

    private val pickSinglePhoto = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> setSinglePhoto(uri) }

    private val openFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> addFolder(uri) }

    private val requestAlbumAccess = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.any { it }) {
            showAlbumPicker()
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.library_album_access)
                .setMessage(R.string.permission_denied_message)
                .setPositiveButton(R.string.action_done, null)
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWidgetConfigureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // The launcher's add-widget flow uses EXTRA_APPWIDGET_ID; in-app edit and the
        // widget's "open settings" tap come through WidgetIntents with EXTRA_WIDGET_ID.
        widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            intent.getIntExtra(WidgetIntents.EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID),
        )
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        graph = WidgetRuntime.get(this)

        binding.toolbar.setNavigationOnClickListener { cancelEditing() }
        binding.cancelButton.setOnClickListener { cancelEditing() }
        binding.saveButton.setOnClickListener { save() }
        binding.saveButton.isEnabled = false
        binding.preview.bind(graph.renderer)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = cancelEditing()
        })

        setupSections()
        setupDropdowns()
        setupStyleChips()
        setupSliders()
        setupSwitches()
        setupButtons()

        if (!restoreDraft(savedInstanceState)) {
            lifecycleScope.launch { loadDraft() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (loaded) {
            outState.putString(
                STATE_CONFIG,
                json.encodeToString(
                    WidgetConfig.serializer(),
                    config.copy(sources = sources.toList()),
                ),
            )
            outState.putBoolean(STATE_EXISTING, existing)
            outState.putInt(STATE_INTERVAL_UNIT, intervalUnitIndex)
            outState.putString(STATE_INTERVAL_TEXT, binding.intervalValue.text?.toString())
        }
    }

    // ------------------------------------------------------------------ load

    private fun restoreDraft(state: Bundle?): Boolean {
        val raw = state?.getString(STATE_CONFIG) ?: return false
        val restored = runCatching {
            json.decodeFromString(WidgetConfig.serializer(), raw)
        }.getOrNull() ?: return false
        existing = state.getBoolean(STATE_EXISTING, false)
        adoptDraft(restored)
        restoreIntervalInputs(state)
        return true
    }

    /**
     * Puts back the unit and the exact text the user had typed. Deriving the unit from
     * the interval alone would turn "5" in seconds into "5" in minutes after a
     * rotation, and an out-of-range value must not be replaced by the last valid one.
     */
    private fun restoreIntervalInputs(state: Bundle) {
        val unit = state.getInt(STATE_INTERVAL_UNIT, intervalUnitIndex)
        val text = state.getString(STATE_INTERVAL_TEXT)
        updatingControls = true
        if (unit in 0..2) intervalUnitIndex = unit
        binding.intervalUnit.setText(intervalUnits[intervalUnitIndex], false)
        binding.intervalValue.setText(
            text ?: IntervalMath.displayValue(intervalSeconds, intervalUnitIndex).toString(),
        )
        updatingControls = false
        validateInterval()
    }

    private suspend fun loadDraft() {
        val now = System.currentTimeMillis()
        val stored = graph.repository.getWidget(widgetId)
        val pendingId = graph.preferences.pendingDuplicateSource
        val pendingAt = graph.preferences.pendingDuplicateAt
        val pendingAge = now - pendingAt
        val duplicate = if (
            pendingId > 0 &&
            pendingAge in 0 until DUPLICATE_WINDOW_MS &&
            isUnconfiguredStub(stored, pendingAt)
        ) {
            graph.repository.getWidget(pendingId)
        } else {
            null
        }
        // The marker is only cleared on save or cancel, so a reload after process death
        // can still adopt the same copy instead of falling back to a default widget.

        // A stub the launcher created for the copy is replaced by the source widget's
        // configuration, so the screen must not treat it as an existing widget.
        existing = stored != null && duplicate == null
        val draft = when {
            stored != null -> stored
            duplicate != null -> duplicate.copy(
                widgetId = widgetId,
                name = "${duplicate.name} copy".take(60),
                cycleId = 1L,
                sequence = 0L,
                lastPhotoKey = null,
                lastDailyEpochDay = 0L,
                nextUpdateAt = 0L,
                createdAt = now,
                updatedAt = now,
            )

            else -> graph.preferences.newWidgetConfig(widgetId, now)
        }
        adoptDraft(draft)
    }

    private fun adoptDraft(draft: WidgetConfig) {
        config = draft
        intervalSeconds = draft.intervalSeconds
        intervalUnitIndex = IntervalMath.bestUnit(intervalSeconds)
        sources = draft.sources.toMutableList()
        binding.toolbar.title = getString(
            if (existing) R.string.configure_title_edit else R.string.configure_title_new,
        )
        bindAll()
        refreshPreviewPhotos()
        refreshHistoryCount()
        loaded = true
        binding.saveButton.isEnabled = true
        schedulePreview()
    }

    // ------------------------------------------------------------------- bind

    private fun bindAll() {
        updatingControls = true
        val a = config.appearance.sanitized()
        binding.nameInput.setText(config.name)
        binding.modeInput.setText(Labels.mode(this, config.mode), false)
        binding.modeDescription.text = Labels.modeDescription(this, config.mode)
        binding.tapInput.setText(Labels.tapAction(this, config.tapAction), false)
        binding.qualityInput.setText(Labels.quality(this, a.renderQuality), false)
        binding.backgroundInput.setText(Labels.background(this, a.backgroundStyle), false)
        binding.overlayPositionInput.setText(Labels.overlayPosition(this, a.overlay.position), false)

        binding.intervalUnit.setText(intervalUnits[intervalUnitIndex], false)
        binding.intervalValue.setText(
            IntervalMath.displayValue(intervalSeconds, intervalUnitIndex).toString(),
        )

        setSliderValue(binding.cooldownSlider, config.cooldownPercent.toFloat())
        setSliderValue(binding.collageSlider, config.collageCells.toFloat())
        setSliderValue(binding.cornerSlider, a.cornerRadiusDp)
        setSliderValue(binding.marginSlider, a.marginDp)
        setSliderValue(binding.paddingSlider, a.paddingDp)
        setSliderValue(binding.borderSlider, a.borderWidthDp)
        setSliderValue(binding.shadowStrengthSlider, a.shadowStrength)
        setSliderValue(binding.focusXSlider, a.focusX)
        setSliderValue(binding.focusYSlider, a.focusY)
        setSliderValue(binding.blurSlider, a.backgroundBlur)
        setSliderValue(binding.dimSlider, a.backgroundDim)
        setSliderValue(binding.overlaySizeSlider, a.overlay.textSizeSp)
        setSliderValue(binding.overlayScrimSlider, a.overlay.scrimStrength)
        setSliderValue(binding.collageGapSlider, a.collageGapDp)

        binding.shadowSwitch.isChecked = a.shadowEnabled
        binding.cardSwitch.isChecked = a.showCard
        binding.overlayDateSwitch.isChecked = a.overlay.showDate
        binding.overlayTimeSwitch.isChecked = a.overlay.showTime
        binding.overlayNameSwitch.isChecked = a.overlay.showPhotoName
        binding.overlayPhotoDateSwitch.isChecked = a.overlay.showPhotoDate
        binding.overlayScrimSwitch.isChecked = a.overlay.scrim
        binding.transitionsSwitch.isChecked = config.transitions
        binding.overlayCustomInput.setText(a.overlay.customText)
        binding.overlayDatePattern.setText(a.overlay.datePattern)
        binding.overlayTimePattern.setText(a.overlay.timePattern)

        binding.fitToggle.check(fitButtonId(a.imageFit))

        // Normalise persisted values to the granularity the UI can represent.
        config = config.copy(
            cooldownPercent = binding.cooldownSlider.value.roundToInt(),
            collageCells = binding.collageSlider.value.roundToInt(),
            appearance = a.copy(
                cornerRadiusDp = binding.cornerSlider.value,
                marginDp = binding.marginSlider.value,
                paddingDp = binding.paddingSlider.value,
                borderWidthDp = binding.borderSlider.value,
                shadowStrength = binding.shadowStrengthSlider.value,
                focusX = binding.focusXSlider.value,
                focusY = binding.focusYSlider.value,
                backgroundBlur = binding.blurSlider.value,
                backgroundDim = binding.dimSlider.value,
                collageGapDp = binding.collageGapSlider.value,
                overlay = a.overlay.copy(
                    textSizeSp = binding.overlaySizeSlider.value,
                    scrimStrength = binding.overlayScrimSlider.value,
                ),
            ).sanitized(),
        ).sanitized()

        updatingControls = false
        updateAppearanceLabels()
        updateAppearanceUi()
        updateIntervalFields()
        updateModeUi()
        refreshSourcesUi()
        binding.historyGroup.isVisible = existing
    }

    private fun setupSections() {
        binding.photosHeader.setTitle(getString(R.string.section_photos))
        binding.photosHeader.attach(binding.photosBody, initiallyExpanded = true)
        binding.rotationHeader.setTitle(getString(R.string.mode_title))
        binding.rotationHeader.attach(binding.rotationBody, initiallyExpanded = true)
        binding.timingHeader.setTitle(getString(R.string.section_timing))
        binding.timingHeader.attach(binding.timingBody, initiallyExpanded = true)
        binding.lookHeader.setTitle(getString(R.string.section_appearance))
        binding.lookHeader.attach(binding.lookBody, initiallyExpanded = true)
        binding.overlayHeader.setTitle(getString(R.string.section_overlay))
        binding.overlayHeader.attach(binding.overlayBody, initiallyExpanded = false)
        binding.behaviorHeader.setTitle(getString(R.string.section_tap))
        binding.behaviorHeader.attach(binding.behaviorBody, initiallyExpanded = false)
    }

    /** One-tap visual presets; the individual sliders stay editable afterwards. */
    private fun setupStyleChips() {
        binding.styleGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (updatingControls) return@setOnCheckedStateChangeListener
            val style = styleChips[checkedIds.firstOrNull()] ?: return@setOnCheckedStateChangeListener
            mutateAppearance { style.applyTo(it) }
        }
    }

    private fun setupDropdowns() {
        binding.modeInput.setSimpleItems(
            WidgetMode.entries.map { Labels.mode(this, it) }.toTypedArray(),
        )
        binding.modeInput.setOnItemClickListener { _, _, position, _ ->
            if (updatingControls) return@setOnItemClickListener
            val mode = WidgetMode.entries[position]
            if (mode == config.mode) return@setOnItemClickListener
            config = config.copy(mode = mode).sanitized()
            updateModeUi()
            schedulePreview()
        }

        binding.intervalUnit.setSimpleItems(intervalUnits)
        binding.intervalUnit.setOnItemClickListener { _, _, position, _ ->
            if (updatingControls) return@setOnItemClickListener
            // The number in the field is what the user means: keep it and reinterpret it
            // with the new unit instead of truncating "93" to "1" for minutes.
            val typed = binding.intervalValue.text?.toString()?.toLongOrNull()
            val retyped = typed?.let { IntervalMath.secondsOrNull(it, position) }
            intervalUnitIndex = position
            intervalSeconds = retyped
                ?: IntervalMath.secondsOrNull(
                    IntervalMath.displayValue(intervalSeconds, position),
                    position,
                )
                ?: intervalSeconds
            config = config.copy(intervalSeconds = intervalSeconds)
            updateIntervalFields()
        }

        binding.qualityInput.setSimpleItems(
            RenderQuality.entries.map { Labels.quality(this, it) }.toTypedArray(),
        )
        binding.qualityInput.setOnItemClickListener { _, _, position, _ ->
            if (updatingControls) return@setOnItemClickListener
            mutateAppearance { it.copy(renderQuality = RenderQuality.entries[position]) }
        }

        binding.backgroundInput.setSimpleItems(
            BackgroundStyle.entries.map { Labels.background(this, it) }.toTypedArray(),
        )
        binding.backgroundInput.setOnItemClickListener { _, _, position, _ ->
            if (updatingControls) return@setOnItemClickListener
            mutateAppearance { it.copy(backgroundStyle = BackgroundStyle.entries[position]) }
            updateAppearanceUi()
        }

        binding.overlayPositionInput.setSimpleItems(
            OverlayPosition.entries.map { Labels.overlayPosition(this, it) }.toTypedArray(),
        )
        binding.overlayPositionInput.setOnItemClickListener { _, _, position, _ ->
            if (updatingControls) return@setOnItemClickListener
            mutateAppearance { it.copy(overlay = it.overlay.copy(position = OverlayPosition.entries[position])) }
        }

        binding.tapInput.setSimpleItems(
            TapAction.entries.map { Labels.tapAction(this, it) }.toTypedArray(),
        )
        binding.tapInput.setOnItemClickListener { _, _, position, _ ->
            if (updatingControls) return@setOnItemClickListener
            config = config.copy(tapAction = TapAction.entries[position])
        }
    }

    private fun setupSliders() {
        onAppearanceSlider(binding.cornerSlider) { a, v -> a.copy(cornerRadiusDp = v) }
        onAppearanceSlider(binding.marginSlider) { a, v -> a.copy(marginDp = v) }
        onAppearanceSlider(binding.paddingSlider) { a, v -> a.copy(paddingDp = v) }
        onAppearanceSlider(binding.borderSlider) { a, v -> a.copy(borderWidthDp = v) }
        onAppearanceSlider(binding.shadowStrengthSlider) { a, v -> a.copy(shadowStrength = v) }
        onAppearanceSlider(binding.focusXSlider) { a, v -> a.copy(focusX = v) }
        onAppearanceSlider(binding.focusYSlider) { a, v -> a.copy(focusY = v) }
        onAppearanceSlider(binding.blurSlider) { a, v -> a.copy(backgroundBlur = v) }
        onAppearanceSlider(binding.dimSlider) { a, v -> a.copy(backgroundDim = v) }
        onAppearanceSlider(binding.collageGapSlider) { a, v -> a.copy(collageGapDp = v) }
        onAppearanceSlider(binding.overlaySizeSlider) { a, v ->
            a.copy(overlay = a.overlay.copy(textSizeSp = v))
        }
        onAppearanceSlider(binding.overlayScrimSlider) { a, v ->
            a.copy(overlay = a.overlay.copy(scrimStrength = v))
        }

        binding.cooldownSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || updatingControls) return@addOnChangeListener
            config = config.copy(cooldownPercent = value.roundToInt()).sanitized()
            binding.cooldownValue.text = getString(R.string.cooldown_value, value.roundToInt())
            schedulePreview()
        }

        binding.collageSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || updatingControls) return@addOnChangeListener
            config = config.copy(collageCells = value.roundToInt()).sanitized()
            binding.collageValue.text = resources.getQuantityString(
                R.plurals.collage_count_value,
                value.roundToInt(),
                value.roundToInt(),
            )
            schedulePreview()
        }

        binding.intervalSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || updatingControls) return@addOnChangeListener
            intervalSeconds = IntervalMath.secondsOf(value.roundToInt())
            intervalUnitIndex = IntervalMath.bestUnit(intervalSeconds)
            config = config.copy(intervalSeconds = intervalSeconds)
            updateIntervalFields()
        }

        binding.intervalValue.doAfterTextChanged {
            if (updatingControls) return@doAfterTextChanged
            val typed = binding.intervalValue.text?.toString()?.toLongOrNull() ?: return@doAfterTextChanged
            val total = typed * IntervalMath.multiplier(intervalUnitIndex)
            if (total in WidgetLimits.MIN_INTERVAL_SECONDS..WidgetLimits.MAX_INTERVAL_SECONDS) {
                intervalSeconds = total
                config = config.copy(intervalSeconds = total)
                updatingControls = true
                binding.intervalSlider.value = IntervalMath.progressOf(total).toFloat()
                binding.intervalSummary.text = getString(
                    R.string.interval_summary,
                    Format.duration(total),
                )
                binding.intervalValueLayout.error = null
                updatingControls = false
            }
        }
    }

    private fun setupSwitches() {
        binding.cardSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingControls) return@setOnCheckedChangeListener
            mutateAppearance { it.copy(showCard = checked) }
            updateAppearanceUi()
        }
        binding.shadowSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingControls) return@setOnCheckedChangeListener
            mutateAppearance { it.copy(shadowEnabled = checked) }
            updateAppearanceUi()
        }
        binding.overlayDateSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingControls) return@setOnCheckedChangeListener
            mutateAppearance { it.copy(overlay = it.overlay.copy(showDate = checked)) }
        }
        binding.overlayTimeSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingControls) return@setOnCheckedChangeListener
            mutateAppearance { it.copy(overlay = it.overlay.copy(showTime = checked)) }
        }
        binding.overlayNameSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingControls) return@setOnCheckedChangeListener
            mutateAppearance { it.copy(overlay = it.overlay.copy(showPhotoName = checked)) }
        }
        binding.overlayPhotoDateSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingControls) return@setOnCheckedChangeListener
            mutateAppearance { it.copy(overlay = it.overlay.copy(showPhotoDate = checked)) }
        }
        binding.overlayScrimSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingControls) return@setOnCheckedChangeListener
            mutateAppearance { it.copy(overlay = it.overlay.copy(scrim = checked)) }
            updateAppearanceUi()
        }
        binding.transitionsSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingControls) return@setOnCheckedChangeListener
            config = config.copy(transitions = checked)
        }
        binding.overlayCustomInput.doAfterTextChanged {
            if (updatingControls) return@doAfterTextChanged
            val text = binding.overlayCustomInput.text?.toString().orEmpty()
            mutateAppearance { it.copy(overlay = it.overlay.copy(customText = text)) }
        }
        binding.overlayDatePattern.doAfterTextChanged {
            if (updatingControls) return@doAfterTextChanged
            mutateAppearance { it.copy(overlay = it.overlay.copy(datePattern = binding.overlayDatePattern.text?.toString().orEmpty())) }
        }
        binding.overlayTimePattern.doAfterTextChanged {
            if (updatingControls) return@doAfterTextChanged
            mutateAppearance { it.copy(overlay = it.overlay.copy(timePattern = binding.overlayTimePattern.text?.toString().orEmpty())) }
        }
        binding.nameInput.doAfterTextChanged {
            if (updatingControls) return@doAfterTextChanged
            config = config.copy(name = binding.nameInput.text?.toString().orEmpty())
        }

        binding.fitToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || updatingControls) return@addOnButtonCheckedListener
            mutateAppearance { it.copy(imageFit = fitOf(checkedId)) }
        }
    }

    private fun setupButtons() {
        binding.addPhotos.setOnClickListener {
            pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.addAlbum.setOnClickListener { pickAlbum() }
        binding.addFolder.setOnClickListener { openFolder.launch(null) }
        binding.chooseSinglePhoto.setOnClickListener {
            pickSinglePhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.borderColor.setOnClickListener {
            ColorPicker.show(this, config.appearance.borderColor) { picked ->
                mutateAppearance { it.copy(borderColor = picked) }
                updateAppearanceUi()
            }
        }
        binding.backgroundColor.setOnClickListener {
            ColorPicker.show(this, config.appearance.backgroundColor) { picked ->
                mutateAppearance {
                    it.copy(backgroundColor = picked, backgroundStyle = BackgroundStyle.SOLID)
                }
                binding.backgroundInput.setText(Labels.background(this, BackgroundStyle.SOLID), false)
                updateAppearanceUi()
            }
        }
        binding.overlayColor.setOnClickListener {
            ColorPicker.show(this, config.appearance.overlay.textColor) { picked ->
                mutateAppearance { it.copy(overlay = it.overlay.copy(textColor = picked)) }
            }
        }
        binding.advancedToggle.setOnClickListener {
            binding.advancedGroup.isVisible = !binding.advancedGroup.isVisible
        }
        binding.timingSettings.setOnClickListener { showSchedulingHelp() }
        binding.viewHistory.setOnClickListener { showHistory() }
        binding.resetHistory.setOnClickListener { confirmResetHistory() }
        binding.deleteWidget.setOnClickListener { confirmDelete() }
    }

    // -------------------------------------------------------------- appearance

    private inline fun onAppearanceSlider(
        slider: Slider,
        crossinline change: (WidgetAppearance, Float) -> WidgetAppearance,
    ) {
        slider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || updatingControls) return@addOnChangeListener
            config = config.copy(
                appearance = change(config.appearance, value).sanitized(),
            )
            updateAppearanceLabels()
            schedulePreview()
        }
    }

    private fun mutateAppearance(block: (WidgetAppearance) -> WidgetAppearance) {
        config = config.copy(appearance = block(config.appearance).sanitized()).sanitized()
        updateAppearanceLabels()
        updateAppearanceUi()
        schedulePreview()
    }

    private fun setSliderValue(slider: Slider, value: Float) {
        val step = slider.stepSize.takeIf { it > 0f } ?: 1f
        val snapped = slider.valueFrom + ((value - slider.valueFrom) / step).roundToInt() * step
        slider.value = snapped.coerceIn(slider.valueFrom, slider.valueTo)
    }

    private fun updateAppearanceLabels() {
        val a = config.appearance.sanitized()
        binding.cornerValue.text = "${a.cornerRadiusDp.roundToInt()} dp"
        binding.marginValue.text = "${a.marginDp.roundToInt()} dp"
        binding.paddingValue.text = "${a.paddingDp.roundToInt()} dp"
        binding.borderValue.text = "${a.borderWidthDp} dp"
        binding.shadowStrengthValue.text = "${(a.shadowStrength * 100).roundToInt()}%"
        binding.focusXValue.text = "${(a.focusX * 100).roundToInt()}%"
        binding.focusYValue.text = "${(a.focusY * 100).roundToInt()}%"
        binding.blurValue.text = "${(a.backgroundBlur * 100).roundToInt()}%"
        binding.dimValue.text = "${(a.backgroundDim * 100).roundToInt()}%"
        binding.overlaySizeValue.text = "${a.overlay.textSizeSp.roundToInt()} sp"
        binding.overlayScrimValue.text = "${(a.overlay.scrimStrength * 100).roundToInt()}%"
        binding.collageGapValue.text = "${a.collageGapDp} dp"
        binding.cooldownValue.text = getString(R.string.cooldown_value, config.cooldownPercent)
        binding.collageValue.text = resources.getQuantityString(
            R.plurals.collage_count_value,
            config.collageCells,
            config.collageCells,
        )
    }

    private fun updateAppearanceUi() {
        val a = config.appearance.sanitized()
        binding.frameGroup.isVisible = a.showCard
        binding.backgroundGroup.isVisible = a.showCard
        binding.shadowStrengthGroup.isVisible = a.shadowEnabled
        binding.blurGroup.isVisible = a.backgroundStyle == BackgroundStyle.BLUR
        binding.overlayScrimGroup.isVisible = a.overlay.scrim
        ColorPicker.styleButton(binding.borderColor, a.borderColor)
        ColorPicker.styleButton(binding.backgroundColor, a.backgroundColor)
        ColorPicker.styleButton(binding.overlayColor, a.overlay.textColor)
        updatingControls = true
        binding.fitToggle.check(fitButtonId(a.imageFit))
        val matchedStyle = WidgetStyle.of(a)
        val chipId = styleChips.entries.firstOrNull { it.value == matchedStyle }?.key
        if (chipId == null) binding.styleGroup.clearCheck() else binding.styleGroup.check(chipId)
        binding.backgroundInput.setText(Labels.background(this, a.backgroundStyle), false)
        binding.qualityInput.setText(Labels.quality(this, a.renderQuality), false)
        binding.overlayPositionInput.setText(Labels.overlayPosition(this, a.overlay.position), false)
        updatingControls = false
        updateSectionCaptions()
    }

    private fun fitButtonId(fit: ImageFit): Int = when (fit) {
        ImageFit.FILL -> R.id.fit_fill
        ImageFit.FIT -> R.id.fit_photo
        ImageFit.BLUR_FILL -> R.id.fit_blur_fill
    }

    private fun fitOf(checkedId: Int): ImageFit = when (checkedId) {
        R.id.fit_photo -> ImageFit.FIT
        R.id.fit_blur_fill -> ImageFit.BLUR_FILL
        else -> ImageFit.FILL
    }

    /** Short summaries shown under each section title, so the screen reads at a glance. */
    private fun updateSectionCaptions() {
        val a = config.appearance.sanitized()
        binding.photosHeader.setCaption(
            if (sources.isEmpty()) {
                getString(R.string.sources_none)
            } else {
                resources.getQuantityString(
                    R.plurals.sources_summary,
                    sources.size,
                    sources.size,
                )
            },
        )
        binding.rotationHeader.setCaption(Labels.mode(this, config.mode))
        binding.timingHeader.setCaption(
            when {
                config.mode == WidgetMode.SINGLE -> getString(R.string.timing_manual)
                config.mode.resetsAtMidnight -> getString(R.string.timing_midnight)
                else -> getString(R.string.interval_summary, Format.duration(intervalSeconds))
            },
        )
        binding.lookHeader.setCaption(
            WidgetStyle.of(a)?.let { Labels.style(this, it) }
                ?: getString(R.string.style_custom),
        )
        val overlayParts = buildList {
            if (a.overlay.showDate) add(getString(R.string.overlay_date))
            if (a.overlay.showTime) add(getString(R.string.overlay_time))
            if (a.overlay.showPhotoName) add(getString(R.string.overlay_photo_name))
            if (a.overlay.showPhotoDate) add(getString(R.string.overlay_photo_date))
            if (a.overlay.customText.isNotBlank()) add(getString(R.string.overlay_custom_text))
        }
        binding.overlayHeader.setCaption(overlayParts.joinToString(" · ").ifBlank { null })
        binding.behaviorHeader.setCaption(Labels.tapAction(this, config.tapAction))
    }

    // ------------------------------------------------------------------ mode

    private fun updateModeUi() {
        binding.modeDescription.text = Labels.modeDescription(this, config.mode)
        binding.cooldownGroup.isVisible = config.mode.usesFairRotation
        binding.collageGroup.isVisible = config.mode == WidgetMode.COLLAGE
        binding.singlePhotoGroup.isVisible = config.mode == WidgetMode.SINGLE
        val timed = !config.mode.resetsAtMidnight && config.mode != WidgetMode.SINGLE
        binding.intervalSlider.isVisible = timed
        binding.intervalValueLayout.isVisible = timed
        binding.intervalValueLayout.error = null
        binding.intervalUnitLayout.isVisible = timed
        if (timed) {
            updateIntervalFields()
            binding.intervalNote.text = getString(
                if (SchedulingHelp.canUseExactAlarms(this)) {
                    R.string.timing_exact_alarm_granted
                } else {
                    R.string.timing_exact_alarm_missing
                },
            )
        } else {
            binding.intervalSummary.text = getString(R.string.interval_not_used)
            binding.intervalNote.text = Labels.modeDescription(this, config.mode)
        }
        updateSinglePhotoUi()
        updateAppearanceUi()
    }

    private fun updateSinglePhotoUi() {
        val uri = config.singlePhotoUri
        binding.singlePhotoLabel.text = if (uri == null) {
            getString(R.string.single_photo_none)
        } else {
            Uri.parse(uri).lastPathSegment ?: getString(R.string.unknown)
        }
        binding.chooseSinglePhoto.setText(
            if (uri == null) R.string.choose_single_photo else R.string.change_single_photo,
        )
    }

    private fun updateIntervalFields() {
        updatingControls = true
        binding.intervalUnit.setText(intervalUnits[intervalUnitIndex], false)
        binding.intervalValue.setText(
            IntervalMath.displayValue(intervalSeconds, intervalUnitIndex).toString(),
        )
        binding.intervalSummary.text = getString(
            R.string.interval_summary,
            Format.duration(intervalSeconds),
        )
        binding.intervalSlider.value = IntervalMath.progressOf(intervalSeconds).toFloat()
        updatingControls = false
        updateSectionCaptions()
    }

    // ---------------------------------------------------------------- sources

    private fun refreshSourcesUi() {
        binding.sourcesContainer.removeAllViews()
        sources.forEachIndexed { index, source ->
            val row = ItemSourceBinding.inflate(layoutInflater, binding.sourcesContainer, false)
            row.sourceType.text = when (source.type) {
                PhotoSourceType.PHOTO -> getString(R.string.source_type_photo)
                PhotoSourceType.ALBUM -> getString(R.string.source_type_album)
                PhotoSourceType.FOLDER -> getString(R.string.source_type_folder)
            }
            row.sourceLabel.text = source.label
            row.sourceRemove.setOnClickListener { removeSourceAt(index) }
            binding.sourcesContainer.addView(row.root)
        }
        binding.sourcesEmpty.isVisible = sources.isEmpty()
        updateSinglePhotoUi()
        updateSectionCaptions()
    }

    private fun removeSourceAt(index: Int) {
        if (index !in sources.indices) return
        sources.removeAt(index)
        refreshSourcesUi()
        refreshPreviewPhotos()
    }

    private fun addPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val existingLocators = sources.map { it.locator }.toSet()
        val appContext = applicationContext
        lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                uris.filter { it.toString() !in existingLocators }.mapIndexedNotNull { index, uri ->
                    runCatching {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                    val ref = runCatching { graph.mediaQuery.photo(uri.toString()) }.getOrNull()
                    if (ref == null) {
                        null
                    } else {
                        PhotoSource(
                            type = PhotoSourceType.PHOTO,
                            locator = uri.toString(),
                            label = ref.displayName.ifBlank { "Photo" },
                            addedAt = now + index,
                        )
                    }
                }
            }
            if (added.isEmpty()) {
                snack(R.string.photo_add_error)
            } else {
                sources.addAll(added)
                refreshSourcesUi()
                refreshPreviewPhotos()
            }
        }
    }

    private fun setSinglePhoto(uri: Uri?) {
        if (uri == null) return
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        config = config.copy(singlePhotoUri = uri.toString())
        updateSinglePhotoUi()
        schedulePreview()
    }

    private fun pickAlbum() {
        // Partial ("Select photos") access is enough to list the albums the user shared,
        // so it must not trigger another permission prompt on every tap.
        if (!PhotoPermissions.hasImageAccess(this)) {
            requestAlbumAccess.launch(PhotoPermissions.albumPermissions())
        } else {
            showAlbumPicker()
        }
    }

    private fun showAlbumPicker() {
        lifecycleScope.launch {
            val albums = withContext(Dispatchers.IO) {
                runCatching { graph.resolver.albums() }.getOrNull()
            }
            if (albums == null) {
                snack(R.string.library_permission_needed)
                return@launch
            }
            if (albums.isEmpty()) {
                snack(R.string.library_albums_empty)
                return@launch
            }
            val labels = albums.map { album ->
                "${album.name} (${resources.getQuantityString(R.plurals.album_count, album.photoCount, album.photoCount)})"
            }.toTypedArray()
            MaterialAlertDialogBuilder(this@WidgetConfigureActivity)
                .setTitle(R.string.add_album)
                .setItems(labels) { _, which -> addAlbum(albums[which]) }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    private fun addAlbum(album: AlbumInfo) {
        if (sources.any { it.type == PhotoSourceType.ALBUM && it.locator == album.bucketId }) return
        sources += PhotoSource(
            type = PhotoSourceType.ALBUM,
            locator = album.bucketId,
            label = album.name,
            addedAt = System.currentTimeMillis(),
        )
        refreshSourcesUi()
        refreshPreviewPhotos()
    }

    private fun addFolder(uri: Uri?) {
        if (uri == null) return
        DocumentAccess.takePersistableRead(this, uri)
        val label = DocumentFile.fromTreeUri(this, uri)?.name
            ?: getString(R.string.source_type_folder)
        if (sources.any { it.type == PhotoSourceType.FOLDER && it.locator == uri.toString() }) return
        sources += PhotoSource(
            type = PhotoSourceType.FOLDER,
            locator = uri.toString(),
            label = label,
            addedAt = System.currentTimeMillis(),
        )
        refreshSourcesUi()
        refreshPreviewPhotos()
        snack(R.string.folder_added)
    }

    private fun refreshPreviewPhotos() {
        photosJob?.cancel()
        val snapshot = sources.toList()
        photosJob = lifecycleScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                try {
                    graph.resolver.resolve(snapshot)
                } catch (e: PhotoAccessException) {
                    dev.photowidget.model.ResolvedPhotos()
                }
            }
            previewPhotos = resolved.photos.take(9)
            previewIssue = if (previewPhotos.isEmpty()) {
                resolved.issues.firstOrNull()?.message
            } else {
                null
            }
            schedulePreview()
        }
    }

    // ----------------------------------------------------------------- render

    private fun schedulePreview() {
        if (!loaded) return
        previewJob?.cancel()
        previewJob = lifecycleScope.launch {
            delay(40)
            binding.preview.render(
                config = config,
                photos = photosForPreview(),
                emptyTitle = previewIssue?.let { getString(R.string.preview_unavailable_title) },
                emptySubtitle = previewIssue,
            )
        }
    }

    /**
     * Only a collage shows several photos at once; every other mode shows one, so the
     * preview must not draw the whole source as a grid.
     */
    private fun photosForPreview(): List<PhotoRef> = when (config.mode) {
        WidgetMode.SINGLE -> {
            val uri = config.singlePhotoUri ?: return emptyList()
            listOf(PhotoRef(uri = uri, key = uri))
        }

        WidgetMode.COLLAGE -> previewPhotos.take(config.collageCells)
        else -> previewPhotos.take(1)
    }

    // --------------------------------------------------------------- actions

    private fun save() {
        if (!loaded) return
        if (config.mode != WidgetMode.SINGLE && sources.isEmpty()) {
            snack(R.string.save_needs_photos)
            return
        }
        if (config.mode == WidgetMode.SINGLE && config.singlePhotoUri == null) {
            snack(R.string.save_needs_single_photo)
            return
        }
        if (!validateInterval()) return

        val name = binding.nameInput.text?.toString()?.trim().orEmpty()
        val toSave = config.copy(
            name = if (name.isBlank()) getString(R.string.hint_name) else name,
            sources = sources.toList(),
            intervalSeconds = intervalSeconds,
        ).sanitized()

        val droppedFolders = config.sources
            .filter { it.type == PhotoSourceType.FOLDER }
            .map { it.locator }
            .filterNot { locator -> toSave.sources.any { it.locator == locator } }

        binding.saveButton.isEnabled = false
        lifecycleScope.launch {
            try {
                graph.repository.saveConfig(toSave)
                graph.preferences.clearPendingDuplicate()
                graph.preferences.clearDeletedWidget(widgetId)
                graph.releaseUnusedFolderGrants(droppedFolders)
                graph.coordinator.update(widgetId, UpdateReason.CONFIG_CHANGED)
                setResult(
                    RESULT_OK,
                    Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
                )
                finish()
            } catch (e: Exception) {
                binding.saveButton.isEnabled = true
                snack(R.string.load_failed)
            }
        }
    }

    private fun validateInterval(): Boolean {
        if (config.mode.resetsAtMidnight || config.mode == WidgetMode.SINGLE) {
            binding.intervalValueLayout.error = null
            return true
        }
        val typed = binding.intervalValue.text?.toString()?.toLongOrNull()
        val total = typed?.times(IntervalMath.multiplier(intervalUnitIndex))
        if (typed == null || total == null ||
            total !in WidgetLimits.MIN_INTERVAL_SECONDS..WidgetLimits.MAX_INTERVAL_SECONDS
        ) {
            binding.intervalValueLayout.error = getString(
                R.string.interval_invalid,
                Format.duration(WidgetLimits.MIN_INTERVAL_SECONDS),
                Format.duration(WidgetLimits.MAX_INTERVAL_SECONDS),
            )
            return false
        }
        binding.intervalValueLayout.error = null
        intervalSeconds = total
        config = config.copy(intervalSeconds = total)
        return true
    }

    private fun cancelEditing() {
        if (!existing) graph.preferences.clearPendingDuplicate()
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun showSchedulingHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.timing_open_settings)
            .setMessage(getString(R.string.timing_notes_body))
            .setItems(
                arrayOf(
                    getString(R.string.exact_alarms_open),
                    getString(R.string.battery_open),
                ),
            ) { _, which ->
                if (which == 0) SchedulingHelp.openExactAlarmSettings(this)
                else SchedulingHelp.openBatterySettings(this)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showHistory() {
        if (!existing) return
        lifecycleScope.launch {
            val items = graph.repository.getHistory(widgetId, 60)
            if (items.isEmpty()) {
                snack(R.string.history_empty)
                return@launch
            }
            val labels = items.map { item ->
                val name = item.displayName.ifBlank { getString(R.string.unknown) }
                "$name · ${Format.dateTime(item.shownAt)}"
            }.toTypedArray()
            MaterialAlertDialogBuilder(this@WidgetConfigureActivity)
                .setTitle(R.string.history_title)
                .setItems(labels, null)
                .setPositiveButton(R.string.action_close, null)
                .show()
        }
    }

    private fun refreshHistoryCount() {
        if (!existing) return
        lifecycleScope.launch {
            val count = graph.repository.historyCount(widgetId)
            binding.historyCount.text = resources.getQuantityString(
                R.plurals.history_count,
                count,
                count,
            )
        }
    }

    private fun confirmResetHistory() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_reset_history)
            .setMessage(R.string.history_reset_warning)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_reset_history) { _, _ ->
                lifecycleScope.launch {
                    graph.repository.resetHistory(widgetId)
                    refreshHistoryCount()
                    schedulePreview()
                    snack(R.string.history_reset)
                }
            }
            .show()
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_widget_title)
            .setMessage(R.string.delete_widget_body)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch {
                    graph.coordinator.deleteWidget(widgetId)
                    setResult(RESULT_CANCELED)
                    finish()
                }
            }
            .show()
    }

    private fun snack(messageRes: Int) {
        Snackbar.make(binding.root, messageRes, Snackbar.LENGTH_SHORT).show()
    }

    private val styleChips: Map<Int, WidgetStyle> = mapOf(
        R.id.style_plain to WidgetStyle.PLAIN,
        R.id.style_natural to WidgetStyle.NATURAL,
        R.id.style_full_bleed to WidgetStyle.FULL_BLEED,
        R.id.style_soft_card to WidgetStyle.SOFT_CARD,
        R.id.style_polaroid to WidgetStyle.POLAROID,
        R.id.style_glass to WidgetStyle.GLASS,
        R.id.style_neon to WidgetStyle.NEON,
    )

    private companion object {
        const val STATE_CONFIG = "draft_config"
        const val STATE_EXISTING = "draft_existing"
        const val STATE_INTERVAL_UNIT = "draft_interval_unit"
        const val STATE_INTERVAL_TEXT = "draft_interval_text"

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            coerceInputValues = true
        }
    }
}
