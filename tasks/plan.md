# Implementation Plan: WebDAV 423 Lock Fix, 40GB+ Cloudflare Uploads & Universal Thumbnail Engine

## 1. Overview & Objectives
Resolve four critical challenges affecting Nextcloud Server on Android without hardware/GPU dependencies:
1. **Fix WebDAV HTTP 423 Locked Exception**: Prevent deadlocks and purge orphaned file locks caused by SQLite concurrency during interrupted transfers.
2. **Reliable 40GB+ Big File Uploads Through Cloudflare**:
   - Bypass Cloudflare's 100MB per-request upload cap using Nextcloud WebDAV chunked uploads.
   - Prevent Cloudflare HTTP 524 timeouts during long assembly of 40GB files on Android storage.
   - Configure Nginx and PHP-FPM streaming with zero-buffering to avoid disk exhaustion.
3. **Fix Image Previews & Thumbnails (JPEG, PNG, WebP, GIF, BMP)**: Fix GD extension runtime loading and eliminate dummy stubs in `bootstrap.php` that return `false`.
4. **Add Full iPhone HEIC & Video (MP4/MOV) Thumbnail Support**:
   - Enable `OC\Preview\HEIC` using native ARM64 `libheif` / `heif-convert` or CPU-based decoding.
   - Enable `OC\Preview\Movie` for iPhone video clips (`.mov`, `.mp4`) via native ARM64 CLI tools (`ffmpeg`).

---

## 2. Technical Strategy & Decisions

