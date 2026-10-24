# Nextcloud Server Android - Project Guidelines & Rules

## 1. Project Overview & Architecture
This project is an Android application that runs a self-hosted **Nextcloud Server** directly on Android devices (`arm64-v8a` / ARM 64-bit).

* **Package Name**: `com.nextcloud.server`
* **App Layer**: Kotlin, Android Architecture Components (Foreground Service, WorkManager, Material Components).
* **Server Stack**:
  * **Nginx** (Reverse Proxy & Web Server, listening on customizable port, e.g. `8080`).
  * **PHP-FPM** (FastCGI Process Manager on `127.0.0.1:9000`).
  * **PHP CLI** (`bin/php`) for running `nextcloud/cron.php` background tasks.
  * **SQLite** embedded database (`pdo_sqlite`).
  * **Nextcloud Core** extracted into `/data/data/com.nextcloud.server/files/nextcloud/`.

---

## 2. Core Compatibility Constraints

### Minimum Android Version: Android 8.0 (API 26)
* **`minSdk`**: Must strictly remain `26` (Android 8.0 Oreo).
* **`targetSdk` & `compileSdk`**: `34` (Android 14).
* **Service Lifecycle**:
  * Foreground service must be started via `startForegroundService()` on Android 8+ (`Build.VERSION.SDK_INT >= Build.VERSION_CODES.O`).
  * Must create and maintain a `NotificationChannel` with `NotificationManager.IMPORTANCE_DEFAULT`.
  * On Android 14+ (`Build.VERSION_CODES.UPSIDE_DOWN_CAKE`), `startForeground()` must specify `ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE`.
* **Broadcast Receivers**:
  * On Android 13+ (API 33/34), dynamic receivers must declare `RECEIVER_NOT_EXPORTED` or `RECEIVER_EXPORTED`.
* **Sleep & Throttling**:
  * Must hold a `PowerManager.PARTIAL_WAKE_LOCK` while the server is active so background HTTP requests do not hang when the screen is locked.

### Architecture Support: Strictly ARM64-v8a (`aarch64`)
* **Target ABI**: `arm64-v8a`.
* **ELF Linker**: All native binaries must use `/system/bin/linker64`.
* **Dynamic Linking**: All shared libraries must be located in `files/lib/` and resolved via `LD_LIBRARY_PATH`.
* **Execution**: Assets copied from APK to app storage must be granted execution permissions (`chmod 755`).

---

## 3. Server & Configuration Standards

* **`php.ini`**:
  * `memory_limit = 512M` (minimum required by Nextcloud).
  * `opcache.enable = 1` and `opcache.enable_cli = 1`.
  * `opcache.save_comments = 1` (**CRITICAL**: Doctrine annotations will fail if comments are stripped).
  * `opcache.interned_strings_buffer = 16`.
  * `output_buffering = 0`.
* **`nginx.conf`**:
  * `client_max_body_size 512M;` to support large file uploads without HTTP 413.
  * FastCGI buffers configured to prevent truncated responses.
  * Temporary directories (`client_body_temp_path`, `proxy_temp_path`, `fastcgi_temp_path`) overridden to `{{FILES_DIR}}/run/`.

---

## 4. Build & Maintenance Commands
* Build Debug APK: `.\gradlew.bat assembleDebug`
* Run dry-run checks: `.\gradlew.bat assembleDebug --dry-run`
* Check Gradle tasks: `.\gradlew.bat tasks`
* Refresh Architecture Index & Connection Graph: `python scripts/generate_codebase_index.py`

---

## 5. Architectural Index & Sequential Edit Guardrail
* **Token-Efficient Index**: Reference `.agents/skills/codebase-architecture-map/CODEBASE_INDEX.md` prior to scanning files.
* **Sequential Edit Chains**: When editing files in connected flows (e.g. preferences ➔ service ➔ UI), the sequential order in `CONNECTION_GRAPH.md` must be observed.
* **Guardrail Self-Check**: Before finishing code changes, assume an edit in a connected file broke downstream dependencies and explicitly verify state and intent synchronization.
* **Sync Frequency**: Run `python scripts/generate_codebase_index.py` after multi-file edits to keep the architecture map in sync.

## 6. Testing & Clean Installation Protocol
* **Strict Fresh Install Rule**: Always perform a fresh install when deploying to Android for testing to guarantee a 100% clean state.
* **Remove Ghost Configs**: Cleanly uninstall existing packages (`adb uninstall com.nextcloud.server`) to completely purge `/data/data/com.nextcloud.server/` (`config.php`, SQLite databases, and SharedPreferences). Purge any residual external directories (`/sdcard/Android/data/com.nextcloud.server/`, `/sdcard/NextcloudData/`) before reinstallation.
* **Disable Cloud Auto-Backup**: `android:allowBackup="false"` must remain in `AndroidManifest.xml` so Android never silently restores stale ghost configs from Google Drive / Cloud Backup upon reinstall.

---

## 7. Universal Storage & Removable Media Rules (Android 8 through 15+)

### Zero-Click, Out-of-the-Box Experience
* **No Blocking Pickers / Popups**: Never interrupt users with mandatory SAF folder pickers, bottom sheets, or manual "Grant Access" buttons upon drive hot-plugging.
* **Auto-Discovery**: Any connected MicroSD card, USB OTG drive, external SSD, or HDD must be automatically detected, mounted, and recognized with `Full Read & Write Access` out of the box.

### Android Version Matrix & Write Strategy
* **Android 8.0 – 9.0 (API 26 – 28)**:
  * Uses `READ_EXTERNAL_STORAGE` and `WRITE_EXTERNAL_STORAGE`.
  * App belongs to GID `sdcard_rw` (1015), providing direct POSIX root access to FAT32/exFAT partitions.
  * App-scoped directory `/storage/<UUID>/Android/data/com.nextcloud.server/files/` is 100% writable with zero permissions.
* **Android 10 (API 29)**:
  * Uses `android:requestLegacyExternalStorage="true"` to preserve raw direct filesystem access.
* **Android 11 – 15+ (API 30 – 35+)**:
  * Uses `MANAGE_EXTERNAL_STORAGE` (All Files Access) for unrestricted read access across all non-private directories on internal and secondary drives.
  * **MicroSD Cards**: Direct write access to arbitrary paths is mediated by `SdRootMediaStoreBridge` via `MediaStore.Files` (leveraging system `media_rw` daemon privileges).
  * **USB OTG / External SSD / HDD**: Direct POSIX write is guaranteed within the drive's app-scoped storage path (`/storage/<UUID>/Android/data/com.nextcloud.server/files/data/`) on all Android versions without user prompts.
* **Filesystem Types & Read-Only Handling**:
  * Only mark `isReadOnlyFs = true` if the physical filesystem is formatted as NTFS or mounted read-only by the kernel (`volume.state == Environment.MEDIA_MOUNTED_READ_ONLY`).
  * Normal FAT32, exFAT, and ext4 drives must always be marked `isWritable = true`.
