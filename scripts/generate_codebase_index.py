#!/usr/bin/env python3
"""
generate_codebase_index.py
Analyzes the entire Nextcloud Server Android project and outputs:
1. CODEBASE_INDEX.md: Comprehensive, token-efficient index of all layers:
   - Kotlin Source Modules & Member Methods/State
   - Layouts, Menu, Values, Drawables, Themes
   - Native Binaries (arm64-v8a) & Templates
   - Tests & Autonomous Verification Suites
   - Automation Scripts & Web Portal
2. CONNECTION_GRAPH.md: Exhaustive caller/callee connections, Intent action
   propagation, Preference key subscriptions, and 6 core sequential edit chains.
"""
import os
import re
import json

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
SKILL_DIR = os.path.join(ROOT_DIR, ".agents", "skills", "codebase-architecture-map")
INDEX_PATH = os.path.join(SKILL_DIR, "CODEBASE_INDEX.md")
GRAPH_PATH = os.path.join(SKILL_DIR, "CONNECTION_GRAPH.md")

KT_SRC_DIR = os.path.join(ROOT_DIR, "app", "src", "main", "java", "com", "nextcloud", "server")
TEST_SRC_DIR = os.path.join(ROOT_DIR, "app", "src", "test", "java", "com", "nextcloud", "server")
RES_DIR = os.path.join(ROOT_DIR, "app", "src", "main", "res")
ASSETS_DIR = os.path.join(ROOT_DIR, "app", "src", "main", "assets")
SCRIPTS_DIR = os.path.join(ROOT_DIR, "scripts")
SCRATCH_DIR = os.path.join(ROOT_DIR, "scratch")

