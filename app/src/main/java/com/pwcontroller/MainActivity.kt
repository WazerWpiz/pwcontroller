package com.pwcontroller

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.pwcontroller.databinding.ActivityMainBinding
import com.pwcontroller.model.ControllerProfile
import com.pwcontroller.profile.ProfileRepository
import com.pwcontroller.service.InputAccessibilityService
import com.pwcontroller.service.OverlayService
import com.pwcontroller.util.GameLauncher
import com.pwcontroller.util.PermissionUtils
import com.pwcontroller.util.PixelWorldsDefaults
import com.pwcontroller.util.PreferenceManager
import com.pwcontroller.util.ShizukuInjector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferenceManager: PreferenceManager
    private lateinit var profileRepository: ProfileRepository

    private var uiState = UiState()
    private var wasReady = false
    private var preparationExpanded = false
    private var overlayStartRequested = false
    private var problemHighlightToken = 0

    private val refreshRunnable = Runnable {
        if (!isDestroyed && !isFinishing) refreshUi()
    }

    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshShizukuOnUiThread()
    }
    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        refreshShizukuOnUiThread()
    }
    private val shizukuPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == ShizukuInjector.REQUEST_CODE &&
                grantResult != PackageManager.PERMISSION_GRANTED
            ) {
                runOnUiThread {
                    if (!isDestroyed) {
                        Toast.makeText(
                            this,
                            R.string.shizuku_permission_denied,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            refreshShizukuOnUiThread()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferenceManager = PWControllerApplication.instance.preferenceManager
        profileRepository = ProfileRepository.create(this)

        setupEdgeToEdge()
        bindVersion()
        setupClickListeners()
        registerShizukuListeners()
        refreshUi()

        if (preferenceManager.isFirstLaunch()) {
            showFirstLaunchDialog()
            preferenceManager.setFirstLaunchComplete()
        } else {
            requestNotificationPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        maybeStartOverlayAutomatically()
        scheduleRefresh(700L)
    }

    override fun onDestroy() {
        if (::binding.isInitialized) binding.root.removeCallbacks(refreshRunnable)
        unregisterShizukuListeners()
        super.onDestroy()
    }

    @Deprecated("Menggunakan kontrak hasil activity dari PermissionUtils yang sudah ada")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            PermissionUtils.REQUEST_OVERLAY_PERMISSION,
            PermissionUtils.REQUEST_ACCESSIBILITY -> {
                refreshUi()
                maybeStartOverlayAutomatically()
                scheduleRefresh(500L)
            }
        }
    }

    private fun setupEdgeToEdge() {
        updateSystemBarAppearance()
        ViewCompat.setOnApplyWindowInsetsListener(binding.scrollMain) { view, insets ->
            val safeInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(
                left = safeInsets.left,
                top = safeInsets.top,
                right = safeInsets.right,
                bottom = safeInsets.bottom
            )
            insets
        }
    }

    private fun updateSystemBarAppearance() {
        val isNight = resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isNight
            isAppearanceLightNavigationBars = !isNight
        }
    }

    @Suppress("DEPRECATION")
    private fun bindVersion() {
        val version = try {
            val info = packageManager.getPackageInfo(packageName, 0)
            "${info.versionName ?: BuildConfig.VERSION_NAME} (${info.versionCode})"
        } catch (e: PackageManager.NameNotFoundException) {
            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        }
        binding.tvHeaderVersion.text = getString(R.string.version_format, version)
        binding.tvFooterVersion.text = getString(R.string.version_footer, version)
    }

    private fun setupClickListeners() {
        binding.toolbarMain.overflowIcon = ContextCompat.getDrawable(
            this,
            R.drawable.ic_more_vert
        )?.mutate()?.apply {
            setTint(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
        }
        binding.btnLaunchGame.setOnClickListener { performHeroAction() }
        binding.btnToggleOverlay.setOnClickListener { toggleOverlay() }
        binding.btnEditMode.setOnClickListener { toggleEditMode() }
        binding.btnExitEditMode.setOnClickListener { setEditMode(false, startIfNeeded = false) }
        binding.btnResetPreset.setOnClickListener { confirmResetPreset() }

        binding.rowOverlayPermission.setOnClickListener { requestOverlayPermission() }
        binding.rowShizuku.setOnClickListener { handleShizukuAction() }
        binding.rowAccessibility.setOnClickListener { openAccessibilitySettings() }
        binding.rowGame.setOnClickListener { openPixelWorldsStore() }

        binding.layoutPreparationCollapsed.setOnClickListener {
            preparationExpanded = true
            updatePreparationVisibility()
        }
        binding.layoutPreparationHeader.setOnClickListener {
            if (uiState.ready) {
                preparationExpanded = false
                updatePreparationVisibility()
            }
        }

        binding.toolbarMain.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_save_profile -> {
                    saveProfile()
                    true
                }
                R.id.action_load_profile -> {
                    loadProfile()
                    true
                }
                R.id.action_test_injection -> {
                    runInjectionTest()
                    true
                }
                else -> false
            }
        }
    }

    /** Diagnostic action: inject a tap through Shizuku and show what the system answered. */
    private fun runInjectionTest() {
        val overlay = OverlayService.getInstance()
        val message = if (overlay != null) {
            overlay.testInjection()
        } else {
            // The overlay is not running: test the injector directly in the middle of the screen
            val (w, h, _) = PixelWorldsDefaults.realScreenSize(this)
            ShizukuInjector.testInject(w / 2f, h / 2f)
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun collectState(): UiState {
        val shizukuInstalled = isShizukuInstalled()
        val shizukuRunning = shizukuInstalled && ShizukuInjector.isRunning()
        val shizukuGranted = shizukuRunning && ShizukuInjector.hasPermission()
        val shizukuInitialized = shizukuGranted && ShizukuInjector.init()
        val overlay = OverlayService.getInstance()
        val overlayReady = overlay?.isOverlayReady() == true

        return UiState(
            gameInstalled = GameLauncher.isGameInstalled(this),
            overlayGranted = PermissionUtils.hasOverlayPermission(this),
            shizukuInstalled = shizukuInstalled,
            shizukuRunning = shizukuRunning,
            shizukuGranted = shizukuGranted,
            shizukuInitialized = shizukuInitialized,
            accessibilityEnabled = PermissionUtils.hasAccessibilityServiceEnabled(this),
            accessibilityConnected = InputAccessibilityService.getInstance() != null,
            overlayReady = overlayReady,
            overlayVisible = overlayReady && overlay?.isVisible == true,
            editMode = preferenceManager.isEditMode()
        )
    }

    private fun refreshUi() {
        if (!::binding.isInitialized || !::preferenceManager.isInitialized) return
        val newState = collectState()
        uiState = newState
        if (newState.overlayReady) overlayStartRequested = false
        if (newState.ready && !wasReady) preparationExpanded = false
        if (newState.ready) clearProblemHighlight()
        wasReady = newState.ready
        renderState(newState)
    }

    private fun renderState(state: UiState) {
        renderPreparationRows(state)
        updatePreparationVisibility()
        renderHero(state)
        renderActiveInput(state)
        renderTools(state)

        binding.cardEditMode.visibility = if (state.editMode) View.VISIBLE else View.GONE
        binding.btnEditMode.setText(
            if (state.editMode) R.string.edit_state_on else R.string.edit_state_off
        )
    }

    private fun renderPreparationRows(state: UiState) {
        binding.tvOverlayPermissionStatus.setText(
            if (state.overlayGranted) {
                R.string.preparation_overlay_granted
            } else {
                R.string.preparation_overlay_missing
            }
        )
        setStatusIcon(
            binding.ivOverlayStatus,
            if (state.overlayGranted) StatusLevel.SUCCESS else StatusLevel.ERROR
        )

        val shizukuText = when {
            !state.shizukuInstalled -> getString(R.string.shizuku_subtitle_not_installed)
            !state.shizukuRunning -> getString(R.string.shizuku_subtitle_not_running)
            !state.shizukuGranted -> getString(R.string.shizuku_subtitle_need_permission)
            !state.shizukuInitialized -> {
                val error = ShizukuInjector.lastError?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.shizuku_unknown_error)
                getString(R.string.shizuku_subtitle_init_error, error)
            }
            else -> getString(R.string.shizuku_subtitle_ready)
        }
        binding.tvShizukuStatus.text = shizukuText
        setStatusIcon(
            binding.ivShizukuStatus,
            when {
                state.shizukuInitialized -> StatusLevel.SUCCESS
                !state.shizukuInstalled || state.shizukuGranted -> StatusLevel.ERROR
                else -> StatusLevel.WARNING
            }
        )

        binding.tvAccessibilityStatus.setText(
            when {
                state.accessibilityConnected -> R.string.preparation_accessibility_active
                state.accessibilityEnabled -> R.string.preparation_accessibility_waiting
                else -> R.string.preparation_accessibility_optional
            }
        )
        setStatusIcon(
            binding.ivAccessibilityStatus,
            if (state.accessibilityConnected) StatusLevel.SUCCESS else StatusLevel.WARNING
        )

        binding.tvGameStatus.setText(
            if (state.gameInstalled) {
                R.string.preparation_game_installed
            } else {
                R.string.preparation_game_missing
            }
        )
        setStatusIcon(
            binding.ivGameStatus,
            if (state.gameInstalled) StatusLevel.SUCCESS else StatusLevel.ERROR
        )

        binding.tvPreparationSummary.text = if (state.ready) {
            getString(R.string.preparation_summary_ready)
        } else {
            getString(R.string.preparation_summary_action, state.requiredProblemCount)
        }
    }

    private fun renderHero(state: UiState) {
        when {
            state.overlayVisible -> {
                binding.btnLaunchGame.setText(R.string.action_stop_overlay)
                binding.btnLaunchGame.setIconResource(R.drawable.ic_stop)
                setTextWithFade(
                    binding.tvOverlayStatus,
                    getString(R.string.hero_status_overlay)
                )
                setHeroStatusColor(R.color.status_success)
            }
            state.ready -> {
                binding.btnLaunchGame.setText(R.string.action_start_game)
                binding.btnLaunchGame.setIconResource(R.drawable.ic_play)
                setTextWithFade(
                    binding.tvOverlayStatus,
                    getString(R.string.hero_status_ready)
                )
                setHeroStatusColor(R.color.status_success)
            }
            else -> {
                binding.btnLaunchGame.setText(R.string.action_finish_preparation)
                binding.btnLaunchGame.setIconResource(R.drawable.ic_warning)
                setTextWithFade(
                    binding.tvOverlayStatus,
                    getString(R.string.hero_status_preparation)
                )
                setHeroStatusColor(R.color.status_warning)
            }
        }
    }

    private fun renderActiveInput(state: UiState) {
        val activeInput = when {
            state.shizukuInitialized -> getString(R.string.shizuku_title)
            state.accessibilityConnected -> getString(R.string.shizuku_input_accessibility)
            else -> null
        }
        setTextWithFade(
            binding.chipActiveInput,
            if (activeInput == null) {
                getString(R.string.shizuku_input_active_none)
            } else {
                getString(R.string.shizuku_input_active, activeInput)
            }
        )
        val color = ContextCompat.getColor(
            this,
            if (activeInput == null) R.color.status_error else R.color.status_success
        )
        binding.chipActiveInput.setTextColor(color)
        binding.chipActiveInput.chipStrokeColor = ColorStateList.valueOf(color)
    }

    private fun renderTools(state: UiState) {
        if (state.overlayVisible) {
            binding.btnToggleOverlay.setText(R.string.action_hide_overlay)
            binding.btnToggleOverlay.setIconResource(R.drawable.ic_visibility_off)
        } else {
            binding.btnToggleOverlay.setText(R.string.action_show_overlay)
            binding.btnToggleOverlay.setIconResource(R.drawable.ic_visibility)
        }
    }

    private fun updatePreparationVisibility() {
        val collapsedVisibility = if (uiState.ready && !preparationExpanded) {
            View.VISIBLE
        } else {
            View.GONE
        }
        val detailsVisibility = if (collapsedVisibility == View.VISIBLE) {
            View.GONE
        } else {
            View.VISIBLE
        }
        if (binding.layoutPreparationCollapsed.visibility == collapsedVisibility &&
            binding.layoutPreparationDetails.visibility == detailsVisibility
        ) return

        TransitionManager.beginDelayedTransition(
            binding.cardPreparation,
            AutoTransition().apply { duration = 180L }
        )
        binding.layoutPreparationCollapsed.visibility = collapsedVisibility
        binding.layoutPreparationDetails.visibility = detailsVisibility
    }

    private fun performHeroAction() {
        when {
            uiState.overlayVisible -> stopOverlay()
            uiState.ready -> launchGame()
            else -> focusFirstProblem()
        }
    }

    private fun focusFirstProblem() {
        val target = when {
            !uiState.overlayGranted -> binding.rowOverlayPermission
            !uiState.inputReady -> binding.rowShizuku
            !uiState.gameInstalled -> binding.rowGame
            else -> null
        } ?: return

        preparationExpanded = true
        updatePreparationVisibility()
        clearProblemHighlight()
        val token = problemHighlightToken
        target.isActivated = true
        binding.cardPreparation.strokeColor = ContextCompat.getColor(this, R.color.status_warning)

        binding.scrollMain.postDelayed({
            if (isDestroyed || token != problemHighlightToken) return@postDelayed
            val rect = Rect()
            target.getDrawingRect(rect)
            binding.scrollMain.offsetDescendantRectToMyCoords(target, rect)
            binding.scrollMain.smoothScrollTo(0, max(0, rect.top - dp(16)))
        }, 200L)
        binding.scrollMain.postDelayed({
            if (token == problemHighlightToken) clearProblemHighlight()
        }, 2400L)
    }

    private fun clearProblemHighlight() {
        problemHighlightToken++
        binding.rowOverlayPermission.isActivated = false
        binding.rowShizuku.isActivated = false
        binding.rowGame.isActivated = false
        if (::binding.isInitialized) {
            binding.cardPreparation.strokeColor = ContextCompat.getColor(this, R.color.card_outline)
        }
    }

    private fun requestOverlayPermission() {
        try {
            PermissionUtils.requestOverlayPermission(this)
        } catch (e: Exception) {
            Log.w(TAG, "Pengaturan overlay tidak dapat dibuka", e)
            Toast.makeText(this, R.string.overlay_settings_error, Toast.LENGTH_LONG).show()
        }
    }

    private fun openAccessibilitySettings() {
        try {
            PermissionUtils.openAccessibilitySettings(this)
        } catch (e: Exception) {
            Log.w(TAG, "Pengaturan Aksesibilitas tidak dapat dibuka", e)
            Toast.makeText(this, R.string.accessibility_settings_error, Toast.LENGTH_LONG).show()
        }
    }

    private fun handleShizukuAction() {
        when {
            !uiState.shizukuInstalled || !uiState.shizukuRunning -> openShizuku()
            !uiState.shizukuGranted -> {
                ShizukuInjector.requestPermission()
                Toast.makeText(this, R.string.shizuku_permission_requested, Toast.LENGTH_SHORT).show()
            }
            else -> openShizuku()
        }
    }

    private fun openShizuku() {
        try {
            if (isShizukuInstalled()) {
                val launchIntent = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
                if (launchIntent != null) {
                    startActivity(launchIntent)
                    return
                }
            }
            openStore(
                SHIZUKU_PACKAGE,
                getString(R.string.shizuku_download_url),
                R.string.shizuku_open_app_failed
            )
        } catch (e: Exception) {
            Log.w(TAG, "Tidak dapat membuka Shizuku", e)
            Toast.makeText(this, R.string.shizuku_open_app_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun openPixelWorldsStore() {
        openStore(
            GameLauncher.TARGET_PACKAGE,
            "https://play.google.com/store/apps/details?id=${GameLauncher.TARGET_PACKAGE}",
            R.string.pixel_worlds_play_store_error
        )
    }

    private fun openStore(packageName: String, webUrl: String, errorMessage: Int) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
        } catch (marketError: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)))
            } catch (webError: Exception) {
                Log.w(TAG, "Toko aplikasi tidak dapat dibuka", webError)
                Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun launchGame() {
        if (uiState.overlayGranted && uiState.inputReady) {
            startOverlayService(showOverlay = true)
        }
        if (!GameLauncher.launchGame(this)) {
            Toast.makeText(this, R.string.pixel_worlds_launch_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleOverlay() {
        if (!uiState.overlayGranted) {
            Toast.makeText(this, R.string.overlay_permission_needed, Toast.LENGTH_LONG).show()
            requestOverlayPermission()
            return
        }
        if (!uiState.inputReady) {
            Toast.makeText(this, R.string.input_not_ready, Toast.LENGTH_LONG).show()
            focusFirstProblem()
            return
        }

        val service = OverlayService.getInstance()
        if (service == null || !service.isOverlayReady()) {
            if (startOverlayService(showOverlay = true)) {
                Toast.makeText(this, R.string.overlay_started, Toast.LENGTH_SHORT).show()
            }
        } else {
            service.toggleVisibility()
        }
        scheduleRefresh(400L)
    }

    private fun stopOverlay() {
        stopService(Intent(this, OverlayService::class.java))
        Toast.makeText(this, R.string.overlay_stopped, Toast.LENGTH_SHORT).show()
        scheduleRefresh(400L)
    }

    private fun toggleEditMode() {
        if (!uiState.overlayGranted) {
            Toast.makeText(this, R.string.overlay_permission_needed, Toast.LENGTH_LONG).show()
            requestOverlayPermission()
            return
        }
        if (!uiState.inputReady) {
            Toast.makeText(this, R.string.input_not_ready, Toast.LENGTH_LONG).show()
            focusFirstProblem()
            return
        }
        setEditMode(!uiState.editMode, startIfNeeded = true)
    }

    private fun setEditMode(enabled: Boolean, startIfNeeded: Boolean) {
        preferenceManager.saveEditMode(enabled)
        val service = OverlayService.getInstance()
        if (service != null && service.isOverlayReady()) {
            service.setEditMode(enabled)
        } else if (enabled && startIfNeeded) {
            startOverlayService(showOverlay = true)
        }
        Toast.makeText(
            this,
            if (enabled) R.string.edit_mode_started else R.string.edit_mode_stopped,
            Toast.LENGTH_SHORT
        ).show()
        refreshUi()
        scheduleRefresh(500L)
    }

    private fun confirmResetPreset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_preset_title)
            .setMessage(R.string.reset_preset_message)
            .setPositiveButton(R.string.reset_preset_confirm) { _, _ -> resetPreset() }
            .setNegativeButton(R.string.reset_preset_cancel, null)
            .show()
    }

    private fun resetPreset() {
        lifecycleScope.launch {
            try {
                val cleanProfile = PixelWorldsDefaults.createProfile()
                profileRepository.saveProfile(cleanProfile)
                preferenceManager.saveCurrentProfile(cleanProfile)
                preferenceManager.clearWindowOffsets()
                preferenceManager.saveEditMode(false)
                OverlayService.getInstance()?.resetOverlayLayout()
                refreshUi()
                Toast.makeText(this@MainActivity, R.string.reset_preset_done, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(TAG, "Preset tidak dapat direset", e)
                Toast.makeText(this@MainActivity, R.string.reset_preset_error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveProfile() {
        lifecycleScope.launch {
            try {
                val profile = OverlayService.getInstance()?.getCurrentProfile()
                    ?: profileRepository.getDefaultProfile()
                    ?: createDefaultProfile()
                val timestamp = System.currentTimeMillis()
                profileRepository.saveProfile(
                    profile.copy(name = "saved_$timestamp", createdAt = timestamp)
                )
                Toast.makeText(this@MainActivity, R.string.profile_saved, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Profil tidak dapat disimpan", e)
                Toast.makeText(this@MainActivity, R.string.profile_save_error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadProfile() {
        lifecycleScope.launch {
            try {
                val profiles = profileRepository.getAllProfiles().first()
                if (profiles.isEmpty()) {
                    Toast.makeText(this@MainActivity, R.string.profile_none, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val latestProfile = profiles.first()
                profileRepository.saveProfile(latestProfile.copy(name = "default"))
                OverlayService.getInstance()?.reloadProfile()
                Toast.makeText(this@MainActivity, R.string.profile_loaded, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Profil tidak dapat dimuat", e)
                Toast.makeText(this@MainActivity, R.string.profile_load_error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun createDefaultProfile(): ControllerProfile = PixelWorldsDefaults.createProfile()

    private fun maybeStartOverlayAutomatically() {
        if (overlayStartRequested || OverlayService.getInstance() != null) return
        if (PermissionUtils.hasOverlayPermission(this) &&
            PermissionUtils.hasAccessibilityServiceEnabled(this)
        ) {
            startOverlayService(showOverlay = true)
        }
    }

    private fun startOverlayService(showOverlay: Boolean): Boolean {
        return try {
            val intent = Intent(this, OverlayService::class.java).apply {
                if (showOverlay) action = OverlayService.ACTION_SHOW
            }
            ContextCompat.startForegroundService(this, intent)
            overlayStartRequested = true
            scheduleRefresh(700L)
            true
        } catch (e: Exception) {
            overlayStartRequested = false
            Log.e(TAG, "Tidak dapat memulai OverlayService", e)
            Toast.makeText(
                this,
                getString(
                    R.string.overlay_start_error,
                    e.message ?: e.javaClass.simpleName
                ),
                Toast.LENGTH_LONG
            ).show()
            false
        }
    }

    private fun showFirstLaunchDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.first_launch_title)
            .setMessage(R.string.first_launch_message)
            .setPositiveButton(R.string.first_launch_continue) { _, _ ->
                requestNotificationPermission()
                if (!uiState.ready) focusFirstProblem()
            }
            .setCancelable(false)
            .show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATION_PERMISSION
            )
        }
    }

    private fun registerShizukuListeners() {
        try {
            Shizuku.addBinderReceivedListener(shizukuBinderReceivedListener)
        } catch (e: Exception) {
            Log.w(TAG, "Gagal mendaftarkan listener binder Shizuku", e)
        }
        try {
            Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
        } catch (e: Exception) {
            Log.w(TAG, "Gagal mendaftarkan listener kematian binder Shizuku", e)
        }
        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionResultListener)
        } catch (e: Exception) {
            Log.w(TAG, "Gagal mendaftarkan listener izin Shizuku", e)
        }
    }

    private fun unregisterShizukuListeners() {
        try {
            Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
        } catch (e: Exception) {
            Log.w(TAG, "Gagal melepas listener binder Shizuku", e)
        }
        try {
            Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
        } catch (e: Exception) {
            Log.w(TAG, "Gagal melepas listener kematian binder Shizuku", e)
        }
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionResultListener)
        } catch (e: Exception) {
            Log.w(TAG, "Gagal melepas listener izin Shizuku", e)
        }
    }

    private fun refreshShizukuOnUiThread() {
        runOnUiThread {
            if (!isDestroyed) refreshUi()
        }
    }

    private fun isShizukuInstalled(): Boolean {
        return try {
            packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun scheduleRefresh(delayMillis: Long) {
        if (!::binding.isInitialized) return
        binding.root.removeCallbacks(refreshRunnable)
        binding.root.postDelayed(refreshRunnable, delayMillis)
    }

    private fun setStatusIcon(image: ImageView, level: StatusLevel) {
        image.setImageResource(level.iconRes)
        image.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, level.colorRes)
        )
    }

    private fun setHeroStatusColor(colorRes: Int) {
        binding.viewHeroStatusDot.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, colorRes)
        )
    }

    private fun setTextWithFade(view: TextView, value: String) {
        if (view.text.toString() == value) return
        view.animate().cancel()
        if (!view.isLaidOut) {
            view.text = value
            view.alpha = 1f
            return
        }
        view.animate()
            .alpha(0f)
            .setDuration(80L)
            .withEndAction {
                if (isDestroyed) return@withEndAction
                view.text = value
                view.animate().alpha(1f).setDuration(120L).start()
            }
            .start()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class UiState(
        val gameInstalled: Boolean = false,
        val overlayGranted: Boolean = false,
        val shizukuInstalled: Boolean = false,
        val shizukuRunning: Boolean = false,
        val shizukuGranted: Boolean = false,
        val shizukuInitialized: Boolean = false,
        val accessibilityEnabled: Boolean = false,
        val accessibilityConnected: Boolean = false,
        val overlayReady: Boolean = false,
        val overlayVisible: Boolean = false,
        val editMode: Boolean = false
    ) {
        val inputReady: Boolean
            get() = shizukuInitialized || accessibilityConnected
        val ready: Boolean
            get() = overlayGranted && gameInstalled && inputReady
        val requiredProblemCount: Int
            get() = listOf(!overlayGranted, !inputReady, !gameInstalled).count { it }
    }

    private enum class StatusLevel(val colorRes: Int, val iconRes: Int) {
        SUCCESS(R.color.status_success, R.drawable.ic_check),
        WARNING(R.color.status_warning, R.drawable.ic_warning),
        ERROR(R.color.status_error, R.drawable.ic_error)
    }

    companion object {
        private const val TAG = "PWControllerMain"
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val REQUEST_NOTIFICATION_PERMISSION = 1003
    }
}
