# Nextcloud Server Android - Developer Requirements & Specifications

## 1. Project Overview & Architecture
This project is an Android application that hosts an embedded, self-contained **Nextcloud Server** instance directly on Android devices (`arm64-v8a` / ARM 64-bit).

* **Application ID**: `com.nextcloud.server`
* **Native Stack**:
  * **Nginx** (Reverse Proxy & HTTP Web Server, configurable port, default: `8000`).
  * **PHP-FPM** (FastCGI Process Manager listening on `127.0.0.1:9000`).
  * **PHP CLI** (`bin/php`) for running `occ` administrative commands and background maintenance (`cron.php`).
  * **SQLite 3** (`pdo_sqlite`) embedded database.
  * **Nextcloud Core** extracted into `/data/user/0/com.nextcloud.server/files/nextcloud/`.
* **Platform Constraints**:
  * **Target ABI**: Strictly `arm64-v8a` (64-bit ARM). All native binaries link via `/system/bin/linker64` and dynamic libraries reside in `files/lib/` resolved via `LD_LIBRARY_PATH`.
  * **Minimum SDK**: Android 8.0 (API 26).
  * **Target & Compile SDK**: Android 14 (API 34).

---

## 2. Storage System Specifications

### 2.1 Internal Storage
* **Nomenclature**: The main internal storage card must strictly be named **"Internal Storage"** in the application UI.
* **Default Directory**: Private app directory (`/data/user/0/com.nextcloud.server/files/nextcloud/data`).
* **Storage Change Operations**: The "Change" button on the Internal Storage card must directly offer **two distinct options**:
  1. **Migrate**:
     * Launches the Android document tree picker (`ActivityResultContracts.OpenDocumentTree()`) allowing the user to select any directory on the device's internal storage (e.g. `/storage/emulated/0/...`).
     * Places Nextcloud into maintenance mode.
     * Copies all existing user files, uploaded media, database catalogs, and configurations from the current storage folder to the chosen folder.
     * Updates `datadirectory` in `config.php` via `occ config:system:set datadirectory --value=<newPath>`.
     * Re-indexes files via `occ files:scan --all` and resumes normal server operation.
  2. **New**:
     * Launches the Android document tree picker allowing the user to select any directory on the device's internal storage.
     * **Does NOT copy or migrate existing user files** from the previous storage folder (acts as a clean, fresh installation location).
     * **Preserves system credentials & configurations**: Copies SQLite database files (`nextcloud.db*`), app configuration state (`appdata_*`), and security markers (`.ncdata`, `.ocdata`, `.htaccess`, `index.html`) so that:
       * Admin username and password remain identical.
       * Active mobile app login sessions and app passwords remain valid.
       * Installed apps, trusted domains, and system configs remain active.
     * Updates `datadirectory` in `config.php`.
     * Runs `occ files:cleanup` to purge orphaned file cache entries for uncopied files.
     * Runs `occ files:scan --all` to index only the fresh files in the new directory.
* **Markers & Permissions**: Every internal data directory must contain `.ncdata` and `.ocdata` verification markers with `check_data_directory_permissions` disabled in `config.php`.

### 2.2 External Storage (MicroSD & USB OTG / SSD Drives)
* **Auto-Discovery**: Hardware and media broadcast receivers (`ACTION_MEDIA_MOUNTED`, `ACTION_MEDIA_UNMOUNTED`, `ACTION_USB_DEVICE_ATTACHED`, `ACTION_USB_DEVICE_DETACHED`) automatically detect removable storage media across Android 8 through 15.
* **Supported Formats**: FAT32, exFAT, NTFS, ext4, and F2FS.
* **100% Root-Free Universal Architecture**:
  * **Zero Root Dependency**: The application requires **NO root permissions (`su`)** whatsoever and operates completely unprivileged on standard, stock Android devices.
  * **Standard Android SDK External Storage Provisioning**: Removable storage drives (MicroSD and USB OTG drives) are mounted via standard Android SDK APIs (`StorageManager.storageVolumes` and `ContextCompat.getExternalFilesDirs()`).
  * **Guaranteed Read/Write Path**: The mount targets the app-owned directory on the removable storage:
    `<RemovableStoragePath>/Android/data/com.nextcloud.server/files/Nextcloud`
    Under Android's Linux kernel and storage subsystem (API 26 through 35), this directory is owned directly by the application's Linux UID with full read, write, and execute permissions (`rwx`), guaranteeing 100% reliable file uploads, edits, and deletions without root elevation, SELinux denials, or Scoped Storage friction.
  * **Single Unified Mount per Drive**: Exactly one clean, top-level mount is created in Nextcloud per physical drive (named `External Storage`, or hardware-classified `SD Card` / `USB Drive` when multiple drives are connected). Stale/read-only companion media mounts are strictly avoided to eliminate user confusion and upload failures.
  * **Upload Chunk Staging (`tempdirectory`)**: Nextcloud's system configuration strictly points `'tempdirectory'` to the writable app temp directory (`{filesDir}/tmp`) in both `apps.config.php` and `config.php`, with matching `sys_temp_dir` / `upload_tmp_dir` in `php.ini` and `env[TMP]` in `php-fpm.conf`, completely eliminating Android's unwritable `/data/local/tmp` upload staging bottleneck.
  * **Change Detection**: Mounted with `filesystem_check_changes = 1` and `enable_sharing = true` so local disk updates are immediately visible in the Nextcloud Web UI and mobile apps.