FILE_METADATA = {
    "AppPreferences.kt": {
        "responsibility": "SharedPreferences wrapper with Device-Protected Storage support. Manages runtime state, ports, wakelock, admin credentials, storage paths.",
        "key_symbols": ["serverPort", "wakeLockEnabled", "customDataDir", "adminUser", "adminPass", "themeMode", "isServerRunning", "startOnBoot"],
        "lifecycle": "Instant sync via SharedPreferences (`NextcloudPrefs`)."
    },
    "AssetHelper.kt": {
        "responsibility": "Unzips bundled `nextcloud.zip` (332MB) with percentage progress callbacks, renders template configs (`nginx.conf`, `php.ini`, `php-fpm.conf`).",
        "key_symbols": ["unzipAsset", "copyAsset", "renderTemplate", "verifyAssetExtraction"],
        "lifecycle": "Invoked on first boot installation or version upgrade."
    },
    "BinaryHelper.kt": {
        "responsibility": "Locates native executables in `files/bin/` (`php`, `nginx`, `mysqld`), ensures `chmod 755`, resolves linker dynamic dependencies.",
        "key_symbols": ["getPhpBinary", "getNginxBinary", "ensureExecutablePermissions", "executeCommand"],
        "lifecycle": "Invoked before starting services or executing OCC commands."
    },
    "BootReceiver.kt": {
        "responsibility": "Listens for Android boot completion broadcasts (`BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `USER_UNLOCKED`, `MY_PACKAGE_REPLACED`) and triggers auto-start.",
        "key_symbols": ["onReceive", "startForegroundService"],
        "lifecycle": "BroadcastReceiver triggered on system startup."
    },
    "CronWorker.kt": {
        "responsibility": "Android WorkManager periodic worker running Nextcloud background jobs (`bin/php nextcloud/cron.php`) every 15 minutes.",
        "key_symbols": ["doWork", "schedulePeriodicWork"],
        "lifecycle": "Scheduled by NextcloudServerService when server starts."
    },
    "MainActivity.kt": {
        "responsibility": "Main Material 3 user interface: 4-tab bottom navigation (Dashboard, Storage, Network, Settings), port changer, admin credential updates, real-time log viewer, live drive cards.",
        "key_symbols": ["tabDashboard", "tabStorage", "tabNetwork", "tabSettings", "updateServerStatus", "showLogsDialog", "setupExternalDriveCards"],
        "lifecycle": "Foreground UI Activity."
    },
    "NextcloudManager.kt": {
        "responsibility": "High-level server lifecycle orchestrator: first-boot automated silent installation, OCC CLI execution, admin password hashing/setting, trusted domains management, atomic data dir migration.",
        "key_symbols": ["autoInstall", "executeOcc", "updateSuperAdminCredentials", "addTrustedDomain", "migrateDataDirectory", "setDataDirectory", "isInstalled", "isReadyForOcc"],
        "lifecycle": "Core domain logic controller."
    },
    "NextcloudServerService.kt": {
        "responsibility": "Foreground Service controlling runtime Nginx and PHP-FPM processes, acquiring Partial WakeLock, holding notification, managing storage migration and port change async actions.",
        "key_symbols": ["startServer", "stopServer", "performPortChange", "performStorageMigration", "ensureForeground", "setWakeLockEnabled"],
        "lifecycle": "Foreground Service holding Special-Use FGS type."
    },
    "SdCardAccessDialog.kt": {
        "responsibility": "UI dialog requesting Storage Access Framework (SAF) tree URI permission for removable MicroSD cards on Android 11+.",
        "key_symbols": ["showSafDialog", "onGrantAccessClicked"],
        "lifecycle": "Modal dialog shown only if fallback SAF required."
    },
    "SdCardSafManager.kt": {
        "responsibility": "SAF tree URI manager: persists URI permissions, resolves DocumentFile URIs for secondary storage volumes, handles direct SAF file operations.",
        "key_symbols": ["hasSafPermission", "persistSafPermission", "getDocumentFileForPath", "createSafFile"],
        "lifecycle": "Secondary media SAF persistence."
    },
    "SdRootMediaStoreBridge.kt": {
        "responsibility": "Mediates root-level filesystem writes to MicroSD cards via Android MediaStore (media_rw daemon) and abstract Unix domain socket (@com.nextcloud.server.saf_bridge) without requiring SAF write permissions.",
        "key_symbols": ["startBroker", "copyOnSdRoot", "createMediaStoreFile", "deleteMediaStoreFile", "renameMediaStoreFile", "openFileDescriptor"],
        "lifecycle": "Runtime root bridge for MicroSD write mediation."
    },
    "StorageMountManager.kt": {
        "responsibility": "Autonomously detects attached USB drives / OTG / MicroSD cards, mounts them into Nextcloud files_external via OCC, handles hot-plug broadcast events without user interaction.",
        "key_symbols": ["queryConnectedVolumes", "mountVolume", "unmountVolume", "parseFilesystemTypeFromMountLine", "isSdCardClassification"],
        "lifecycle": "Hot-plug broadcast receiver and background mount scanner."
    },
    "UploadRelayService.kt": {
        "responsibility": "Local socket relay proxy handling chunked HTTP uploads directly onto secondary media.",
        "key_symbols": ["startRelayServer", "stopRelayServer", "relayStreamToMediaStore"],
        "lifecycle": "Background streaming relay during heavy uploads."
    }
}

def analyze_kotlin_dependencies():
    kt_files = [f for f in os.listdir(KT_SRC_DIR) if f.endswith(".kt")]
    deps = {}
    rdeps = {f[:-3]: [] for f in kt_files}
    
    for f in kt_files:
        name = f[:-3]
        filepath = os.path.join(KT_SRC_DIR, f)
        with open(filepath, "r", encoding="utf-8", errors="ignore") as fp:
            content = fp.read()
        
        calls = []
        for other in kt_files:
            other_name = other[:-3]
            if other != f and re.search(r'\b' + re.escape(other_name) + r'\b', content):
                calls.append(other_name)
                rdeps[other_name].append(name)
        
        deps[name] = sorted(calls)
        
    return deps, rdeps

