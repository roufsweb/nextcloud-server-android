package com.nextcloud.server

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.util.Log
import android.provider.DocumentsContract
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.view.ContextThemeWrapper
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var nextcloudManager: NextcloudManager
    private val executor = Executors.newSingleThreadExecutor()

    // Views
    private lateinit var statusBadgeContainer: LinearLayout
    private lateinit var statusIndicatorDot: View
    private lateinit var statusPill: TextView
    private lateinit var statusText: TextView
    private lateinit var urlContainer: LinearLayout
    private lateinit var serverUrlText: TextView
    private lateinit var copyUrlButton: MaterialButton
    private lateinit var portInput: TextInputEditText
    private lateinit var serverActionButton: MaterialButton

    private lateinit var adminUserInputLayout: com.google.android.material.textfield.TextInputLayout
    private lateinit var adminPassInputLayout: com.google.android.material.textfield.TextInputLayout
    private lateinit var adminUserInput: TextInputEditText
    private lateinit var adminPassInput: TextInputEditText
    private lateinit var updateCredentialsButton: MaterialButton
    private lateinit var credentialsProgressContainer: View
    private lateinit var credentialsProgressStatusText: TextView
    private lateinit var credentialsProgressBar: LinearProgressIndicator

    private lateinit var currentDataDirText: TextView
    private lateinit var changeDataDirButton: MaterialButton
    private lateinit var migrationProgressContainer: LinearLayout
    private lateinit var migrationStatusText: TextView
    private lateinit var migrationProgressBar: ProgressBar
    private var pendingCopyData: Boolean = true

    private lateinit var trustedDomainsListText: TextView
    private lateinit var addDomainButton: MaterialButton

    private lateinit var externalDrivesContainer: LinearLayout
    private lateinit var viewLogsButton: MaterialButton

    // Bottom Navigation & 4-Tab Views
    private lateinit var bottomNavigation: com.google.android.material.bottomnavigation.BottomNavigationView
    private lateinit var tabDashboard: View
    private lateinit var tabStorage: View
    private lateinit var tabNetwork: View
    private lateinit var tabSettings: View
    private lateinit var topLogsButton: MaterialButton
    private lateinit var quickStorageCard: View
    private lateinit var quickNetworkCard: View
    private lateinit var quickSettingsCard: View
    private lateinit var storageUsedTotalText: TextView
    private lateinit var storageFreeText: TextView
    private lateinit var storageProgressBar: LinearProgressIndicator
    private lateinit var quickStorageUsageText: TextView
    private lateinit var removableStorageSectionTitle: View
    private lateinit var connectedDrivesCard: View

    // Initial Setup Progress Controls
    private lateinit var setupProgressContainer: View
    private lateinit var setupStatusText: TextView
    private lateinit var setupPercentText: TextView
    private lateinit var setupProgressBar: LinearProgressIndicator

    // Port Change Controls
    private lateinit var applyPortButton: MaterialButton
    private lateinit var portProgressContainer: View
    private lateinit var portProgressBar: LinearProgressIndicator
    private lateinit var portProgressStatusText: TextView

    // Theme / Appearance Controls
    private lateinit var themeToggleGroup: MaterialButtonToggleGroup
    private lateinit var themeSystemButton: MaterialButton
    private lateinit var themeLightButton: MaterialButton
    private lateinit var themeDarkButton: MaterialButton
    private lateinit var currentThemeSubtitleText: TextView

    // Background Execution & Permissions Controls
    private lateinit var switchStartOnBoot: MaterialSwitch
    private lateinit var startOnBootSubtitleText: TextView
    private lateinit var rowBatteryOptimization: View
    private lateinit var switchBatteryOptimization: MaterialSwitch
    private lateinit var batteryOptimizationBadge: TextView
    private lateinit var rowStoragePermission: View
    private lateinit var switchStoragePermission: MaterialSwitch
    private lateinit var storagePermissionBadge: TextView
    private lateinit var rowNotificationPermission: View
    private lateinit var switchNotificationPermission: MaterialSwitch
    private lateinit var notificationPermissionBadge: TextView
    private lateinit var switchWakeLock: MaterialSwitch

    private var isServerRunning = false

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            handleSelectedFolderUri(uri)
        }
    }

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        runOnUiThread {
            updatePermissionAndBackgroundToggles()
        }
        executor.execute {
            val mountManager = StorageMountManager(this@MainActivity)
            mountManager.syncConnectedStorages()
            refreshStorageAndDomains()
        }
    }

    private var pendingSafStorageUuid: String? = null
    private var activeSafDialog: com.google.android.material.bottomsheet.BottomSheetDialog? = null

    private val safPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        val uuid = pendingSafStorageUuid
        if (result.resultCode == Activity.RESULT_OK && uri != null && uuid != null) {
            val safManager = SdCardSafManager(this)
            safManager.saveSafUri(uuid, uri)
            SdCardAccessDialog.markDialogShown(this, uuid)

            // Notify running server service so UploadRelayService immediately monitors this storage
            val intent = Intent(this, NextcloudServerService::class.java).apply {
                action = NextcloudServerService.ACTION_SAF_GRANTED
                putExtra("uuid", uuid)
            }
            startService(intent)

            Toast.makeText(this, "Direct root write & SAF upload relay enabled!", Toast.LENGTH_LONG).show()
            refreshStorageAndDomains()
        } else if (uuid != null) {
            SdCardAccessDialog.markDialogShown(this, uuid)
        }
        pendingSafStorageUuid = null
    }

    private fun promptSafAccess(storage: StorageMountManager.DetectedStorage, isAutoPrompt: Boolean = false) {
        if (isFinishing || isDestroyed) return
        if (activeSafDialog != null && activeSafDialog?.isShowing == true) {
            Log.d("MainActivity", "SAF access dialog already displayed, skipping duplicate prompt")
            return
        }

        // Physical SD Cards are 100% managed via MediaStore root bridge and MUST NEVER prompt for SAF
        if (storage.isSdCard || storage.displayName.contains("SD", ignoreCase = true)) {
            Log.d("MainActivity", "Storage ${storage.id} is an SD Card, skipping SAF prompt")
            return
        }

        if (storage.isWritable || storage.isReadOnlyFs) {
            return
        }

        val cleanUuid = storage.id.removeSuffix("_root").removeSuffix("_uploads")
        if (isAutoPrompt && SdCardAccessDialog.wasDialogShown(this, cleanUuid)) {
            return
        }

        // Mark immediately in SharedPreferences to prevent re-entrancy from parallel status events
        SdCardAccessDialog.markDialogShown(this, cleanUuid)

        activeSafDialog = SdCardAccessDialog(
            context = this,
            storage = storage,
            onGrantClicked = { uuid ->
                pendingSafStorageUuid = uuid
                val intent = SdCardAccessDialog.buildSafPickerIntent(this, uuid)
                safPickerLauncher.launch(intent)
            },
            onSkipped = { uuid ->
                SdCardAccessDialog.markDialogShown(this, uuid)
                refreshStorageAndDomains()
            },
            onDismissed = {
                activeSafDialog = null
            }
        ).show()
    }

    private val migrationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val isRunning = intent?.getBooleanExtra("is_running", false) ?: false
            val stage = intent?.getStringExtra("stage") ?: ""
            val percent = intent?.getIntExtra("percent", 0) ?: 0
            val newPath = intent?.getStringExtra("new_path") ?: ""
            val success = intent?.getBooleanExtra("success", false) ?: false

            runOnUiThread {
                if (isRunning) {
                    migrationProgressContainer.visibility = View.VISIBLE
                    migrationStatusText.text = if (percent > 0) "$stage ($percent%)" else stage
                    if (percent > 0) {
                        migrationProgressBar.isIndeterminate = false
                        migrationProgressBar.progress = percent
                    } else {
                        migrationProgressBar.isIndeterminate = true
                    }
                    changeDataDirButton.isEnabled = false
                } else {
                    migrationProgressContainer.visibility = View.GONE
                    changeDataDirButton.isEnabled = true
                    if (success && newPath.isNotEmpty()) {
                        currentDataDirText.text = newPath
                        Toast.makeText(this@MainActivity, "Internal storage updated to $newPath", Toast.LENGTH_LONG).show()
                    } else if (!success) {
                        Toast.makeText(this@MainActivity, "Storage update failed. Check logs.", Toast.LENGTH_LONG).show()
                    }
                    refreshStorageAndDomains()
                }
            }
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra("status") ?: return
            val storageDisconnected = intent.getBooleanExtra("storage_disconnected", false)
            runOnUiThread {
                handleStatusUpdate(status, storageDisconnected)
            }
        }
    }

    private val storageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            runOnUiThread {
                refreshStorageAndDomains()
            }
        }
    }

    private val hardwareStorageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: ""
            Log.d("MainActivity", "Hardware storage event received: $action")
            runOnUiThread {
                refreshStorageAndDomains()
            }
            val isServerRunning = prefs.getBoolean("server_running", false)
            if (isServerRunning) {
                executor.execute {
                    try {
                        StorageMountManager(this@MainActivity).syncConnectedStorages()
                    } catch (e: Exception) {
                        Log.w("MainActivity", "Error syncing storages on hot-swap: ${e.message}")
                    }
                }
            }
        }
    }

    private val portProgressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val isRunning = intent?.getBooleanExtra("is_running", false) ?: false
            val status = intent?.getStringExtra("status") ?: ""
            val percent = intent?.getIntExtra("percent", 0) ?: 0
            val port = intent?.getIntExtra("port", 8000) ?: 8000
            val success = intent?.getBooleanExtra("success", false) ?: false

            runOnUiThread {
                if (isRunning) {
                    portProgressContainer.visibility = View.VISIBLE
                    portProgressStatusText.text = if (percent > 0) "$status ($percent%)" else status
                    if (percent > 0) {
                        portProgressBar.isIndeterminate = false
                        portProgressBar.progress = percent
                    } else {
                        portProgressBar.isIndeterminate = true
                    }
                    applyPortButton.isEnabled = false
                    portInput.isEnabled = false
                } else {
                    portProgressContainer.visibility = View.GONE
                    applyPortButton.isEnabled = true
                    portInput.isEnabled = true
                    if (success) {
                        portInput.setText(port.toString())
                        Toast.makeText(this@MainActivity, "Server port switched to $port!", Toast.LENGTH_SHORT).show()
                    } else if (status.startsWith("Failed")) {
                        Toast.makeText(this@MainActivity, status, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun updateSetupProgressUi(isRunning: Boolean, stage: String, percent: Int, success: Boolean) {
        if (isRunning) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setupProgressContainer.visibility = View.VISIBLE
            setupStatusText.text = stage
            setupPercentText.text = "$percent%"
            setupProgressBar.isIndeterminate = false
            setupProgressBar.progress = percent

            serverActionButton.isEnabled = false
            if (stage.contains("extract", ignoreCase = true)) {
                serverActionButton.text = "Extracting Nextcloud..."
            } else if (stage.contains("database", ignoreCase = true) || stage.contains("sqlite", ignoreCase = true)) {
                serverActionButton.text = "Initializing Database..."
            } else if (stage.contains("app", ignoreCase = true) || stage.contains("domain", ignoreCase = true) || stage.contains("policy", ignoreCase = true)) {
                serverActionButton.text = "Configuring Nextcloud..."
            } else {
                serverActionButton.text = "Starting Server..."
            }
            serverActionButton.setIconResource(R.drawable.ic_play_arrow)
            serverActionButton.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.m3_primary)
            serverActionButton.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.m3_on_primary))
            serverActionButton.iconTint = ContextCompat.getColorStateList(this@MainActivity, R.color.m3_on_primary)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setupProgressContainer.visibility = View.GONE
            if (isServerRunning) {
                serverActionButton.isEnabled = true
                serverActionButton.text = "Stop Server"
                serverActionButton.setIconResource(R.drawable.ic_stop)
                serverActionButton.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.m3_danger_container)
                serverActionButton.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.m3_on_danger_container))
                serverActionButton.iconTint = ContextCompat.getColorStateList(this@MainActivity, R.color.m3_on_danger_container)
            } else if (!success) {
                serverActionButton.isEnabled = true
                serverActionButton.text = "Start Server"
                serverActionButton.setIconResource(R.drawable.ic_play_arrow)
                serverActionButton.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.m3_primary)
                serverActionButton.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.m3_on_primary))
                serverActionButton.iconTint = ContextCompat.getColorStateList(this@MainActivity, R.color.m3_on_primary)
                if (stage.isNotEmpty()) {
                    Toast.makeText(this@MainActivity, stage, Toast.LENGTH_LONG).show()
                }
            }
            if (success) {
                refreshStorageAndDomains()
            }
        }
    }

    private val setupProgressListener = NextcloudServerService.SetupProgressListener { isRunning, stage, percent, success ->
        runOnUiThread {
            updateSetupProgressUi(isRunning, stage, percent, success)
        }
    }

    private val setupReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val isRunning = intent?.getBooleanExtra("is_running", false) ?: false
            val stage = intent?.getStringExtra("stage") ?: ""
            val percent = intent?.getIntExtra("percent", 0) ?: 0
            val success = intent?.getBooleanExtra("success", false) ?: false

            runOnUiThread {
                updateSetupProgressUi(isRunning, stage, percent, success)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = AppPreferences(this).sharedPreferences
        applySavedTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        nextcloudManager = NextcloudManager(this)

        initViews()
        loadSavedConfig()
        requestStoragePermissionsIfNeeded()
        refreshStorageAndDomains()
    }

    private fun applySavedTheme() {
        when (prefs.getString("app_theme_mode", "system")) {
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    private fun initViews() {
        statusBadgeContainer = findViewById(R.id.statusBadgeContainer)
        statusIndicatorDot = findViewById(R.id.statusIndicatorDot)
        statusPill = findViewById(R.id.statusPill)
        statusText = findViewById(R.id.statusText)
        urlContainer = findViewById(R.id.urlContainer)
        serverUrlText = findViewById(R.id.serverUrlText)
        copyUrlButton = findViewById(R.id.copyUrlButton)
        portInput = findViewById(R.id.portInput)
        serverActionButton = findViewById(R.id.serverActionButton)

        adminUserInputLayout = findViewById(R.id.adminUserInputLayout)
        adminPassInputLayout = findViewById(R.id.adminPassInputLayout)
        adminUserInput = findViewById(R.id.adminUserInput)
        adminPassInput = findViewById(R.id.adminPassInput)
        updateCredentialsButton = findViewById(R.id.updateCredentialsButton)
        credentialsProgressContainer = findViewById(R.id.credentialsProgressContainer)
        credentialsProgressStatusText = findViewById(R.id.credentialsProgressStatusText)
        credentialsProgressBar = findViewById(R.id.credentialsProgressBar)

        currentDataDirText = findViewById(R.id.currentDataDirText)
        changeDataDirButton = findViewById(R.id.changeDataDirButton)
        migrationProgressContainer = findViewById(R.id.migrationProgressContainer)
        migrationStatusText = findViewById(R.id.migrationStatusText)
        migrationProgressBar = findViewById(R.id.migrationProgressBar)

        trustedDomainsListText = findViewById(R.id.trustedDomainsListText)
        addDomainButton = findViewById(R.id.addDomainButton)

        externalDrivesContainer = findViewById(R.id.externalDrivesContainer)
        viewLogsButton = findViewById(R.id.viewLogsButton)

        // 4-Tab Navigation Views
        bottomNavigation = findViewById(R.id.bottomNavigation)
        tabDashboard = findViewById(R.id.tabDashboard)
        tabStorage = findViewById(R.id.tabStorage)
        tabNetwork = findViewById(R.id.tabNetwork)
        tabSettings = findViewById(R.id.tabSettings)
        topLogsButton = findViewById(R.id.topLogsButton)
        quickStorageCard = findViewById(R.id.quickStorageCard)
        quickNetworkCard = findViewById(R.id.quickNetworkCard)
        quickSettingsCard = findViewById(R.id.quickSettingsCard)
        storageUsedTotalText = findViewById(R.id.storageUsedTotalText)
        storageFreeText = findViewById(R.id.storageFreeText)
        storageProgressBar = findViewById(R.id.storageProgressBar)
        quickStorageUsageText = findViewById(R.id.quickStorageUsageText)
        removableStorageSectionTitle = findViewById(R.id.removableStorageSectionTitle)
        connectedDrivesCard = findViewById(R.id.connectedDrivesCard)

        bottomNavigation.setOnItemSelectedListener { item ->
            val index = when (item.itemId) {
                R.id.nav_dashboard -> 0
                R.id.nav_storage -> 1
                R.id.nav_network -> 2
                R.id.nav_settings -> 3
                else -> 0
            }
            prefs.edit().putInt("active_tab_index", index).apply()
            switchTab(index)
            true
        }

        // Dashboard Quick Glance Jump Clickers
        quickStorageCard.setOnClickListener { bottomNavigation.selectedItemId = R.id.nav_storage }
        quickNetworkCard.setOnClickListener { bottomNavigation.selectedItemId = R.id.nav_network }
        quickSettingsCard.setOnClickListener { bottomNavigation.selectedItemId = R.id.nav_settings }
        topLogsButton.setOnClickListener { showLogsDialog() }

        // Button Listeners
        serverActionButton.setOnClickListener {
            if (isServerRunning) {
                stopServer()
            } else {
                startServer()
            }
        }
        serverUrlText.setOnClickListener { openBrowser() }

        copyUrlButton.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Nextcloud URL", serverUrlText.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Server URL copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        updateCredentialsButton.setOnClickListener { updateAdminCredentials() }
        changeDataDirButton.setOnClickListener { showStorageSelectionDialog() }
        addDomainButton.setOnClickListener { showAddDomainDialog() }
        trustedDomainsListText.setOnClickListener { showManageDomainsDialog() }
        viewLogsButton.setOnClickListener { showLogsDialog() }

        // Initial Setup Progress Views
        setupProgressContainer = findViewById(R.id.setupProgressContainer)
        setupStatusText = findViewById(R.id.setupStatusText)
        setupPercentText = findViewById(R.id.setupPercentText)
        setupProgressBar = findViewById(R.id.setupProgressBar)

        // Port Configuration Views & Listeners
        applyPortButton = findViewById(R.id.applyPortButton)
        portProgressContainer = findViewById(R.id.portProgressContainer)
        portProgressBar = findViewById(R.id.portProgressBar)
        portProgressStatusText = findViewById(R.id.portProgressStatusText)

        applyPortButton.setOnClickListener { handleApplyPort() }
        portInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                handleApplyPort()
                true
            } else {
                false
            }
        }

        // Theme / Appearance Controls
        themeToggleGroup = findViewById(R.id.themeToggleGroup)
        themeSystemButton = findViewById(R.id.themeSystemButton)
        themeLightButton = findViewById(R.id.themeLightButton)
        themeDarkButton = findViewById(R.id.themeDarkButton)
        currentThemeSubtitleText = findViewById(R.id.currentThemeSubtitleText)
        setupThemeToggle()

        // Background Execution & Permissions Controls
        switchStartOnBoot = findViewById(R.id.switchStartOnBoot)
        startOnBootSubtitleText = findViewById(R.id.startOnBootSubtitleText)
        rowBatteryOptimization = findViewById(R.id.rowBatteryOptimization)
        switchBatteryOptimization = findViewById(R.id.switchBatteryOptimization)
        batteryOptimizationBadge = findViewById(R.id.batteryOptimizationBadge)
        rowStoragePermission = findViewById(R.id.rowStoragePermission)
        switchStoragePermission = findViewById(R.id.switchStoragePermission)
        storagePermissionBadge = findViewById(R.id.storagePermissionBadge)
        rowNotificationPermission = findViewById(R.id.rowNotificationPermission)
        switchNotificationPermission = findViewById(R.id.switchNotificationPermission)
        notificationPermissionBadge = findViewById(R.id.notificationPermissionBadge)
        switchWakeLock = findViewById(R.id.switchWakeLock)
        setupBackgroundAndPermissionToggles()
    }

    private fun setupBackgroundAndPermissionToggles() {
        // 1. Start on Boot Toggle
        val startOnBoot = prefs.getBoolean("start_on_boot", false)
        switchStartOnBoot.isChecked = startOnBoot
        updateStartOnBootSubtitle(startOnBoot)

        switchStartOnBoot.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("start_on_boot", isChecked).apply()
            updateStartOnBootSubtitle(isChecked)
            Toast.makeText(
                this,
                if (isChecked) "Nextcloud server will start automatically when device boots"
                else "Start on boot disabled",
                Toast.LENGTH_SHORT
            ).show()
        }

        // 2. Battery Optimization Toggle
        val batteryToggleAction: () -> Unit = {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val isIgnoring = powerManager.isIgnoringBatteryOptimizations(packageName)
            if (!isIgnoring) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                }
            } else {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(intent)
                Toast.makeText(this, "To optimize battery, find Nextcloud in system settings.", Toast.LENGTH_SHORT).show()
            }
        }
        rowBatteryOptimization.setOnClickListener { batteryToggleAction() }
        switchBatteryOptimization.setOnClickListener { batteryToggleAction() }

        // 3. Storage Permission Toggle
        val storageToggleAction: () -> Unit = {
            val isGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (!isGranted) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        manageStorageLauncher.launch(intent)
                    } catch (e: Exception) {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        manageStorageLauncher.launch(intent)
                    }
                } else {
                    requestPermissions(arrayOf(
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ), 1001)
                }
            } else {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
                Toast.makeText(this, "Manage storage permission in app settings", Toast.LENGTH_SHORT).show()
            }
        }
        rowStoragePermission.setOnClickListener { storageToggleAction() }
        switchStoragePermission.setOnClickListener { storageToggleAction() }

        // 4. Notification Permission Toggle
        val notificationToggleAction: () -> Unit = {
            val areEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
            if (!areEnabled) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1002)
                } else {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    }
                    startActivity(intent)
                }
            } else {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
                startActivity(intent)
            }
        }
        rowNotificationPermission.setOnClickListener { notificationToggleAction() }
        switchNotificationPermission.setOnClickListener { notificationToggleAction() }

        // 5. CPU Wake Lock Toggle
        val wakeLockEnabled = prefs.getBoolean("wake_lock_enabled", true)
        switchWakeLock.isChecked = wakeLockEnabled
        switchWakeLock.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("wake_lock_enabled", isChecked).apply()
            if (isServerRunning) {
                val intent = Intent(this, NextcloudServerService::class.java).apply {
                    action = NextcloudServerService.ACTION_TOGGLE_WAKELOCK
                    putExtra("enabled", isChecked)
                }
                startService(intent)
            }
            Toast.makeText(
                this,
                if (isChecked) "CPU wake lock enabled" else "CPU wake lock disabled",
                Toast.LENGTH_SHORT
            ).show()
        }

        updatePermissionAndBackgroundToggles()
    }

    private fun updateStartOnBootSubtitle(enabled: Boolean) {
        startOnBootSubtitleText.text = if (enabled) {
            "Auto-start server when device boots (Active)"
        } else {
            "Auto-start server when device boots (Disabled)"
        }
    }

    private fun updatePermissionAndBackgroundToggles() {
        // 1. Start on boot
        val startOnBoot = prefs.getBoolean("start_on_boot", false)
        if (switchStartOnBoot.isChecked != startOnBoot) {
            switchStartOnBoot.isChecked = startOnBoot
        }
        updateStartOnBootSubtitle(startOnBoot)

        // 2. Battery Optimization
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnoringBattery = powerManager.isIgnoringBatteryOptimizations(packageName)
        switchBatteryOptimization.isChecked = isIgnoringBattery
        if (isIgnoringBattery) {
            batteryOptimizationBadge.text = "Unrestricted"
            batteryOptimizationBadge.setTextColor(ContextCompat.getColor(this, R.color.status_green))
            batteryOptimizationBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_green_bg)
        } else {
            batteryOptimizationBadge.text = "Optimized"
            batteryOptimizationBadge.setTextColor(ContextCompat.getColor(this, R.color.status_orange))
            batteryOptimizationBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_orange_bg)
        }

        // 3. Storage Permission
        val isStorageGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        switchStoragePermission.isChecked = isStorageGranted
        if (isStorageGranted) {
            storagePermissionBadge.text = "Granted"
            storagePermissionBadge.setTextColor(ContextCompat.getColor(this, R.color.status_green))
            storagePermissionBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_green_bg)
        } else {
            storagePermissionBadge.text = "Restricted"
            storagePermissionBadge.setTextColor(ContextCompat.getColor(this, R.color.status_red))
            storagePermissionBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_red_bg)
        }

        // 4. Notification Permission
        val areNotificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        switchNotificationPermission.isChecked = areNotificationsEnabled
        if (areNotificationsEnabled) {
            notificationPermissionBadge.text = "Allowed"
            notificationPermissionBadge.setTextColor(ContextCompat.getColor(this, R.color.status_green))
            notificationPermissionBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_green_bg)
        } else {
            notificationPermissionBadge.text = "Blocked"
            notificationPermissionBadge.setTextColor(ContextCompat.getColor(this, R.color.status_orange))
            notificationPermissionBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_orange_bg)
        }

        // 5. Wake lock
        val wakeLockEnabled = prefs.getBoolean("wake_lock_enabled", true)
        if (switchWakeLock.isChecked != wakeLockEnabled) {
            switchWakeLock.isChecked = wakeLockEnabled
        }
    }

    private fun setupThemeToggle() {
        val currentMode = prefs.getString("app_theme_mode", "system")
        when (currentMode) {
            "light" -> {
                themeLightButton.isChecked = true
                currentThemeSubtitleText.text = "Always use light theme"
            }
            "dark" -> {
                themeDarkButton.isChecked = true
                currentThemeSubtitleText.text = "Always use dark theme"
            }
            else -> {
                themeSystemButton.isChecked = true
                currentThemeSubtitleText.text = "Follow system theme"
            }
        }

        themeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val newMode = when (checkedId) {
                R.id.themeLightButton -> "light"
                R.id.themeDarkButton -> "dark"
                else -> "system"
            }
            val activeMode = prefs.getString("app_theme_mode", "system")
            if (newMode != activeMode) {
                prefs.edit().putString("app_theme_mode", newMode).apply()
                prefs.edit().putInt("active_tab_index", 3).apply()
                when (newMode) {
                    "light" -> {
                        currentThemeSubtitleText.text = "Always use light theme"
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                    }
                    "dark" -> {
                        currentThemeSubtitleText.text = "Always use dark theme"
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                    }
                    else -> {
                        currentThemeSubtitleText.text = "Follow system theme"
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                    }
                }
            }
        }
    }

    private fun switchTab(index: Int) {
        tabDashboard.visibility = if (index == 0) View.VISIBLE else View.GONE
        tabStorage.visibility = if (index == 1) View.VISIBLE else View.GONE
        tabNetwork.visibility = if (index == 2) View.VISIBLE else View.GONE
        tabSettings.visibility = if (index == 3) View.VISIBLE else View.GONE
    }

    private fun loadSavedConfig() {
        val port = prefs.getInt("server_port", 8000)
        portInput.setText(port.toString())

        val savedUser = prefs.getString("admin_user", "admin")
        val savedPass = prefs.getString("admin_pass", "NextcloudAdmin123!")
        adminUserInput.setText(savedUser)
        adminPassInput.setText(savedPass)

        val defaultDataDir = File(filesDir, "nextcloud/data").absolutePath
        val savedDataDir = prefs.getString("data_dir", defaultDataDir)
        currentDataDirText.text = savedDataDir

        val savedTab = prefs.getInt("active_tab_index", 0)
        val targetNavId = when (savedTab) {
            1 -> R.id.nav_storage
            2 -> R.id.nav_network
            3 -> R.id.nav_settings
            else -> R.id.nav_dashboard
        }
        bottomNavigation.selectedItemId = targetNavId
        switchTab(savedTab)

        if (NextcloudServerService.isServerRunning) {
            handleStatusUpdate(NextcloudServerService.lastStatus, false)
        }
    }

    private fun requestStoragePermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                createMaterialAlertDialog()
                    .setTitle("Storage Permission Required")
                    .setMessage("Nextcloud Server requires storage management permission to access local folders and automatically mount external USB OTG, SD card, and SSD drives (FAT32, exFAT, etc.).\n\nPlease allow 'All files access' in system settings.")
                    .setPositiveButton("Grant Access") { _, _ ->
                        try {
                            val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:$packageName")
                            }
                            manageStorageLauncher.launch(intent)
                        } catch (e: Exception) {
                            val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            manageStorageLauncher.launch(intent)
                        }
                    }
                    .setNegativeButton("Later", null)
                    .show()
            }
        } else {
            val permissions = arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            val needed = permissions.filter {
                checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (needed.isNotEmpty()) {
                requestPermissions(needed.toTypedArray(), 1001)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1002)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        runOnUiThread {
            updatePermissionAndBackgroundToggles()
        }
        executor.execute {
            val mountManager = StorageMountManager(this@MainActivity)
            mountManager.syncConnectedStorages()
            refreshStorageAndDomains()
        }
    }

    private fun startServer() {
        if (NextcloudServerService.isSetupRunning) {
            return
        }

        val portStr = portInput.text.toString().trim()
        val port = portStr.toIntOrNull() ?: 8000
        if (port < 1024 || port > 65535) {
            Toast.makeText(this, "Port must be between 1024 and 65535 (ports below 1024 require root)", Toast.LENGTH_LONG).show()
            return
        }

        if (isPortInUse(port)) {
            Toast.makeText(this, "Port $port is in use by another app on this phone. Please choose another port (e.g. 8000).", Toast.LENGTH_LONG).show()
            return
        }

        // Pre-flight check 1: Target storage directory & permissions
        val defaultDataDir = File(filesDir, "nextcloud/data").absolutePath
        val targetDataDir = prefs.getString("data_dir", defaultDataDir) ?: defaultDataDir
        val isInternal = targetDataDir.startsWith(filesDir.absolutePath) ||
                         targetDataDir.startsWith("/data/data/$packageName") ||
                         targetDataDir.startsWith("/data/user/0/$packageName")

        if (!isInternal) {
            val isStorageGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }

            if (!isStorageGranted) {
                createMaterialAlertDialog()
                    .setTitle("Storage Permission Required")
                    .setMessage("Nextcloud is configured to use external storage:\n\n$targetDataDir\n\nAndroid requires storage management permission so Nextcloud can read and write your files.\n\nPlease grant permission, or reset to internal app storage.")
                    .setPositiveButton("Grant Access") { _, _ ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            try {
                                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                    data = Uri.parse("package:$packageName")
                                }
                                manageStorageLauncher.launch(intent)
                            } catch (e: Exception) {
                                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                manageStorageLauncher.launch(intent)
                            }
                        } else {
                            requestPermissions(arrayOf(
                                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                            ), 1001)
                        }
                    }
                    .setNegativeButton("Use Internal Storage") { _, _ ->
                        prefs.edit().putString("data_dir", defaultDataDir).apply()
                        currentDataDirText.text = defaultDataDir
                        Toast.makeText(this, "Reset to internal storage. Tap Start Server to run.", Toast.LENGTH_SHORT).show()
                    }
                    .setNeutralButton("Cancel", null)
                    .show()
                return
            }
        }

        // Pre-flight check 2: Storage space validation
        val freeBytes = filesDir.usableSpace
        val minRequiredBytes = 1200L * 1024 * 1024 // 1.2 GB
        if (freeBytes < minRequiredBytes && !nextcloudManager.isInstalled()) {
            val freeMb = freeBytes / (1024 * 1024)
            createMaterialAlertDialog()
                .setTitle("Low Internal Storage")
                .setMessage("Your device has only ${freeMb} MB free space. Nextcloud requires approximately 1.2 GB of free space to extract and install core files.\n\nPlease free up some storage before proceeding.")
                .setPositiveButton("Try Anyway") { _, _ ->
                    launchServerService(port)
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        launchServerService(port)
    }

    private fun launchServerService(port: Int) {
        prefs.edit().putInt("server_port", port).apply()

        if (!nextcloudManager.isInstalled()) {
            val adminUser = adminUserInput.text.toString().trim().ifEmpty { "admin" }
            val adminPass = adminPassInput.text.toString().trim().ifEmpty { "NextcloudAdmin123!" }
            prefs.edit().putString("admin_user", adminUser).putString("admin_pass", adminPass).apply()
        }

        if (!nextcloudManager.isInstalled()) {
            setupProgressContainer.visibility = View.VISIBLE
            setupStatusText.text = "Preparing server setup..."
            setupPercentText.text = "2%"
            setupProgressBar.isIndeterminate = false
            setupProgressBar.progress = 2
            serverActionButton.isEnabled = false
            serverActionButton.text = "Starting Server..."
        }

        val intent = Intent(this, NextcloudServerService::class.java).apply {
            putExtra("port", port)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        handleStatusUpdate("Starting server on port $port...", false)
    }

    private fun stopServer() {
        val intent = Intent(this, NextcloudServerService::class.java).apply {
            action = "STOP"
        }
        startService(intent)
        handleStatusUpdate("Server is stopped", false)
    }

    private fun openBrowser() {
        val url = serverUrlText.text.toString()
        if (url.startsWith("http")) {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(browserIntent)
        }
    }

    private fun handleStatusUpdate(status: String, storageDisconnected: Boolean = false) {
        if (storageDisconnected) {
            statusIndicatorDot.clearAnimation()
            statusIndicatorDot.setBackgroundResource(R.drawable.status_dot_error)
            statusPill.text = "Attention"
            statusPill.setTextColor(ContextCompat.getColor(this, R.color.status_red))
            statusBadgeContainer.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_red_bg)
            statusText.text = "Storage Missing"

            serverActionButton.visibility = View.VISIBLE
            serverActionButton.isEnabled = true
            serverActionButton.text = "Start Server"
            serverActionButton.setIconResource(R.drawable.ic_play_arrow)
            serverActionButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.m3_primary)
            serverActionButton.setTextColor(ContextCompat.getColor(this, R.color.m3_on_primary))
            serverActionButton.iconTint = ContextCompat.getColorStateList(this, R.color.m3_on_primary)
            showStorageLostDialog()
            return
        }

        if (status.contains("running", ignoreCase = true) || status.contains("http://", ignoreCase = true)) {
            isServerRunning = true
            setupProgressContainer.visibility = View.GONE
            statusIndicatorDot.setBackgroundResource(R.drawable.status_dot_running)

            val pulse = AlphaAnimation(1.0f, 0.35f).apply {
                duration = 900
                repeatMode = Animation.REVERSE
                repeatCount = Animation.INFINITE
            }
            statusIndicatorDot.startAnimation(pulse)

            statusPill.text = "Online"
            statusPill.setTextColor(ContextCompat.getColor(this, R.color.status_green))
            statusBadgeContainer.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_green_bg)

            val port = prefs.getInt("server_port", 8000)
            statusText.text = "All services active"
            val wifiIp = nextcloudManager.getWifiIpAddress()
            val primaryUrl = if (wifiIp != null) "http://$wifiIp:$port" else "http://localhost:$port"

            serverUrlText.text = primaryUrl
            urlContainer.visibility = View.VISIBLE
            if (!NextcloudServerService.isPortChangeRunning) {
                portInput.isEnabled = true
                applyPortButton.isEnabled = true
            }

            // Unified Stop Action
            serverActionButton.visibility = View.VISIBLE
            serverActionButton.isEnabled = true
            serverActionButton.text = "Stop Server"
            serverActionButton.setIconResource(R.drawable.ic_stop)
            serverActionButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.m3_danger_container)
            serverActionButton.setTextColor(ContextCompat.getColor(this, R.color.m3_on_danger_container))
            serverActionButton.iconTint = ContextCompat.getColorStateList(this, R.color.m3_on_danger_container)

            refreshStorageAndDomains()
        } else if (status.contains("starting", ignoreCase = true) || status.contains("installing", ignoreCase = true) || status.contains("extracting", ignoreCase = true) || status.contains("preparing", ignoreCase = true)) {
            statusIndicatorDot.clearAnimation()
            statusIndicatorDot.setBackgroundResource(R.drawable.status_dot_starting)
            statusPill.text = "Starting..."
            statusPill.setTextColor(ContextCompat.getColor(this, R.color.status_orange))
            statusBadgeContainer.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_orange_bg)
            statusText.text = status

            if (!NextcloudServerService.isSetupRunning) {
                serverActionButton.visibility = View.VISIBLE
                serverActionButton.isEnabled = false
                serverActionButton.text = "Starting Server..."
                serverActionButton.setIconResource(R.drawable.ic_play_arrow)
                serverActionButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.m3_primary)
                serverActionButton.setTextColor(ContextCompat.getColor(this, R.color.m3_on_primary))
                serverActionButton.iconTint = ContextCompat.getColorStateList(this, R.color.m3_on_primary)
            }
        } else {
            if (NextcloudServerService.isSetupRunning) {
                return
            }
            isServerRunning = false
            statusIndicatorDot.clearAnimation()
            statusIndicatorDot.setBackgroundResource(R.drawable.status_dot_stopped)
            statusPill.text = "Offline"
            statusPill.setTextColor(ContextCompat.getColor(this, R.color.text_grey))
            statusBadgeContainer.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_stopped_bg)
            statusText.text = "Services idle"
            urlContainer.visibility = View.GONE
            if (!NextcloudServerService.isPortChangeRunning) {
                portInput.isEnabled = true
                applyPortButton.isEnabled = true
            }

            // Unified Start Action
            serverActionButton.visibility = View.VISIBLE
            serverActionButton.isEnabled = true
            serverActionButton.text = "Start Server"
            serverActionButton.setIconResource(R.drawable.ic_play_arrow)
            serverActionButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.m3_primary)
            serverActionButton.setTextColor(ContextCompat.getColor(this, R.color.m3_on_primary))
            serverActionButton.iconTint = ContextCompat.getColorStateList(this, R.color.m3_on_primary)
        }
    }

    private fun createMaterialAlertDialog(): MaterialAlertDialogBuilder {
        return MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_App_MaterialAlertDialog)
    }

    private fun showStorageLostDialog() {
        createMaterialAlertDialog()
            .setTitle("Storage Disconnected")
            .setMessage("The external drive hosting Nextcloud data was disconnected.\n\nThe server was paused in maintenance mode to protect files.\n\nFallback to internal app storage?")
            .setPositiveButton("Fallback to Internal") { _, _ ->
                val internalPath = File(filesDir, "nextcloud/data").absolutePath
                startStorageMigrationService(internalPath, copyData = false)
            }
            .setNegativeButton("Wait for Reconnect", null)
            .setCancelable(false)
            .show()
    }

    private fun refreshStorageAndDomains() {
        executor.execute {
            val isSetupActive = NextcloudServerService.isSetupRunning
            val isNcInstalled = nextcloudManager.isInstalled()

            val currentDir = if (isSetupActive || !isNcInstalled) {
                prefs.getString("data_dir", "${filesDir.absolutePath}/nextcloud/data")
                    ?: "${filesDir.absolutePath}/nextcloud/data"
            } else {
                nextcloudManager.getDataDirectory()
            }

            val domains = if (isSetupActive || !isNcInstalled) {
                val list = mutableListOf("localhost", "127.0.0.1")
                nextcloudManager.getWifiIpAddress()?.let { list.add(it) }
                list
            } else {
                nextcloudManager.getTrustedDomains()
            }

            val mountManager = StorageMountManager(this@MainActivity)
            val storages = mountManager.getRemovableStorages()
            val hasRemovable = storages.isNotEmpty()

            val dirFile = File(currentDir)
            val totalBytes = dirFile.totalSpace
            val freeBytes = dirFile.freeSpace
            val usedBytes = if (totalBytes > freeBytes) totalBytes - freeBytes else 0L
            val totalGb = String.format(java.util.Locale.US, "%.1f GB", totalBytes / (1024.0 * 1024.0 * 1024.0))
            val usedGb = String.format(java.util.Locale.US, "%.1f GB", usedBytes / (1024.0 * 1024.0 * 1024.0))
            val freeGb = String.format(java.util.Locale.US, "%.1f GB free", freeBytes / (1024.0 * 1024.0 * 1024.0))
            val percent = if (totalBytes > 0) ((usedBytes * 100) / totalBytes).toInt() else 0

            runOnUiThread {
                currentDataDirText.text = currentDir
                trustedDomainsListText.text = if (domains.isEmpty()) {
                    "localhost\n127.0.0.1"
                } else {
                    domains.joinToString("\n")
                }

                if (hasRemovable) {
                    removableStorageSectionTitle.visibility = View.VISIBLE
                    connectedDrivesCard.visibility = View.VISIBLE
                    quickStorageUsageText.text = "$usedGb used • $freeGb • ${storages.size} external drive(s)"

                    externalDrivesContainer.removeAllViews()
                    for ((index, storage) in storages.withIndex()) {
                        val itemView = layoutInflater.inflate(R.layout.item_external_storage_drive, externalDrivesContainer, false)
                        val iconView = itemView.findViewById<ImageView>(R.id.driveIcon)
                        val titleView = itemView.findViewById<TextView>(R.id.driveTitleText)
                        val subtitleView = itemView.findViewById<TextView>(R.id.driveSubtitleText)
                        val toggleSwitch = itemView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.driveToggleSwitch)
                        val pathView = itemView.findViewById<TextView>(R.id.drivePathText)
                        val storageUsedTotalText = itemView.findViewById<TextView>(R.id.driveStorageUsedTotalText)
                        val storageFreeText = itemView.findViewById<TextView>(R.id.driveStorageFreeText)
                        val storageProgressBar = itemView.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.driveStorageProgressBar)

                        val isSd = storage.displayName.contains("SD", ignoreCase = true)
                        iconView.setImageResource(if (isSd) R.drawable.ic_sd_storage else R.drawable.ic_usb)

                        val labelSuffix = if (storage.label.isNotBlank()) " [${storage.label}]" else ""
                        titleView.text = "${storage.displayName}$labelSuffix"

                        val statusHint = when {
                            storage.isReadOnlyFs -> "⚠️ Hardware Read-Only (${storage.fsType}) • Needs exFAT/FAT32 format"
                            else -> "Full Read & Write Access • ${storage.fsType}"
                        }
                        subtitleView.text = statusHint
                        subtitleView.setTextColor(
                            ContextCompat.getColor(
                                this@MainActivity,
                                if (storage.isReadOnlyFs) R.color.m3_danger
                                else R.color.m3_on_surface_variant
                            )
                        )

                        pathView.text = storage.displayPath

                        val totalBytes = storage.totalBytes
                        val freeBytes = storage.freeBytes
                        val usedBytes = if (totalBytes > freeBytes) totalBytes - freeBytes else 0L

                        val totalGb = totalBytes / (1024.0 * 1024.0 * 1024.0)
                        val usedGb = usedBytes / (1024.0 * 1024.0 * 1024.0)
                        val freeGb = freeBytes / (1024.0 * 1024.0 * 1024.0)

                        storageUsedTotalText.text = String.format(java.util.Locale.US, "%.1f GB of %.1f GB used", usedGb, totalGb)
                        storageFreeText.text = String.format(java.util.Locale.US, "%.1f GB free", freeGb)

                        val progress = if (totalBytes > 0) ((usedBytes.toDouble() / totalBytes.toDouble()) * 100).toInt().coerceIn(0, 100) else 0
                        storageProgressBar.progress = progress

                        val isEnabled = mountManager.isStorageEnabled(storage.id)
                        toggleSwitch.isChecked = isEnabled

                        itemView.alpha = if (isEnabled) 1.0f else 0.5f

                        itemView.isClickable = true
                        itemView.isFocusable = true
                        itemView.setOnClickListener {
                            if (storage.isReadOnlyFs) {
                                MaterialAlertDialogBuilder(this@MainActivity)
                                    .setTitle("${storage.displayName} is Read-Only")
                                    .setMessage("This drive is mounted as Read-Only by Android (formatted as NTFS or unplugged without safe removal).\n\nTo allow write access in Nextcloud, reformat the drive as exFAT or FAT32, or run disk error checking (chkdsk) on PC.")
                                    .setPositiveButton("OK", null)
                                    .show()
                            } else {
                                Toast.makeText(this@MainActivity, "${storage.displayName}: Full read & write access active", Toast.LENGTH_SHORT).show()
                            }
                        }

                        toggleSwitch.setOnCheckedChangeListener { _, isChecked ->
                            mountManager.setStorageEnabled(storage.id, isChecked)
                            itemView.alpha = if (isChecked) 1.0f else 0.5f
                            Toast.makeText(
                                this@MainActivity,
                                if (isChecked) "${storage.displayName} enabled in Nextcloud"
                                else "${storage.displayName} disabled from Nextcloud",
                                Toast.LENGTH_SHORT
                            ).show()
                        }

                        externalDrivesContainer.addView(itemView)

                        if (index < storages.size - 1) {
                            val divider = View(this@MainActivity).apply {
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    (1 * resources.displayMetrics.density).toInt()
                                ).apply {
                                    topMargin = (10 * resources.displayMetrics.density).toInt()
                                    bottomMargin = (10 * resources.displayMetrics.density).toInt()
                                }
                                setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.m3_outline_variant))
                            }
                            externalDrivesContainer.addView(divider)
                        }
                    }
                } else {
                    removableStorageSectionTitle.visibility = View.GONE
                    connectedDrivesCard.visibility = View.GONE
                    externalDrivesContainer.removeAllViews()
                    quickStorageUsageText.text = "$usedGb used • $freeGb • Internal disk"
                }

                storageProgressBar.progress = percent
                storageUsedTotalText.text = "$usedGb of $totalGb used"
                storageFreeText.text = freeGb
            }
        }
    }

    private fun updateAdminCredentials() {
        adminUserInputLayout.error = null
        adminPassInputLayout.error = null

        val user = adminUserInput.text.toString().trim()
        val pass = adminPassInput.text.toString().trim()
        if (user.isEmpty()) {
            adminUserInputLayout.error = "Username cannot be empty"
            return
        }
        if (user.contains(" ")) {
            adminUserInputLayout.error = "Username cannot contain spaces"
            return
        }
        if (pass.isEmpty()) {
            adminPassInputLayout.error = "Password cannot be empty"
            return
        }
        if (pass.length < 8) {
            adminPassInputLayout.error = "Password must be at least 8 characters (Nextcloud minimum requirement)"
            return
        }

        val activeSavedUser = prefs.getString("admin_user", "admin") ?: "admin"
        val activeSavedPass = prefs.getString("admin_pass", "NextcloudAdmin123!") ?: "NextcloudAdmin123!"

        if (user == activeSavedUser && pass == activeSavedPass) {
            Toast.makeText(this, "Administrator credentials are unchanged.", Toast.LENGTH_SHORT).show()
            return
        }

        if (!nextcloudManager.isInstalled()) {
            prefs.edit().putString("admin_user", user).putString("admin_pass", pass).apply()
            Toast.makeText(this, "Admin credentials saved! They will be used to initialize Nextcloud when the server starts.", Toast.LENGTH_LONG).show()
            return
        }

        val executeCredentialChange: () -> Unit = {
            setCredentialsUpdating(true, "Updating administrator credentials...")
            executor.execute {
                val ok = nextcloudManager.updateSuperAdminCredentials(
                    newUsername = user,
                    newPass = pass,
                    oldUsername = activeSavedUser,
                    onProgress = { stepMsg ->
                        runOnUiThread {
                            setCredentialsUpdating(true, stepMsg)
                        }
                    }
                )
                runOnUiThread {
                    setCredentialsUpdating(false)
                    if (ok.first) {
                        prefs.edit().putString("admin_user", user).putString("admin_pass", pass).apply()
                        adminUserInputLayout.error = null
                        adminPassInputLayout.error = null
                        val successMsg = if (user != activeSavedUser) {
                            "Administrator changed to '$user' and previous admin removed!"
                        } else {
                            "Password for administrator '$user' updated successfully!"
                        }
                        Toast.makeText(this@MainActivity, successMsg, Toast.LENGTH_LONG).show()
                    } else {
                        // Revert inputs so misleading credentials are not displayed
                        adminUserInput.setText(activeSavedUser)
                        adminPassInput.setText(activeSavedPass)
                        val errMsg = ok.second.ifEmpty { "Password was rejected by Nextcloud password policy" }
                        adminPassInputLayout.error = errMsg
                        createMaterialAlertDialog()
                            .setTitle("Could Not Update Administrator")
                            .setMessage("$errMsg\n\nThe credentials were not changed. Reverted to active credentials: '$activeSavedUser'.")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
        }

        if (user != activeSavedUser) {
            createMaterialAlertDialog()
                .setTitle("Change Server Administrator")
                .setMessage("Change the primary server administrator from '$activeSavedUser' to '$user'?\n\nExisting files will be transferred to '$user', and the previous administrator '$activeSavedUser' will be removed.")
                .setPositiveButton("Change Admin") { _, _ ->
                    executeCredentialChange()
                }
                .setNegativeButton("Cancel") { _, _ ->
                    adminUserInput.setText(activeSavedUser)
                    adminPassInput.setText(activeSavedPass)
                }
                .show()
        } else {
            executeCredentialChange()
        }
    }

    private fun setCredentialsUpdating(updating: Boolean, message: String = "") {
        if (updating) {
            credentialsProgressContainer.visibility = View.VISIBLE
            credentialsProgressStatusText.text = message
            updateCredentialsButton.isEnabled = false
            updateCredentialsButton.text = "Saving..."
            adminUserInput.isEnabled = false
            adminPassInput.isEnabled = false
        } else {
            credentialsProgressContainer.visibility = View.GONE
            updateCredentialsButton.isEnabled = true
            updateCredentialsButton.text = "Save Credentials"
            adminUserInput.isEnabled = true
            adminPassInput.isEnabled = true
        }
    }

    private fun showStorageSelectionDialog() {
        if (NextcloudServerService.isMigrationRunning) {
            Toast.makeText(this, "A storage operation is currently in progress. Please wait.", Toast.LENGTH_SHORT).show()
            return
        }

        val padH = (20 * resources.displayMetrics.density).toInt()
        val padV = (10 * resources.displayMetrics.density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, padV, padH, padV)
        }

        var dialog: androidx.appcompat.app.AlertDialog? = null

        // 1. Option: Migrate
        val migrateCard = androidx.cardview.widget.CardView(this).apply {
            radius = 12 * resources.displayMetrics.density
            cardElevation = 0f
            setCardBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.card_bg))
            isClickable = true
            isFocusable = true
            foreground = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.list_selector_background)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * resources.displayMetrics.density).toInt()
            }
        }
        val migrateContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (16 * resources.displayMetrics.density).toInt(),
                (14 * resources.displayMetrics.density).toInt(),
                (16 * resources.displayMetrics.density).toInt(),
                (14 * resources.displayMetrics.density).toInt()
            )
        }
        val migrateTitle = TextView(this).apply {
            text = "Migrate"
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.m3_primary))
        }
        val migrateSubtitle = TextView(this).apply {
            text = "Select any folder on internal storage and transfer all existing Nextcloud files, data, and uploads."
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.m3_on_surface_variant))
            setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        migrateContent.addView(migrateTitle)
        migrateContent.addView(migrateSubtitle)
        migrateCard.addView(migrateContent)

        migrateCard.setOnClickListener {
            dialog?.dismiss()
            pendingCopyData = true
            try {
                folderPickerLauncher.launch(null)
            } catch (e: Exception) {
                Toast.makeText(this, "Unable to launch folder picker: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        container.addView(migrateCard)

        // 2. Option: New
        val newCard = androidx.cardview.widget.CardView(this).apply {
            radius = 12 * resources.displayMetrics.density
            cardElevation = 0f
            setCardBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.card_bg))
            isClickable = true
            isFocusable = true
            foreground = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.list_selector_background)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val newContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (16 * resources.displayMetrics.density).toInt(),
                (14 * resources.displayMetrics.density).toInt(),
                (16 * resources.displayMetrics.density).toInt(),
                (14 * resources.displayMetrics.density).toInt()
            )
        }
        val newTitle = TextView(this).apply {
            text = "New"
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.m3_primary))
        }
        val newSubtitle = TextView(this).apply {
            text = "Select a new folder for a fresh installation. Password, user accounts, and configurations stay the same without copying previous files."
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.m3_on_surface_variant))
            setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        newContent.addView(newTitle)
        newContent.addView(newSubtitle)
        newCard.addView(newContent)

        newCard.setOnClickListener {
            dialog?.dismiss()
            pendingCopyData = false
            try {
                folderPickerLauncher.launch(null)
            } catch (e: Exception) {
                Toast.makeText(this, "Unable to launch folder picker: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        container.addView(newCard)

        dialog = createMaterialAlertDialog()
            .setTitle("Change Internal Storage")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showConnectedDrivesDialog() {
        executor.execute {
            val mountManager = StorageMountManager(this@MainActivity)
            val drives = mountManager.getRemovableStorages()
            val summary = mountManager.getActiveDrivesSummary()

            runOnUiThread {
                val padH = (20 * resources.displayMetrics.density).toInt()
                val padV = (12 * resources.displayMetrics.density).toInt()
                val container = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(padH, padV, padH, padV)
                }

                val titleText = TextView(this).apply {
                    text = if (drives.isEmpty()) {
                        "No external storage media currently detected.\n\nConnected MicroSD cards and USB OTG drives (FAT32, exFAT, NTFS, ext4) are automatically discovered and mounted into your Nextcloud files."
                    } else {
                        "Connected external drives are automatically mounted into your Nextcloud storage root.\n\nActive Mounted Volumes:"
                    }
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.m3_on_surface_variant))
                    setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
                }
                container.addView(titleText)

                val detailsText = TextView(this).apply {
                    text = summary
                    textSize = 12.5f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.m3_on_surface))
                    setBackgroundResource(R.drawable.pill_bg)
                    setPadding(
                        (12 * resources.displayMetrics.density).toInt(),
                        (10 * resources.displayMetrics.density).toInt(),
                        (12 * resources.displayMetrics.density).toInt(),
                        (10 * resources.displayMetrics.density).toInt()
                    )
                }
                container.addView(detailsText)

                createMaterialAlertDialog()
                    .setTitle("Connected Storage & Drives")
                    .setView(container)
                    .setPositiveButton("Close", null)
                    .setNeutralButton("Rescan Now") { _, _ ->
                        Toast.makeText(this, "Scanning for USB & SD drives...", Toast.LENGTH_SHORT).show()
                        executor.execute {
                            mountManager.syncConnectedStorages()
                            refreshStorageAndDomains()
                        }
                    }
                    .setNegativeButton("Change Main Storage") { _, _ ->
                        showStorageSelectionDialog()
                    }
                    .show()
            }
        }
    }

    private fun handleSelectedFolderUri(uri: Uri) {
        val resolvedPath = resolveFolderUri(uri)
        if (resolvedPath == null) {
            Toast.makeText(this, "Could not resolve folder path. Please select a local folder.", Toast.LENGTH_LONG).show()
            return
        }

        val currentPath = currentDataDirText.text.toString().trim()
        if (currentPath == resolvedPath) {
            Toast.makeText(this, "The selected folder is already the current storage folder.", Toast.LENGTH_SHORT).show()
            return
        }

        val title = if (pendingCopyData) "Confirm Storage Migration" else "Confirm New Storage Location"
        val message = if (pendingCopyData) {
            "Target Directory:\n$resolvedPath\n\nMigrate all existing Nextcloud files, media, and data to this folder?\n\nThe migration will run in the background."
        } else {
            "Target Directory:\n$resolvedPath\n\nInitialize this folder as a fresh Nextcloud internal storage location?\n\nThis acts as a fresh installation. Previous files remain untouched in the old folder. Your admin password, user accounts, and server configurations will stay unchanged."
        }
        val confirmBtn = if (pendingCopyData) "Migrate" else "Set New Storage"

        createMaterialAlertDialog()
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(confirmBtn) { _, _ ->
                startStorageMigrationService(resolvedPath, pendingCopyData)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startStorageMigrationService(targetPath: String, copyData: Boolean) {
        val currentPath = currentDataDirText.text.toString().trim()

        migrationProgressContainer.visibility = View.VISIBLE
        migrationStatusText.text = if (copyData) "Starting data migration..." else "Initializing fresh storage..."
        migrationProgressBar.isIndeterminate = true
        changeDataDirButton.isEnabled = false

        val intent = Intent(this, NextcloudServerService::class.java).apply {
            action = NextcloudServerService.ACTION_MIGRATE_STORAGE
            putExtra("old_path", currentPath)
            putExtra("new_path", targetPath)
            putExtra("copy_data", copyData)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun resolveFolderUri(uri: Uri): String? {
        try {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (e: Exception) {
                // Not all providers support persistable permissions
            }

            val docId = try {
                DocumentsContract.getTreeDocumentId(uri)
            } catch (e: Exception) {
                uri.path
            } ?: return null

            if (docId.startsWith("raw:")) {
                return docId.removePrefix("raw:")
            }

            val parts = docId.split(":")
            val type = parts[0]
            val subPath = if (parts.size > 1) parts[1] else ""

            if (type.equals("primary", ignoreCase = true)) {
                val base = Environment.getExternalStorageDirectory().absolutePath
                return if (subPath.isNotEmpty()) "$base/$subPath" else base
            }

            if (type.equals("downloads", ignoreCase = true)) {
                val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
                return if (subPath.isNotEmpty()) "$base/$subPath" else base
            }

            if (type.equals("home", ignoreCase = true)) {
                val base = File(Environment.getExternalStorageDirectory(), "Documents").absolutePath
                return if (subPath.isNotEmpty()) "$base/$subPath" else base
            }

            // Removable storage (MicroSD or USB OTG drive)
            val storageManager = getSystemService(Context.STORAGE_SERVICE) as StorageManager
            for (volume in storageManager.storageVolumes) {
                val volUuid = volume.uuid
                if (volUuid != null && volUuid.equals(type, ignoreCase = true)) {
                    var volPath: String? = null
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        volPath = volume.directory?.absolutePath
                    }
                    if (volPath == null) {
                        try {
                            val method = volume.javaClass.getMethod("getPath")
                            volPath = method.invoke(volume) as? String
                        } catch (e: Exception) {
                            // ignore
                        }
                    }
                    if (volPath == null) {
                        volPath = "/storage/$volUuid"
                    }
                    return if (subPath.isNotEmpty()) "$volPath/$subPath" else volPath
                }
            }

            // Fallback for paths with /tree/primary:, /tree/downloads, or direct /storage/
            val rawPath = Uri.decode(uri.toString())
            if (rawPath.contains("/tree/primary:")) {
                val rel = rawPath.substringAfter("/tree/primary:")
                return "${Environment.getExternalStorageDirectory().absolutePath}/$rel"
            }
            if (rawPath.contains("/tree/downloads")) {
                val rel = rawPath.substringAfter("/tree/downloads").removePrefix(":").removePrefix("/")
                val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
                return if (rel.isNotEmpty()) "$base/$rel" else base
            }
            if (rawPath.contains("/storage/")) {
                val idx = rawPath.indexOf("/storage/")
                return rawPath.substring(idx).substringBefore("?")
            }

            return "/storage/$type/$subPath".trimEnd('/')
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error resolving URI $uri", e)
            return null
        }
    }

    private fun showAddDomainDialog() {
        val container = FrameLayout(this).apply {
            val padH = (24 * resources.displayMetrics.density).toInt()
            val padV = (8 * resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
        }
        val textInputLayout = com.google.android.material.textfield.TextInputLayout(this).apply {
            hint = "Hostname or IP Address"
            helperText = "e.g. nextcloud.example.com or 192.168.1.50"
            boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        val input = TextInputEditText(textInputLayout.context).apply {
            setSingleLine()
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.m3_on_surface))
        }
        textInputLayout.addView(input)
        container.addView(textInputLayout)

        createMaterialAlertDialog()
            .setTitle("Add Trusted Domain")
            .setMessage("Specify a hostname or IP address permitted to access Nextcloud.")
            .setView(container)
            .setPositiveButton("Add Domain") { _, _ ->
                val domain = input.text.toString().trim()
                if (domain.isNotEmpty()) {
                    val cleanDomain = nextcloudManager.cleanDomainInput(domain)
                    executor.execute {
                        nextcloudManager.addTrustedDomain(cleanDomain)
                        refreshStorageAndDomains()
                    }
                    Toast.makeText(this, "Added $cleanDomain", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showManageDomainsDialog() {
        executor.execute {
            val domains = nextcloudManager.getTrustedDomains()
            runOnUiThread {
                if (domains.isEmpty()) {
                    Toast.makeText(this, "No trusted domains configured", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val items = domains.toTypedArray()
                createMaterialAlertDialog()
                    .setTitle("Trusted Domains")
                    .setItems(items) { _, which ->
                        val toRemove = items[which]
                        if (toRemove == "localhost" || toRemove == "127.0.0.1") {
                            Toast.makeText(this, "Cannot remove loopback domain '$toRemove'", Toast.LENGTH_SHORT).show()
                        } else {
                            createMaterialAlertDialog()
                                .setTitle("Remove Domain")
                                .setMessage("Remove '$toRemove' from trusted domains?")
                                .setPositiveButton("Remove") { _, _ ->
                                    executor.execute {
                                        nextcloudManager.removeTrustedDomain(toRemove)
                                        refreshStorageAndDomains()
                                    }
                                    Toast.makeText(this, "Removed $toRemove", Toast.LENGTH_SHORT).show()
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                    }
                    .setNegativeButton("Close", null)
                    .show()
            }
        }
    }

    private fun showLogsDialog() {
        executor.execute {
            val logsDir = File(filesDir, "logs")
            val fpmLog = File(logsDir, "php-fpm.log")
            val nginxLog = File(logsDir, "nginx_error.log")

            val fpmTail = if (fpmLog.exists()) fpmLog.readLines().takeLast(25).joinToString("\n") else "No PHP-FPM logs"
            val nginxTail = if (nginxLog.exists()) nginxLog.readLines().takeLast(25).joinToString("\n") else "No Nginx error logs"

            val combined = "--- PHP-FPM Log ---\n$fpmTail\n\n--- Nginx Error Log ---\n$nginxTail"

            runOnUiThread {
                val padH = (20 * resources.displayMetrics.density).toInt()
                val padV = (12 * resources.displayMetrics.density).toInt()
                val scrollView = ScrollView(this).apply {
                    setPadding(padH, padV, padH, padV)
                }
                val textView = TextView(this).apply {
                    text = combined
                    setTextIsSelectable(true)
                    textSize = 12f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.m3_on_surface))
                }
                scrollView.addView(textView)

                createMaterialAlertDialog()
                    .setTitle("Server Logs")
                    .setView(scrollView)
                    .setPositiveButton("Close", null)
                    .setNeutralButton("Copy Logs") { _, _ ->
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Nextcloud Server Logs", combined)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("com.nextcloud.server.STATUS_UPDATE")
        val storageFilter = IntentFilter(StorageMountManager.ACTION_STORAGE_CHANGED)
        val migrationFilter = IntentFilter(NextcloudServerService.ACTION_MIGRATION_PROGRESS)
        val portFilter = IntentFilter(NextcloudServerService.ACTION_PORT_PROGRESS)
        val setupFilter = IntentFilter(NextcloudServerService.ACTION_SETUP_PROGRESS)

        val mediaFilter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        val usbFilter = IntentFilter().apply {
            addAction(StorageMountManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(StorageMountManager.ACTION_USB_DEVICE_DETACHED)
            addAction("android.os.storage.action.VOLUME_STATE_CHANGED")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
            registerReceiver(storageChangeReceiver, storageFilter, RECEIVER_NOT_EXPORTED)
            registerReceiver(migrationReceiver, migrationFilter, RECEIVER_NOT_EXPORTED)
            registerReceiver(portProgressReceiver, portFilter, RECEIVER_NOT_EXPORTED)
            registerReceiver(setupReceiver, setupFilter, RECEIVER_NOT_EXPORTED)
            registerReceiver(hardwareStorageReceiver, mediaFilter, RECEIVER_EXPORTED)
            registerReceiver(hardwareStorageReceiver, usbFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
            registerReceiver(storageChangeReceiver, storageFilter)
            registerReceiver(migrationReceiver, migrationFilter)
            registerReceiver(portProgressReceiver, portFilter)
            registerReceiver(setupReceiver, setupFilter)
            registerReceiver(hardwareStorageReceiver, mediaFilter)
            registerReceiver(hardwareStorageReceiver, usbFilter)
        }

        NextcloudServerService.setupProgressListener = setupProgressListener

        if (NextcloudServerService.isSetupRunning) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setupProgressContainer.visibility = View.VISIBLE
            val stage = NextcloudServerService.lastSetupStage
            val percent = NextcloudServerService.lastSetupPercent
            setupStatusText.text = stage
            setupPercentText.text = "$percent%"
            setupProgressBar.isIndeterminate = false
            setupProgressBar.progress = percent
            serverActionButton.isEnabled = false
            if (stage.contains("extract", ignoreCase = true)) {
                serverActionButton.text = "Extracting Nextcloud..."
            } else if (stage.contains("database", ignoreCase = true) || stage.contains("sqlite", ignoreCase = true)) {
                serverActionButton.text = "Initializing Database..."
            } else if (stage.contains("app", ignoreCase = true) || stage.contains("domain", ignoreCase = true) || stage.contains("policy", ignoreCase = true)) {
                serverActionButton.text = "Configuring Nextcloud..."
            } else {
                serverActionButton.text = "Starting Server..."
            }
            serverActionButton.setIconResource(R.drawable.ic_play_arrow)
            serverActionButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.m3_primary)
            serverActionButton.setTextColor(ContextCompat.getColor(this, R.color.m3_on_primary))
            serverActionButton.iconTint = ContextCompat.getColorStateList(this, R.color.m3_on_primary)
        } else {
            if (!NextcloudServerService.isMigrationRunning) {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            setupProgressContainer.visibility = View.GONE
        }

        if (NextcloudServerService.isMigrationRunning) {
            migrationProgressContainer.visibility = View.VISIBLE
            val stage = NextcloudServerService.lastMigrationStage
            val percent = NextcloudServerService.lastMigrationPercent
            migrationStatusText.text = if (percent > 0) "$stage ($percent%)" else stage.ifEmpty { "Configuring storage..." }
            if (percent > 0) {
                migrationProgressBar.isIndeterminate = false
                migrationProgressBar.progress = percent
            } else {
                migrationProgressBar.isIndeterminate = true
            }
            changeDataDirButton.isEnabled = false
        } else {
            migrationProgressContainer.visibility = View.GONE
            changeDataDirButton.isEnabled = true
        }

        if (NextcloudServerService.isPortChangeRunning) {
            portProgressContainer.visibility = View.VISIBLE
            val stage = NextcloudServerService.lastPortChangeStatus
            val percent = NextcloudServerService.lastPortChangePercent
            portProgressStatusText.text = if (percent > 0) "$stage ($percent%)" else stage
            if (percent > 0) {
                portProgressBar.isIndeterminate = false
                portProgressBar.progress = percent
            } else {
                portProgressBar.isIndeterminate = true
            }
            applyPortButton.isEnabled = false
            portInput.isEnabled = false
        } else {
            portProgressContainer.visibility = View.GONE
            applyPortButton.isEnabled = true
            portInput.isEnabled = true
        }

        if (NextcloudServerService.isSetupRunning) {
            // Already handled above, keep setup UI active
        } else if (NextcloudServerService.isServerRunning) {
            handleStatusUpdate(NextcloudServerService.lastStatus)
        } else {
            handleStatusUpdate("Stopped")
        }

        refreshStorageAndDomains()
        updatePermissionAndBackgroundToggles()
    }

    override fun onPause() {
        super.onPause()
        NextcloudServerService.setupProgressListener = null
        try {
            unregisterReceiver(statusReceiver)
            unregisterReceiver(storageChangeReceiver)
            unregisterReceiver(migrationReceiver)
            unregisterReceiver(portProgressReceiver)
            unregisterReceiver(setupReceiver)
            unregisterReceiver(hardwareStorageReceiver)
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun handleApplyPort(explicitPort: Int? = null) {
        val portStr = if (explicitPort != null) explicitPort.toString() else portInput.text.toString().trim()
        val port = portStr.toIntOrNull()
        if (port == null || port < 1024 || port > 65535) {
            Toast.makeText(this, "Port must be between 1024 and 65535 (ports below 1024 require root)", Toast.LENGTH_LONG).show()
            return
        }

        val currentPort = prefs.getInt("server_port", 8000)
        if (isServerRunning && port == currentPort) {
            Toast.makeText(this, "Server is already running on port $port", Toast.LENGTH_SHORT).show()
            return
        }

        if (port != currentPort && isPortInUse(port)) {
            Toast.makeText(this, "Port $port is in use by another app on this phone. Please choose another port.", Toast.LENGTH_LONG).show()
            return
        }

        // Dismiss keyboard
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(portInput.windowToken, 0)

        if (!isServerRunning) {
            prefs.edit().putInt("server_port", port).apply()
            portInput.setText(port.toString())
            Toast.makeText(this, "Port updated to $port. It will be used on next start.", Toast.LENGTH_SHORT).show()
            return
        }

        // Live dynamic port change while server is running!
        portProgressContainer.visibility = View.VISIBLE
        portProgressStatusText.text = "Switching server to port $port..."
        portProgressBar.isIndeterminate = true
        applyPortButton.isEnabled = false
        portInput.isEnabled = false

        val intent = Intent(this, NextcloudServerService::class.java).apply {
            action = NextcloudServerService.ACTION_CHANGE_PORT
            putExtra("port", port)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun showChangePortDialog() {
        val currentPort = prefs.getInt("server_port", 8000)
        val container = FrameLayout(this).apply {
            val padH = (24 * resources.displayMetrics.density).toInt()
            val padV = (8 * resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
        }
        val textInputLayout = com.google.android.material.textfield.TextInputLayout(this).apply {
            hint = "Port Number"
            helperText = "Default: 8000 (Range 1024–65535)"
            boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        val input = TextInputEditText(textInputLayout.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(currentPort.toString())
            setSelection(text?.length ?: 0)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.m3_on_surface))
        }
        textInputLayout.addView(input)
        container.addView(textInputLayout)

        val message = if (isServerRunning) {
            "Server is currently running.\nChanging port will dynamically reconfigure and reload the web server with live progress."
        } else {
            "Enter new port (1024–65535). It will be saved and used when starting the server."
        }

        createMaterialAlertDialog()
            .setTitle("Change Server Port")
            .setMessage(message)
            .setView(container)
            .setPositiveButton("Apply") { _, _ ->
                val port = input.text.toString().trim().toIntOrNull()
                if (port != null) {
                    portInput.setText(port.toString())
                    handleApplyPort(port)
                } else {
                    Toast.makeText(this, "Invalid port number", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun isPortInUse(port: Int): Boolean {
        return try {
            val future = java.util.concurrent.Executors.newSingleThreadExecutor().submit<Boolean> {
                try {
                    java.net.ServerSocket(port).use { false }
                } catch (e: Exception) {
                    true
                }
            }
            future.get(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (activeSafDialog?.isShowing == true) {
                activeSafDialog?.dismiss()
            }
        } catch (_: Exception) {}
        activeSafDialog = null
    }
}