### A. 40GB+ Big File Uploads Through Cloudflare Without Errors
Cloudflare enforces two strict limits:
1. **100 MB Max Request Body** (Free/Pro plans return `HTTP 413 Entity Too Large` if a single request exceeds 100MB).
2. **100-Second HTTP Response Timeout** (Cloudflare returns `HTTP 524 A timeout occurred` if the server doesn't respond within 100s).

#### Strategic Solution for 40GB+ Uploads:
* **Enforce Safe Chunk Size (10 MB – 20 MB)**:
  - Configure Nextcloud WebDAV chunking via OCC: `config:app:set files max_chunk_size --value 20971520` (20MB) or 10MB in Web UI / clients. Every HTTP request stays well below Cloudflare's 100MB ceiling.
* **Disable Request Buffering in Nginx (`fastcgi_request_buffering off`)**:
  - Already configured in `nginx.conf.template`! Chunks stream straight from Cloudflare into PHP-FPM without Nginx saving temporary chunk files to internal storage twice.
* **Prevent Cloudflare HTTP 524 Timeout During 40GB Chunk Assembly**:
  - In Nextcloud, when the final chunk finishes, a `MOVE` request triggers chunk concatenation. Concatenating 40 GB on Android UFS/MicroSD takes 2–5 minutes, which would trip Cloudflare's 100s timeout if unhandled.
  - Solution: In `nginx.conf.template`, disable fastcgi response buffering for DAV upload endpoints (`fastcgi_buffering off;`) and set `proxy_read_timeout 3600;`, `fastcgi_read_timeout 3600;`. Nextcloud outputs progress / whitespace bytes during assembly to keep the HTTP socket alive through Cloudflare.
* **Storage Temp Directory Optimization**:
  - Ensure `tempdirectory` in `apps.config.php` and `upload_tmp_dir` in `php.ini.template` point to the target drive or large volume with sufficient free space (at least 40GB free) so chunk staging doesn't exhaust `/data/data`.

### B. WebDAV 423 Lock Resolution (SQLite Concurrency)
- **Problem**: Nextcloud's transactional file locking stores locks in `oc_file_locks`. In SQLite, when an upload drops or a PHP-FPM worker exits, lock records stay in `oc_file_locks` (`lock > 0`), permanently blocking subsequent WebDAV requests.
- **Fix**:
  1. Set `'filelocking.enabled' => false` in `apps.config.php` and via OCC on first boot / runtime initialization. On a single-user or small family embedded Android server without Redis, transactional file locking causes deadlocks on SQLite.
  2. Implement an automated lock cleanup routine in `NextcloudManager.kt` (`files:cleanup` or `DELETE FROM oc_file_locks WHERE 1;` when server starts or when maintenance mode toggles).
  3. Ensure `filelocking.ttl = 300` (5 minutes) if locking is selectively retained for WebDAV atomic operations.

### C. Standard Image Thumbnails (JPEG, PNG, WebP, GIF, BMP)
- **Problem**: `app/src/main/assets/conf/bootstrap.php` contains stub overrides (`imagecreatetruecolor`, `imagecreatefromjpeg`, etc.) returning `false`, causing all thumbnail generation to fail if GD isn't overriding them cleanly.
- **Fix**:
  1. Inspect native `gd.so` dynamic loading in `app/src/main/jniLibs/arm64-v8a/` and ensure all dependent libraries (`libpng16.so`, `libjpeg.so`, `libwebp.so`, `libfreetype.so`, `libz.so`) resolve cleanly via `LD_LIBRARY_PATH`.
  2. Guard `bootstrap.php` so it only stubs functions if `extension_loaded('gd')` is truly false; when `gd` is loaded, genuine GD functions must handle thumbnails.
  3. Verify memory limit (`memory_limit = 512M`) and `preview_max_memory = 256` in `NextcloudManager.kt`.

### D. iPhone HEIC Image Thumbnails
- **Problem**: Nextcloud requires `OC\Preview\HEIC` provider, which depends on `libheif`.
- **Strategy**:
  1. We already have `libheif.so` (1.9MB) and `libde265.so` (440KB) compiled in `app/src/main/jniLibs/arm64-v8a/`.
  2. Add `OC\Preview\HEIC` to `enabledPreviewProviders` in `NextcloudManager.kt` and `apps.config.php`.
  3. Provide an ARM64 binary (`heif-convert` or `ffmpeg`) in `files/bin/` so `OC\Preview\HEIC` can decode iPhone pictures to JPEG thumbnails.

### E. Video Thumbnails (iPhone MOV / MP4 / MKV)
- **Problem**: `OC\Preview\Movie` is absent from `enabledPreviewProviders` and no `ffmpeg` binary is configured.
- **Strategy**:
  1. Bundle a pre-compiled ARM64 static `ffmpeg` CLI binary into the app native assets or binary distribution (`files/bin/ffmpeg`).
  2. Configure Nextcloud's `preview_ffmpeg_path => '{{FILES_DIR}}/bin/ffmpeg'`.
  3. Register `'OC\Preview\Movie'` in `enabledPreviewProviders` in `NextcloudManager.kt` and `apps.config.php`.

---

## 3. Sequential Edit Chains (Based on `codebase-architecture-map`)

```
Chain A (WebDAV 423 Locks & 40GB Upload Settings):
NextcloudManager.kt (apps.config.php, chunk size & filelocking) ➔ assets/nginx.conf.template (timeouts & buffering) ➔ assets/php-fpm.conf.template (timeouts) ➔ NextcloudServerService.kt (lock cleanup)

Chain B (Image & Thumbnail Pipeline):
assets/conf/bootstrap.php (remove dummy false stubs) ➔ assets/php.ini.template (extension verification) ➔ NextcloudManager.kt (enabledPreviewProviders) ➔ MainActivity.kt (UI verification)

Chain C (Video & HEIC Native Binaries):
jniLibs/arm64-v8a/ or assets/bin/ (ARM64 ffmpeg / heif-convert) ➔ BinaryHelper.kt (executable resolution) ➔ NextcloudManager.kt (preview_ffmpeg_path setting)
```

---

## 4. Phased Task Breakdown

### Phase 1: WebDAV 423 Lock Mitigation & 40GB+ Cloudflare Tuning
- **Task 1.1**: In `NextcloudManager.kt`, set `'filelocking.enabled' => false` and `'filelocking.ttl' => 300` in `apps.config.php` and via OCC `config:system:set`.
- **Task 1.2**: Set Nextcloud default chunk size to 20MB (`occ config:app:set files max_chunk_size --value 20971520`) to guarantee Cloudflare 100MB limit compliance.
- **Task 1.3**: Configure Nginx & PHP-FPM timeouts (`fastcgi_read_timeout 3600s;`, `request_terminate_timeout = 3600`) and ensure `fastcgi_buffering off;` for long chunk assembly.
- **Task 1.4**: In `NextcloudServerService.kt`, add an automated lock cleanup on server startup executing SQLite SQL `DELETE FROM oc_file_locks;` to purge any leftover locks from previous crashes.

### Phase 2: Native GD & Image Thumbnail Restoration
- **Task 2.1**: Refactor `app/src/main/assets/conf/bootstrap.php` so stub functions do not shadow active PHP GD functions when `gd.so` is loaded.
- **Task 2.2**: Verify GD extension loading in `php.ini.template` and confirm JPEG, PNG, WebP, GIF, and BMP generate thumbnails under Nextcloud Gallery/Photos.
- **Task 2.3**: Update `NextcloudManager.kt` preview configuration settings (`preview_max_x = 2048`, `preview_max_y = 2048`, `enable_previews = true`).

### Phase 3: iPhone HEIC & Video (MP4/MOV) Thumbnail Engine
- **Task 3.1**: Add native ARM64 `ffmpeg` binary (`files/bin/ffmpeg`) and grant `chmod 755` via `BinaryHelper.kt`.
- **Task 3.2**: Configure `'preview_ffmpeg_path'` in Nextcloud to point to `BinaryHelper.getFfmpegBinary(context)`.
- **Task 3.3**: Register `'OC\Preview\HEIC'` and `'OC\Preview\Movie'` in `enabledPreviewProviders` in `NextcloudManager.kt` and `apps.config.php`.
- **Task 3.4**: Ensure `libheif.so` and `libde265.so` dynamic linkage is available for HEIC decoding.

### Phase 4: Integration Verification & Regression Testing
- **Task 4.1**: Upload sample iPhone HEIC photo and verify high-resolution thumbnail generation in Nextcloud web UI and mobile app.
- **Task 4.2**: Upload sample MP4/MOV video and verify video thumbnail keyframe extraction.
- **Task 4.3**: Test large chunked upload through Cloudflare Tunnel and verify completion without HTTP 413 or HTTP 524 timeouts.
- **Task 4.4**: Run `run_full_autonomous_tests.py` to confirm zero WebDAV errors, zero locks, and full storage health.

---

## 6. Implementation Plan: Phone Internal Storage (`/storage/emulated/0`) Integration & Seamless Cross-Storage Copying

### A. Core Objectives & Non-Regression Invariants
1. **Expose Internal Storage (`/storage/emulated/0`)**: Automatically mount the phone's internal shared storage into Nextcloud under the display name **"Phone Storage"** (via `files_external` local backend).
2. **Zero-Click User Experience**: No manual folder pickers or permissions popups. Nextcloud automatically detects and mounts `/storage/emulated/0` alongside any connected MicroSD card or USB OTG drive.
3. **Full Read & Write Access**: Allow users to read, upload, modify, and delete files inside `/storage/emulated/0` (including `Download/`, `DCIM/`, `Documents/`, etc.) directly from Nextcloud WebDAV and web interface.
4. **Seamless Cross-Storage Copying**: Guarantee clean file and folder duplication between:
   - `Phone Storage` ⇄ `SD Card`
   - `Phone Storage` ⇄ `USB Drive`
   - `SD Card` ⇄ `USB Drive`
   With **zero HTTP 423 locks**, **zero permission errors**, and **zero WebDAV copy exceptions**.
5. **Strict Non-Regression Invariants**:
   - **Database Protection**: The SQLite database (`nextcloud.db`) remains 100% in private app storage (`/data/data/com.nextcloud.server/files/nextcloud/data/`), fully protected from FUSE file-locking latency and accidental user deletion.
   - **Removable Media Hot-Plug**: Retain existing auto-discovery and dynamic mounting of MicroSD cards, USB OTG flash drives, external SSDs, and HDDs.
   - **Cloudflare & Network Settings**: Preserve 20MB chunk limits, 3600s FastCGI timeouts, and unbuffered streaming.
   - **Android 8 to 15+ Compatibility**: Zero regressions across Android 8.0 (API 26) through Android 15+ (API 35+).

---

### B. Sequential Edit Chains (Architecture Map Compliant)

```
Chain 1: Preference Configuration
AppPreferences.kt (add isInternalStorageMountEnabled: Boolean, defaults to true)

Chain 2: Storage Discovery & Mounting Engine
StorageMountManager.kt:
  ├── Update getRemovableStorages() to include Primary Volume (/storage/emulated/0)
  ├── Classify primary volume with displayName = "Phone Storage", fsType = ext4/F2FS
  ├── Ensure write-probe verifies direct POSIX write on /storage/emulated/0
  ├── Reconcile existing files_external mounts to avoid duplicate "Phone Storage" entries
  └── Exclude restricted system paths (Android/data, Android/obb) from files:scan to prevent OS permission warnings

Chain 3: Cross-Storage Stream & Copy Mediation
Local.php (in assets / runtime patch) & SdRootMediaStoreBridge.kt:
  ├── Ensure copy() handles direct POSIX copy between Internal and USB drives
  └── Fallback to abstract Unix socket bridge (@com.nextcloud.server.saf_bridge) when copying to/from MicroSD card root

Chain 4: Material 3 UI & Drive Cards
MainActivity.kt & Storage Tab:
  ├── Display "Phone Storage" card alongside removable USB/SD drives
  ├── Show real-time free/used capacity of internal flash
  └── Provide toggle switch to enable/disable Phone Storage mount without server restart

Chain 5: Autonomous End-to-End Validation
scratch/run_full_autonomous_tests.py:
  ├── Test 12: Verify Phone Storage (/storage/emulated/0) files_external mount active
  ├── Test 13: Test WebDAV PUT/GET directly into Phone Storage
  └── Test 14: Test cross-storage COPY between Phone Storage and secondary storage
```

---

### C. Phased Task Breakdown

#### Phase 1: Storage Discovery Engine Expansion (`StorageMountManager.kt`)
- **Task 1.1**: Update `getRemovableStorages()` to inspect `storageManager.storageVolumes` for both `volume.isPrimary` and `volume.isRemovable`.
- **Task 1.2**: Generate a unique storage entry for Primary Internal Storage:
  - `id` = `"primary_internal"`
  - `displayName` = `"Phone Storage"`
  - `nextcloudMountPoint` = `"Phone Storage"`
  - `path` = `/storage/emulated/0`
  - `fsType` = `"ext4/F2FS"`
  - `isWritable` = `true` (validated via zero-byte probe `nc_write_probe_*.tmp`)
- **Task 1.3**: Add check in `addNextcloudMount()` so Nextcloud's `files_external` creates local mount `"Phone Storage"` with `filesystem_check_changes = 1` and `enable_sharing = true`.

#### Phase 2: Protection of System Sandboxes (`Android/data` and `Android/obb`)
- **Task 2.1**: On Android 11+ (API 30+), Android sandbox restricts access into other apps' private directories in `/storage/emulated/0/Android/data` and `/storage/emulated/0/Android/obb`.
- **Task 2.2**: Add exclusion or ignore rule so Nextcloud background `files:scan` skips `Android/data` and `Android/obb`, preventing unnecessary permission warnings in `nextcloud.log` while indexing all other phone folders (`DCIM`, `Download`, `Documents`, `Pictures`, `Music`, `Movies`).

#### Phase 3: Cross-Storage Copy & WebDAV Stream Relay Assurance
- **Task 3.1**: Verify `copy()` and `move()` operations in Nextcloud Flysystem adapter [`Local.php`](file:///e:/rouf/android-apk/nextcloud-server-android/scratch/Local.php) when transferring between `Phone Storage` and `SD Card`.
- **Task 3.2**: Confirm `SdRootMediaStoreBridge.copyOnSdRoot()` smoothly streams data across mounts via `@com.nextcloud.server.saf_bridge` without loading files into memory.

#### Phase 4: UI Integration & User Control (`MainActivity.kt`)
- **Task 4.1**: In `MainActivity.kt` Storage tab, include `primary_internal` in the drive cards list.
- **Task 4.2**: Display total and available internal flash storage using `StatFs("/storage/emulated/0")`.
- **Task 4.3**: Allow user to enable or disable the "Phone Storage" mount with a clean switch, saved in `AppPreferences`.

#### Phase 5: Live Automated Testing & Verification
- **Task 5.1**: Add autonomous tests to [`scratch/run_full_autonomous_tests.py`](file:///e:/rouf/android-apk/nextcloud-server-android/scratch/run_full_autonomous_tests.py).
- **Task 5.2**: Test live creation of a file in `/storage/emulated/0/Nextcloud/` via WebDAV.
- **Task 5.3**: Test copying that file between Phone Storage and secondary storage via WebDAV HTTP `COPY`.
- **Task 5.4**: Confirm file is 100% visible on Android File Explorer via `ls -la /storage/emulated/0/`.
- **Task 5.5**: Run full 14-test suite on both Samsung Galaxy S8 and Android Studio Emulator.

---

### D. Acceptance Criteria
- [ ] `/storage/emulated/0` appears as a top-level **"Phone Storage"** folder inside Nextcloud Web and WebDAV.
- [ ] Users can browse, upload, download, and delete files in their phone's internal storage (`Download`, `DCIM`, etc.) via Nextcloud.
- [ ] Any file saved to the phone's internal storage is instantly visible in Nextcloud, and vice versa.
- [ ] Cross-storage copy operations succeed with HTTP 201 Created and zero locks/errors.
- [ ] No regression on SQLite database stability, port forwarding, or USB/SD card hot-plug detection.
- [ ] 100% passing automated test suite on both Android 9 and Android 14.
