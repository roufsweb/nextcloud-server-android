# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.4.0] - 2026-10-27

### Added
- **Physical MicroSD Card Full Root Access (Android 8–10+)**:
  - Transparent socket-mediated SAF bridge in `SdRootMediaStoreBridge.kt` and `Local.php` granting full root read/write access to removable MicroSD cards and USB OTG storage.
  - Native creation of folders (MKCOL), file uploads (PUT), renames, and deletions directly on physical SD card root without requiring root/SU.
- **Embedded ARM64 FFmpeg Media Engine (`libffmpeg.so`)**:
  - Bundled native 64-bit ARM FFmpeg binary in APK `jniLibs/arm64-v8a/libffmpeg.so`.
  - Native HEIC photo decoding directly into GD Image runtime via isolated `LD_LIBRARY_PATH=/system/lib64` child processes, avoiding Android dynamic linker namespace collisions with `libhwui.so`.
  - Rapid keyframe preview extraction for MOV, MP4, and drone video files.
- **Modern Vector SVG Icons & Cache Invalidation for Web UI**:
  - Replaced all emojis (`📱`, `💾`, `🔌`, `🔄`) with crisp, scalable SVG vector outlines (smartphone, microSD, USB flash drive, sync refresh) in the bottom-left storage widget.
  - Added `Cache-Control: no-cache, no-store, must-revalidate` in Nginx and dynamic `filemtime` versioning (`?v=<timestamp>`) in `layout.user.php` preventing stale browser caching.

### Fixed
- **Cross-Storage Move & Copy Normalization**:
  - Normalized path resolution in `Local.php` (`rtrim($this->datadir, '/') . '/' . ltrim($path, '/')`) preventing `//storage/` double slash which previously caused WebDAV `403 Forbidden: Copy did not proceed`.
- **External Mount Point Error Dialog**:
  - Enforced `status: 0` (`STATUS_SUCCESS`) on external storage configuration responses in `ApiController.php`, eliminating the "External mount error" modal dialog on `/apps/files/extstoragemounts`.

---

## [1.3.0] - 2026-10-24

### Added
- **Phone Internal Storage (`/storage/emulated/0`) Auto-Mount**:
  - Automatically mounts primary internal storage as "Phone Storage" via `files_external` local storage backend with zero database risk (`owncloud.db` remains isolated and protected in private app storage).
  - Real-time timestamp monitoring (`filesystem_check_changes = 1`) ensuring photos, downloads, and files placed via Android File Explorer or PC USB cable (MTP) appear immediately in Nextcloud without manual scanning.
  - Dedicated storage card and toggle switch in the Material 3 Storage tab.
- **iPhone HEIC & Video Preview Engines**:
  - Native GD image thumbnail engine support for PNG, JPEG, GIF, BMP, and WebP.
  - `OC\Preview\HEIC` and `OC\Preview\Movie` registered preview providers with ARM64 native `ffmpeg` integration.
- **Cross-Storage File Copy Operation**:
  - Added socket-mediated `COPY` command to `SdRootMediaStoreBridge` and `Local.php` enabling clean file and folder duplication between Phone Storage, MicroSD card root, and USB OTG storage.
- **40GB+ Big File Upload & Cloudflare Tunnel Tuning**:
  - Configured 20MB chunk limits (`files max_chunk_size = 20971520`) to stay strictly below Cloudflare's 100MB body cap.
  - Set Nginx and PHP-FPM FastCGI buffers and timeouts to 3600s with `fastcgi_buffering off` preventing Cloudflare HTTP 524 timeouts during large chunk assembly.

### Fixed
- **WebDAV HTTP 423 Locked Fix**:
  - Disabled client-side file locking (`filelocking.enabled => false`, `filelocking.ttl => 300`) preventing 423 locks during concurrent uploads and synchronization.
  - Added automated SQLite lock cleanup (`DELETE FROM oc_file_locks;`) on server initialization to purge orphaned locks from interrupted transfers.
- **"Copy Did Not Proceed" Resolution**:
  - Solved POSIX cross-volume copy failure across external storage partitions by falling back to `SdRootMediaStoreBridge` streaming copy.
- **GD PHP Extension Stubs**:
  - Guarded fallback functions in `bootstrap.php` with `!extension_loaded('gd')` so native compiled GD library functions are never masked.

### Verified
- Automated 14-test autonomous end-to-end suite passing 100% on:
  - Samsung Galaxy S8 (SM-G950N, Android 9 / API 28, arm64-v8a physical hardware).
  - Android Studio Emulator (Android 14 / API 34, x86_64).

---

## [1.2.0] - 2026-10-22

### Added
- Zero-Click hot-plug removable storage for MicroSD cards and USB OTG drives.
- Support for Android 15+ kernel 16 KB page sizes and SELinux W^X constraints.

---

## [1.1.0] - 2026-10-21

### Added
- Native GD image processing library integration.
- Expanded PHP-FPM worker pool for concurrent WebDAV sync.

---

## [1.0.0] - 2026-10-20

### Added
- Initial public release of Nextcloud Server for Android (ARM64-v8a).
- Native Nginx, PHP-FPM, SQLite, and Nextcloud Core integration.
