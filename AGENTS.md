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

---

## 5. Testing & Clean Installation Protocol
* **Strict Fresh Install Rule**: Always perform a fresh install when deploying to Android for testing to guarantee a 100% clean state.
* **Remove Ghost Configs**: Cleanly uninstall existing packages (`adb uninstall com.nextcloud.server`) to completely purge `/data/data/com.nextcloud.server/` (`config.php`, SQLite databases, and SharedPreferences). Purge any residual external directories (`/sdcard/Android/data/com.nextcloud.server/`, `/sdcard/NextcloudData/`) before reinstallation.
* **Disable Cloud Auto-Backup**: `android:allowBackup="false"` must remain in `AndroidManifest.xml` so Android never silently restores stale ghost configs from Google Drive / Cloud Backup upon reinstall.