* **UI Presentation**:
  * **Conditional Visibility**: The "External Storage" section and connected drives list in the UI **only show up when external storage devices are physically connected**. When no external drives are attached, the section is completely hidden.
  * **Minimal Controls**: Each detected physical drive is listed with its hardware icon (SD card or USB), display name, storage capacity, filesystem path, and a single **MaterialSwitch toggle**.
  * **Toggle Default**: Enabled (**ON**) by default upon connection.
  * **Toggle Action**:
    * When toggled **OFF**: Unmounts the drive from Nextcloud (`files_external:delete`) without altering the physical drive.
    * When toggled **ON**: Re-mounts the drive into Nextcloud with full access and triggers a background file scan (`files:scan`).
* **Hot-Unplug Protection**:
  * When an external drive is unplugged, it is automatically unmounted from Nextcloud and `occ files:cleanup` cleans orphaned cache entries.
  * If the primary `datadirectory` was located on an external drive that was unplugged, the server immediately freezes into maintenance mode and presents a recovery dialog allowing fallback to internal storage.

---

## 3. Server Configuration & Networking Standards

### 3.1 Nginx Web Server
* **Relative Redirects**: `absolute_redirect off;` and `port_in_redirect off;` must be strictly configured in Nginx so that local network requests (e.g. `http://192.168.1.50:8000/`) retain their port and are never redirected to portless IP addresses.
* **Port Switching**: Server port is user-configurable (range 1024–65535, default 8000). Applying a new port updates `nginx.conf`, restarts Nginx and PHP-FPM, and updates `overwrite.cli.url`.
* **Payload Limits**: `client_max_body_size 512M;` to support large file uploads.
* **FastCGI Parameter Forwarding**:
  * `fastcgi_param HTTP_AUTHORIZATION $http_authorization;`
  * `fastcgi_param SERVER_PORT $server_port;`
* **Reverse Proxy & Cloudflare**:
  * Ingress headers `HTTP_CF_CONNECTING_IP` and `HTTP_X_FORWARDED_FOR` forwarded to PHP.
  * Cloudflare IP ranges configured as `trusted_proxies` in `config.php`.
  * Dynamic trusted domain management via UI and `occ config:system:set trusted_domains`.

### 3.2 PHP-FPM Runtime
* `memory_limit = 512M` (minimum required by Nextcloud core).
* `opcache.enable = 1`, `opcache.enable_cli = 1`, and `opcache.save_comments = 1` (**critical** for Doctrine metadata annotations).
* SQLite journal mode: `WAL` (Write-Ahead Logging).

---

## 4. Mobile App Integration & QR Code Authentication
* **Persistent Mobile Credentials**: Nextcloud 30+ introduced temporary one-time QR credentials. To allow seamless, permanent mobile app logins via QR code, the app must configure:
  ```bash
  occ config:app:set settings qrcode_onetime --value=no
  ```
* **Authorization Headers**: FastCGI must pass `HTTP_AUTHORIZATION` so HTTP Basic Auth and Bearer app tokens are processed by Nextcloud's core authentication subsystem.

---

## 5. Background Execution & System Permissions

### 5.1 Service Lifecycle & Foreground State
* **Foreground Service**: The server must run as an Android Foreground Service with a persistent, non-dismissible notification.
* **Notification Channel**: Created with `NotificationManager.IMPORTANCE_DEFAULT`.
* **Android 14+ Compatibility**: `startForeground()` must specify `ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE`.
* **CPU WakeLock**: Holds a `PowerManager.PARTIAL_WAKE_LOCK` while server is active so background requests, uploads, and cron tasks run reliably with the screen turned off.

### 5.2 Settings & Permission Toggles
The **Settings** tab must provide actionable, real-time toggles for:
1. **Start on Boot**: Managed via `BootReceiver` listening for `ACTION_BOOT_COMPLETED`.
2. **Battery Optimization**: Requests battery whitelist (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).
3. **Storage Permission**: Directs to `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` (Android 11+) or runtime storage permission (Android 8–10).
4. **Notification Permission**: Directs to notification settings (`POST_NOTIFICATIONS` on Android 13+).
5. **CPU WakeLock**: Toggleable switch to retain or release partial wake locks during active server operation.

---

## 6. UI/UX & Design Standards
* **Material Design 3 (M3)**: Built with official Material Components for Android (`MaterialCardView`, `MaterialButton`, `MaterialSwitch`, `LinearProgressIndicator`).
* **Tab Navigation**: Clean 4-tab bottom navigation bar:
  1. **Dashboard**: Service state pill, unified Start/Stop action, server URL with copy/browser launch, port configuration, and quick glance metrics.
  2. **Storage**: Internal Storage card (with Migrate & New actions), dynamic External Storage card (visible only when drives connected, with toggles), and storage capacity bar.
  3. **Network**: Trusted domains management and admin credentials manager.
  4. **Settings**: Theme selection (Light / Dark / System), Start on Boot toggle, and permission status toggles.
* **Icons**: Strictly use official Google Material vector drawables (`.xml`). Never use colored circle shapes or placeholders.
* **Clean Nomenclature**: Professional, standard naming throughout. Avoid internal debugging text or duplicated metrics.
