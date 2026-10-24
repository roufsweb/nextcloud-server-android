# Implementation Plan: Universal Storage, PHP Security Hardening & Native Thumbnail Engine

## 1. Overview & Objectives
This multi-stage implementation plan addresses six core architectural and user-experience objectives for Nextcloud Server Android across Android 8.0 (API 26) through Android 15+:
1. **PHP Security Hardening & PHP2Shell Mitigation**:
   - Mitigate viral **PHP2Shell** vulnerabilities (**CVE-2026-109618** and **CVE-2026-109619**, CVSS 9.2 Critical RCE).
   - Mitigate HTTPoxy (**CVE-2016-5385**) and enforce production-grade security headers, error suppression, and strict session cookie policies in `php.ini` and `nginx.conf`.
2. **Universal Image Thumbnails Across Android 8–15+**:
   - Fix dynamic loading of PHP GD library (`libphp_gd.so`) by resolving Android 8–11 `PackageManager` library naming restrictions (`^lib.*\.so$`).
   - Ensure JPEG, PNG, WebP, GIF, and BMP thumbnail generation succeeds across Nextcloud Files, Gallery, and Photos.
3. **Storage Tab UI Simplification (Android App)**:
   - Streamline `item_external_storage_drive.xml` and `activity_main.xml`: eliminate wordy AI text bloat (e.g. *"Full Read & Write Access • ext4/F2FS"*).
   - Render clean, modern Material 3 cards: Drive Icon + Name + Path Pill + Capacity Bar + Toggle Switch.
4. **App Data & Database Protection**:
   - Strictly safeguard the core SQLite database (`owncloud.db`) and server configuration in private app storage (`/data/user/0/com.nextcloud.server/files/`).
   - Protect against database corruption caused by moving the core directory to removable/FUSE storage.
5. **Universal Storage Folders in Nextcloud Files**:
   - Expose Phone Internal Storage (`/storage/emulated/0`), MicroSD cards, and USB OTG drives as top-level root folders (`📁 Phone Storage`, `📁 SD Card`, `📁 USB Drive`).
   - Validate full functionality for Photos, Albums, Video streaming (HTTP 206 byte-ranges), and Nextcloud link sharing.
6. **Web UI Sidebar Storage List**:
   - Display a sleek storage widget directly above `#app-settings` ("Files settings") in the Nextcloud Web UI left navigation sidebar.
   - Show active storage volumes with real-time capacity and progress indicators.

---

## 2. Technical Architecture & Decisions

### Decision 1: PHP2Shell & Server Security Hardening
* **CVE-2026-109619 (Stale Global State / Use-After-Free in Persistent Workers)**:
  - *Vector*: Triggered via PHP's `session.upload_progress` feature interacting with request-owned memory.
  - *Mitigation*: Set `session.upload_progress.enabled = 0` in `php.ini.template`. Because Nextcloud uses WebDAV chunking rather than legacy PHP session upload meters, disabling this feature has zero impact on Nextcloud uploads while completely neutralizing the CVE attack surface.
* **CVE-2026-109618 (Out-of-Memory longjmp Corruption)**:
  - *Mitigation*: Ensure Nginx acts as a strict reverse proxy gatekeeper (`client_max_body_size 1024M;`, `client_body_buffer_size 128k;`), preventing malformed multipart headers from reaching PHP-FPM.
* **HTTPoxy (CVE-2016-5385)**:
  - *Mitigation*: Add `fastcgi_param HTTP_PROXY "";` in `nginx.conf.template` to strip any client-supplied `Proxy` headers.
* **Information Disclosure & Session Hardening**:
  - Add `expose_php = Off`, `display_errors = Off`, `display_startup_errors = Off`, `log_errors = On`.
  - Enforce `session.cookie_httponly = 1`, `session.use_strict_mode = 1`, `session.cookie_samesite = "Lax"`.

### Decision 2: Android 8–11 Native Library Extraction (`libphp_gd.so`)
* *Root Cause*: On Android 8.0 through 11 (API 26–30), Android `PackageManager` enforces a strict regex when extracting native libraries from `lib/arm64-v8a/` inside the APK: `^lib.*\.so$`.
* Files named `gd.so` or `sodium.so` were silently skipped during APK installation and never copied to `/data/app/.../lib/arm64/`.
* *Solution*:
  - Rename/alias `app/src/main/jniLibs/arm64-v8a/gd.so` to `libphp_gd.so`.
  - In `app/src/main/assets/php.ini.template`, load `extension=libphp_gd.so`.
  - In `NextcloudServerService.kt`, add an extraction fallback so `libphp_gd.so` is copied into `{{FILES_DIR}}/lib/` if missing.

### Decision 3: App Data & Database Sandbox Isolation
* Nextcloud's SQLite database (`owncloud.db`) relies on POSIX advisory byte-range locks (`fcntl`).
* FUSE partitions (`/storage/emulated/0`), FAT32, and exFAT partitions do NOT reliably support SQLite file locking or can be unmounted/ejected at any time.
* *Decision*: The core Nextcloud SQLite database and system configuration MUST strictly reside in `/data/user/0/com.nextcloud.server/files/`. All secondary and shared storages are mounted cleanly via Nextcloud's `files_external` (Local backend), ensuring data files are stored on external media while the database remains 100% crash-safe and protected.

### Decision 4: Web UI Sidebar Storage Widget
* Nextcloud Files sidebar has a standard footer button `#app-settings` ("Files settings").
* To display connected storages and free space right above this button:
  1. Android's `StorageMountManager.kt` exports `storage_stats.json` to `{{FILES_DIR}}/nextcloud/data/storage_stats.json` (or inside a custom theme/app asset).
  2. A lightweight JavaScript asset hooked into Nextcloud Files reads the storage status and renders a clean Material-style storage breakdown with progress bars directly above `#app-settings`.