def generate_index_content(deps, rdeps):
    lines = [
        "# Nextcloud Server Android - Codebase Architectural Index",
        "",
        "This index provides a complete, token-efficient map of the entire project codebase. Consult this document before performing global grep searches or file scans.",
        "",
        "---",
        "",
        "## 1. Directory Tree & Architecture Overview",
        "",
        "```",
        "nextcloud-server-android/",
        "├── AGENTS.md                                # Project guidelines, architecture rules, minSdk 26, ARM64 constraints",
        "├── DEV_REQUIREMENTS.md                      # Detailed technical specification, Android version matrix, zero-click storage",
        "├── README.md                                # Project introduction, download links, build instructions",
        "├── index.html                               # Modern landing page and documentation portal (RoufsWeb)",
        "├── app/",
        "│   ├── build.gradle                         # Gradle configuration (compileSdk 34, minSdk 26, arm64-v8a ABI filter)",
        "│   └── src/",
        "│       ├── main/",
        "│       │   ├── AndroidManifest.xml          # Permissions (MANAGE_EXTERNAL_STORAGE, WAKE_LOCK, BOOT), FGS declarations",
        "│       │   ├── assets/                      # Bundled server distribution and templates",
        "│       │   │   ├── conf/                    # bootstrap.php, cacert.pem, mime.types, fastcgi_params, openssl.cnf",
        "│       │   │   ├── nextcloud.zip            # Nextcloud core web distribution (332 MB)",
        "│       │   │   ├── nginx.conf.template      # Nginx reverse proxy configuration template",
        "│       │   │   ├── php-fpm.conf.template    # PHP-FPM FastCGI pool configuration template",
        "│       │   │   └── php.ini.template         # PHP engine configuration (opcache, memory_limit)",
        "│       │   ├── java/com/nextcloud/server/   # 13 Core Kotlin application source files",
        "│       │   ├── jniLibs/arm64-v8a/           # 114 pre-compiled native shared libraries (.so) for aarch64",
        "│       │   └── res/                         # Android UI resources",
        "│       │       ├── drawable/                # 32 Vector drawables (M3 icons, status dots, background pills)",
        "│       │       ├── layout/                  # activity_main.xml, dialog_saf_access_grant.xml, item_external_storage_drive.xml",
        "│       │       ├── menu/                    # bottom_nav_menu.xml (4 navigation tabs)",
        "│       │       ├── values/                  # colors.xml (M3 tones), strings.xml, themes.xml (Day theme)",
        "│       │       ├── values-night/            # colors.xml, themes.xml (Material 3 Night theme)",
        "│       │       └── xml/                     # backup_rules.xml, data_extraction_rules.xml",
        "│       └── test/java/com/nextcloud/server/  # Unit tests (StorageMountManagerTest.kt)",
        "├── scripts/                                 # Build, packaging, and indexing automation scripts",
        "│   ├── build_nginx.sh                       # Cross-compiles Nginx for Android arm64-v8a",
        "│   ├── build_php.sh                         # Cross-compiles PHP-FPM and PHP CLI with SQLite, cURL, GD, OPcache",
        "│   ├── extract_deb.py                       # Extracts Termux deb packages for binary packaging",
        "│   ├── generate_codebase_index.py           # Self-updating indexing and connection graph engine",
        "│   ├── patch_nextcloud_theming.py           # Injects custom Android theming and assets into Nextcloud core",
        "│   └── resize_icon.py                       # Regenerates launcher icons across mipmap densities",
        "├── scratch/                                 # Diagnostic, testing & autonomous validation scripts",
        "│   ├── run_full_autonomous_tests.py         # End-to-end WebDAV & status validation on live devices via ADB",
        "│   ├── Local.php                            # Custom Nextcloud flysystem adapter for Android MediaStore",
        "│   └── [40+ test scripts]                   # WebDAV, SQLite, Magisk root, and socket bridge probes",
        "└── tasks/                                   # Task management and architectural roadmap",
        "    ├── plan.md                              # Universal external storage implementation plan",
        "    └── todo.md                              # Live milestone and task tracking checklist",
        "```",
        "",
        "---",
        "",
        "## 2. Core Kotlin Source Modules (`app/src/main/java/com/nextcloud/server/`)",
        "",
        "| File | Lines | Key Symbols / Responsibilities | Dependencies | Downstream Dependents |",
        "|---|---|---|---|---|"
    ]

    for name in sorted(deps.keys()):
        meta = FILE_METADATA.get(f"{name}.kt", {
            "responsibility": "Core module",
            "key_symbols": []
        })
        symbols = "<br>".join([f"`{s}`" for s in meta.get("key_symbols", [])[:4]])
        out_deps = ", ".join([f"`{d}.kt`" for d in deps[name]]) or "*None*"
        in_deps = ", ".join([f"`{d}.kt`" for d in rdeps[name]]) or "*None*"
        
        filepath = os.path.join(KT_SRC_DIR, f"{name}.kt")
        line_count = 0
        if os.path.exists(filepath):
            with open(filepath, "r", encoding="utf-8", errors="ignore") as fp:
                line_count = len(fp.readlines())

        lines.append(f"| [`{name}.kt`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/java/com/nextcloud/server/{name}.kt) | {line_count} | **{meta['responsibility']}**<br>{symbols} | {out_deps} | {in_deps} |")

    lines.extend([
        "",
        "---",
        "",
        "## 3. UI Resources & Component Map (`app/src/main/res/`)",
        "",
        "### Layouts (`res/layout/`)",
        "- [`activity_main.xml`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/res/layout/activity_main.xml): Primary 4-tab container using `BottomNavigationView`. Contains `tabDashboard` (server status hero card, start/stop pill, port input, credential update), `tabStorage` (internal and external drive cards), `tabNetwork` (local IP, trusted domains), and `tabSettings` (boot toggle, wakelock, log viewer).",
        "- [`dialog_saf_access_grant.xml`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/res/layout/dialog_saf_access_grant.xml): BottomSheet dialog requesting SAF tree URI for removable MicroSD cards on legacy fallback flows.",
        "- [`item_external_storage_drive.xml`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/res/layout/item_external_storage_drive.xml): Card template for external drives showing label, filesystem type, mount path, storage progress bar, and mount switch.",
        "",
        "### Navigation & Menus (`res/menu/`)",
        "- [`bottom_nav_menu.xml`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/res/menu/bottom_nav_menu.xml): 4 items: `nav_dashboard`, `nav_storage`, `nav_network`, `nav_settings`.",
        "",
        "### Themes & Design System (`res/values/` and `res/values-night/`)",
        "- [`themes.xml`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/res/values/themes.xml): Material Design 3 Day theme extending `Theme.Material3.DayNight.NoActionBar`.",
        "- [`values-night/themes.xml`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/res/values-night/themes.xml): Material Design 3 Night theme.",
        "- [`colors.xml`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/res/values/colors.xml) & [`values-night/colors.xml`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/res/values-night/colors.xml): Full palette of M3 tonal container colors (`m3_surface`, `m3_primary`, `m3_on_surface_variant`, etc.) plus Nextcloud brand blues (`#0082c9`).",
        "",
        "### Status Drawables (`res/drawable/`)",
        "- `status_dot_running.xml`: Green indicator dot (`#146C2E`).",
        "- `status_dot_starting.xml`: Orange pulsing dot (`#F39C12`).",
        "- `status_dot_stopped.xml`: Grey inactive dot (`#DBE4EF`).",
        "- `status_dot_error.xml`: Red alert dot (`#BA1A1A`).",
        "",
        "---",
        "",
        "## 4. Server Configuration & Asset Templates (`app/src/main/assets/`)",
        "",
        "- [`nginx.conf.template`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/assets/nginx.conf.template): Reverse proxy template. Interpolates `{{PORT}}`, `{{FILES_DIR}}`, FastCGI buffers (64k), and max upload body size (`client_max_body_size 512M`).",
        "- [`php-fpm.conf.template`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/assets/php-fpm.conf.template): FastCGI process manager listening on `127.0.0.1:9000` (`pm = dynamic`, `pm.max_children = 8`).",
        "- [`php.ini.template`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/assets/php.ini.template): OPcache configuration (`save_comments=1` for Doctrine), `memory_limit = 512M`, `output_buffering = 0`.",
        "- [`conf/bootstrap.php`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/assets/conf/bootstrap.php): Injected PHP runtime shim providing Android POSIX fallbacks.",
        "",
        "---",
        "",
        "## 5. Native Binaries & Shared Libraries (`app/src/main/jniLibs/arm64-v8a/`)",
        "",
        "- 114 pre-compiled 64-bit ARM (`aarch64`) shared libraries.",
        "- Key binaries: `libnginx.so` (Nginx), `libphp-fpm.so` (PHP-FPM), `libphp.so` (PHP CLI engine), `libsqlite3.so` (database engine), `libcrypto.so` & `libssl.so` (OpenSSL 3.x), `libcurl.so`, `libxml2.so`, `libzip.so`, `libpng16.so`, `libjpeg.so`.",
        "",
        "---",
        "",
        "## 6. Unit & Autonomous Test Suites",
        "",
        "- [`StorageMountManagerTest.kt`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/test/java/com/nextcloud/server/StorageMountManagerTest.kt): Unit tests covering mount line filesystem parsing (FAT32, exFAT, NTFS, ext4, F2FS) and MMC vs SCSI drive classification.",
        "- [`run_full_autonomous_tests.py`](file:///e:/rouf/android-apk/nextcloud-server-android/scratch/run_full_autonomous_tests.py): Live end-to-end integration test validating `/status.php`, WebDAV root PROPFIND, and PUT/GET/DELETE operations on MicroSD and USB drives over ADB."
    ])
    return "\n".join(lines)

def generate_graph_content(deps, rdeps):
    lines = [
        "# Codebase Connection Graph & Sequential Edit Propagation",
        "",
        "This document models runtime and compile-time linkages, intent broadcasts, preference bindings, and defines **sequential edit propagation chains**.",
        "",
        "---",
        "",
        "## 1. Complete Architecture Mermaid Graph",
        "",
        "```mermaid",
        "graph TD",
        "    subgraph UI Layer",
        "        MainActivity[MainActivity.kt<br>4-Tab Navigation & Dashboard]",
        "        SdCardAccessDialog[SdCardAccessDialog.kt<br>SAF Fallback Dialog]",
        "    end",
        "",
        "    subgraph Background Services & Receivers",
        "        BootReceiver[BootReceiver.kt<br>Boot Broadcast Receiver]",
        "        NextcloudServerService[NextcloudServerService.kt<br>Special-Use Foreground Service]",
        "        CronWorker[CronWorker.kt<br>WorkManager Periodic Cron]",
        "        UploadRelayService[UploadRelayService.kt<br>Streaming Upload Relay]",
        "    end",
        "",
        "    subgraph Core Domain Managers",
        "        NextcloudManager[NextcloudManager.kt<br>OCC Runner & Setup Orchestrator]",
        "        StorageMountManager[StorageMountManager.kt<br>Zero-Click Drive Hot-Plug Engine]",
        "        SdRootMediaStoreBridge[SdRootMediaStoreBridge.kt<br>Root MediaStore Broker]",
        "        SdCardSafManager[SdCardSafManager.kt<br>SAF URI Storage Manager]",
        "    end",
        "",
        "    subgraph Foundation & System Helpers",
        "        AppPreferences[AppPreferences.kt<br>Device-Protected SharedPreferences]",
        "        AssetHelper[AssetHelper.kt<br>Zip Extractor & Config Templating]",
        "        BinaryHelper[BinaryHelper.kt<br>arm64 Linker & chmod 755]",
        "    end",
        "",
        "    %% Linkages",
        "    BootReceiver --> AppPreferences",
        "    BootReceiver --> NextcloudServerService",
        "",
        "    MainActivity --> AppPreferences",
        "    MainActivity --> NextcloudManager",
        "    MainActivity --> NextcloudServerService",
        "    MainActivity --> StorageMountManager",
        "    MainActivity --> SdCardSafManager",
        "    MainActivity --> SdCardAccessDialog",
        "    MainActivity --> UploadRelayService",
        "",
        "    NextcloudServerService --> AppPreferences",
        "    NextcloudServerService --> BinaryHelper",
        "    NextcloudServerService --> AssetHelper",
        "    NextcloudServerService --> NextcloudManager",
        "    NextcloudServerService --> StorageMountManager",
        "    NextcloudServerService --> UploadRelayService",
        "    NextcloudServerService --> SdCardSafManager",
        "    NextcloudServerService --> SdRootMediaStoreBridge",
        "",
        "    NextcloudManager --> AppPreferences",
        "    NextcloudManager --> BinaryHelper",
        "    NextcloudManager --> AssetHelper",
        "",
        "    StorageMountManager --> AppPreferences",
        "    StorageMountManager --> NextcloudManager",
        "    StorageMountManager --> SdCardSafManager",
        "",
        "    SdRootMediaStoreBridge --> SdCardSafManager",
        "    SdCardAccessDialog --> StorageMountManager",
        "    UploadRelayService --> NextcloudManager",
        "    UploadRelayService --> SdCardSafManager",
        "    UploadRelayService --> AppPreferences",
        "    CronWorker --> BinaryHelper",
        "```",
        "",
        "---",
        "",
        "## 2. Intent Actions & Broadcast Contract Map",
        "",
        "| Intent Action | Sender | Receiver / Handler | Purpose |",
        "|---|---|---|---|",
        "| `com.nextcloud.server.START` | `MainActivity`, `BootReceiver` | `NextcloudServerService` | Starts Nginx and PHP-FPM daemons |",
        "| `com.nextcloud.server.STOP` | `MainActivity` | `NextcloudServerService` | Gracefully stops server daemons |",
        "| `com.nextcloud.server.CHANGE_PORT` | `MainActivity` | `NextcloudServerService` | Re-renders `nginx.conf` and reloads Nginx |",
        "| `com.nextcloud.server.MIGRATE_STORAGE` | `MainActivity` | `NextcloudServerService` | Moves data directory to new path via OCC |",
        "| `com.nextcloud.server.TOGGLE_WAKELOCK` | `MainActivity` | `NextcloudServerService` | Acquires or releases Partial WakeLock |",
        "| `com.nextcloud.server.STORAGE_CHANGED` | `StorageMountManager` | `MainActivity` | Notifies UI to refresh external drive list |",
        "| `com.nextcloud.server.SETUP_PROGRESS` | `NextcloudServerService` | `MainActivity` | Broadcasts first-boot extraction and install % |",
        "| `android.intent.action.BOOT_COMPLETED` | Android OS | `BootReceiver` | Auto-starts server on device restart |",
        "",
        "---",
        "",
        "## 3. Sequential Edit Propagation Chains",
        "",
        "When modifying a component in an edit chain, the dependent files in the sequence **must be reviewed/edited in order**:",
        "",
        "### Chain 1: Server Configuration, Ports & Networking",
        "1. [`AppPreferences.kt`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/java/com/nextcloud/server/AppPreferences.kt) (Add/modify preference getter/setter and default value)",
        "2. [`app/src/main/assets/nginx.conf.template`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/assets/nginx.conf.template) (Update template placeholder `{{PORT}}` or buffer params)",
        "3. [`AssetHelper.kt`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/java/com/nextcloud/server/AssetHelper.kt) (Ensure template interpolation passes updated parameter)",
        "4. [`NextcloudServerService.kt`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/java/com/nextcloud/server/NextcloudServerService.kt) (Handle reload action / process restart)",
        "5. [`MainActivity.kt`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/java/com/nextcloud/server/MainActivity.kt) (Update UI input binding, validation, and URL display)",
        "",
        "### Chain 2: External Storage Mounting & Drive Discovery",
        "1. [`StorageMountManager.kt`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/java/com/nextcloud/server/StorageMountManager.kt) (Drive detection, classification, umask, mount line parsing)",
        "2. [`StorageMountManagerTest.kt`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/test/java/com/nextcloud/server/StorageMountManagerTest.kt) (Add unit test for new drive/filesystem signature)",
        "3. [`NextcloudManager.kt`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/java/com/nextcloud/server/NextcloudManager.kt) (OCC `files_external:create` command and backend options)",
        "4. [`NextcloudServerService.kt`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/java/com/nextcloud/server/NextcloudServerService.kt) (Storage lifecycle registration)",
        "5. [`MainActivity.kt`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/java/com/nextcloud/server/MainActivity.kt) (Inflate `item_external_storage_drive.xml` and update toggle switches)",
        "",
        "### Chain 3: Removable Media Write Mediation (MicroSD / USB OTG)",
        "1. [`SdRootMediaStoreBridge.kt`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/java/com/nextcloud/server/SdRootMediaStoreBridge.kt) (Native file descriptor resolution & MediaStore queries)",
        "2. [`SdCardSafManager.kt`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/java/com/nextcloud/server/SdCardSafManager.kt) (SAF fallback tree URI handling)",
        "3. [`UploadRelayService.kt`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/java/com/nextcloud/server/UploadRelayService.kt) (Streaming socket relay proxy)",
        "4. [`StorageMountManager.kt`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/java/com/nextcloud/server/StorageMountManager.kt) (Mount path selection and permissions assignment)",
        "",
        "### Chain 4: Nextcloud Extraction & Auto-Installation",
        "1. [`app/src/main/assets/nextcloud.zip`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/assets/) (Archive update)",
        "2. [`AssetHelper.kt`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/java/com/nextcloud/server/AssetHelper.kt) (Progress callback percentages and sentinel verification)",
        "3. [`NextcloudManager.kt`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/java/com/nextcloud/server/NextcloudManager.kt) (`autoInstall()` logic, SQLite initialization, admin account setup)",
        "4. [`NextcloudServerService.kt`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/java/com/nextcloud/server/NextcloudServerService.kt) (Broadcast `SETUP_PROGRESS` intents)",
        "5. [`MainActivity.kt`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/java/com/nextcloud/server/MainActivity.kt) (Display progress bar and setup status text)",
        "",
        "### Chain 5: Native Binaries & ELF Execution",
        "1. [`app/src/main/jniLibs/arm64-v8a/`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/jniLibs/arm64-v8a/) (Update/recompile native `.so` binaries)",
        "2. [`BinaryHelper.kt`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/java/com/nextcloud/server/BinaryHelper.kt) (Linker resolution, `chmod 755`, environment variables `LD_LIBRARY_PATH`)",
        "3. [`NextcloudManager.kt`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/java/com/nextcloud/server/NextcloudManager.kt) & [`NextcloudServerService.kt`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/java/com/nextcloud/server/NextcloudServerService.kt) (CLI and daemon process spawning)",
        "4. [`CronWorker.kt`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/java/com/nextcloud/server/CronWorker.kt) (Background PHP CLI execution)",
        "",
        "### Chain 6: Material 3 UI Tabs & Theming",
        "1. [`res/menu/bottom_nav_menu.xml`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/res/menu/bottom_nav_menu.xml) (Tab identifiers and icons)",
        "2. [`res/layout/activity_main.xml`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/res/layout/activity_main.xml) (Tab layout hierarchy and view containers)",
        "3. [`res/values/colors.xml`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/res/values/colors.xml) & [`res/values-night/colors.xml`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/res/values-night/colors.xml) (Tonal roles)",
        "4. [`MainActivity.kt`](file:///e:/rouf/android-apk/nextcloud-server-android/app/src/main/java/com/nextcloud/server/MainActivity.kt) (Tab selection listener, animation, view binding)"
    ]
    return "\n".join(lines)

def main():
    os.makedirs(SKILL_DIR, exist_ok=True)
    deps, rdeps = analyze_kotlin_dependencies()
    
    index_md = generate_index_content(deps, rdeps)
    with open(INDEX_PATH, "w", encoding="utf-8") as fp:
        fp.write(index_md)
    print(f"Indexed: {INDEX_PATH}")
    
    graph_md = generate_graph_content(deps, rdeps)
    with open(GRAPH_PATH, "w", encoding="utf-8") as fp:
        fp.write(graph_md)
    print(f"Indexed: {GRAPH_PATH}")

if __name__ == "__main__":
    main()