---

## 3. Phased Task Breakdown

### Stage 1: PHP Security Hardening ("PHP2Shell" & Web Exploit Protection)
- **Task 1.1**: Update `app/src/main/assets/php.ini.template`:
  - Add `session.upload_progress.enabled = 0` (neutralize CVE-2026-109619).
  - Add `expose_php = Off`, `display_errors = Off`, `display_startup_errors = Off`, `log_errors = On`.
  - Add `error_log = "{{FILES_DIR}}/run/php_error.log"`.
  - Add `allow_url_include = Off`.
  - Add `session.cookie_httponly = 1`, `session.use_strict_mode = 1`, `session.cookie_samesite = "Lax"`.
- **Task 1.2**: Update `app/src/main/assets/nginx.conf.template`:
  - Add `fastcgi_param HTTP_PROXY "";` (mitigate CVE-2016-5385 HTTPoxy).
- **Task 1.3**: Verify PHP-FPM boots cleanly with the hardened configuration.

### Stage 2: Universal Image Thumbnails Engine (Android 8–15+)
- **Task 2.1**: In `app/src/main/jniLibs/arm64-v8a/`:
  - Copy/Rename `gd.so` to `libphp_gd.so`.
  - Copy/Rename `sodium.so` to `libphp_sodium.so`.
- **Task 2.2**: In `app/src/main/assets/php.ini.template`:
  - Change `extension=gd.so` to `extension=libphp_gd.so`.
- **Task 2.3**: In `NextcloudServerService.kt`:
  - Ensure fallback binary extraction extracts `libphp_gd.so` if needed.
- **Task 2.4**: Live verification on physical Galaxy S8 (Android 9):
  - Run `php -r 'var_dump(gd_info());'` via ADB.
  - Verify `JPEG Support => true`, `PNG Support => true`, `WebP Support => true`.

### Stage 3: Android App Storage Tab UI Simplification
- **Task 3.1**: In `app/src/main/res/layout/item_external_storage_drive.xml`:
  - Remove verbose `driveSubtitleText` ("Full Read & Write Access • ext4/F2FS").
  - Maintain a clean Material 3 header: Drive Icon + Drive Name + Toggle Switch.
  - Clean path pill and storage meter progress bar.
- **Task 3.2**: In `app/src/main/res/layout/activity_main.xml`:
  - Remove filler subtitle `Internal application data storage`.
- **Task 3.3**: In `app/src/main/java/com/nextcloud/server/MainActivity.kt`:
  - Update `updateExternalStorageUI()` to bind clean drive titles without verbose strings.
  - Retain hardware warning banner ONLY if `storage.isReadOnlyFs` is true.

### Stage 4: App Data & Database Sandbox Protection
- **Task 4.1**: In `MainActivity.kt`:
  - Replace the unconstrained `changeDataDirButton` directory chooser with a security dialog explaining that the core Nextcloud database and system configuration are securely isolated in private storage.
  - Ensure users mount external storage via the Storage tab drives list rather than risking database corruption.
- **Task 4.2**: Verify SQLite database permissions and isolation in `/data/user/0/com.nextcloud.server/files/`.

### Stage 5: Universal Storage Folders in Nextcloud Files & Feature Integration
- **Task 5.1**: Verify `StorageMountManager.kt` mounts all volumes as top-level Nextcloud folders:
  - 📁 `Phone Storage` (`/storage/emulated/0`)
  - 📁 `SD Card` (`/storage/<UUID>`)
  - 📁 `USB Drive` (`/storage/<UUID>`)
- **Task 5.2**: Verify Photos & Albums feature integration across all mounts (`oc_filecache` indexing).
- **Task 5.3**: Verify Video streaming (HTTP range requests via Nginx) and Nextcloud link sharing.

### Stage 6: Nextcloud Web UI Sidebar Storage List
- **Task 6.1**: In `StorageMountManager.kt`:
  - Implement `exportStorageStatsJson()` that periodically writes `storage_stats.json` with storage names, used/free/total GB, and percentage.
- **Task 6.2**: Implement a lightweight JavaScript hook in Nextcloud Files (`storage_sidebar.js`) that injects a sleek drive capacity widget directly above `#app-settings` ("Files settings").
- **Task 6.3**: Verify responsive display in desktop and mobile browser views.

### Stage 7: Automated Multi-Device Verification & Codebase Sync
- **Task 7.1**: Run autonomous test suite on Samsung Galaxy S8 (Android 9 / ARM64).
- **Task 7.2**: Run autonomous test suite on Android Studio Emulator (Android 14 / x86_64).
- **Task 7.3**: Update `CODEBASE_INDEX.md` and `CONNECTION_GRAPH.md` via `python scripts/generate_codebase_index.py`.

---

## 4. Risks & Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Disabling `session.upload_progress` breaks uploads | Medium | Nextcloud WebDAV and web uploads use chunking and OCS API, not PHP session upload meters. Verified safe. |
| Renaming `gd.so` to `libphp_gd.so` breaks ABI | Low | Native symbol exports remain identical; dlopen resolves seamlessly on Linux/Android linker. |
| SQLite corruption if database moved to SD card | High | Core database strictly retained in private app storage (`/data/user/0/...`); only user data folders mounted externally. |
| Custom sidebar JS doesn't render if DOM delayed | Low | Use `MutationObserver` or `OCA.Files` event listener with interval fallback. |
