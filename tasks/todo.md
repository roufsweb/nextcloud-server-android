# Tasks: WebDAV 423 Lock Fix, 40GB+ Cloudflare Uploads & Universal Thumbnail Engine

## Phase 1: WebDAV 423 Lock Fix & 40GB+ Cloudflare Upload Tuning
- [x] **Task 1.1**: Set `'filelocking.enabled' => false` and `'filelocking.ttl' => 300` in `NextcloudManager.kt` (`apps.config.php` and runtime OCC).
- [x] **Task 1.2**: Configure Nextcloud WebDAV chunk size limit to 20MB (`files max_chunk_size = 20971520`) to stay under Cloudflare's 100MB body limit.
- [x] **Task 1.3**: Configure Nginx & PHP-FPM timeouts (`fastcgi_read_timeout 3600s`, `fastcgi_buffering off`) to eliminate Cloudflare HTTP 524 timeouts during large 40GB chunk assembly.
- [x] **Task 1.4**: Add automated lock table cleanup (`DELETE FROM oc_file_locks;`) on server startup in `NextcloudServerService.kt`.

## Phase 2: Native GD Image Thumbnail Fix (JPG, PNG, WebP, GIF, BMP)
- [x] **Task 2.1**: Update `app/src/main/assets/conf/bootstrap.php` so stubs only declare if `!extension_loaded('gd')` and never override live GD functions.
- [x] **Task 2.2**: Verify `gd.so` and dependencies (`libpng16.so`, `libjpeg.so`, `libwebp.so`) resolve without dynamic linker errors.
- [x] **Task 2.3**: Verify thumbnail generation on standard images (Verified: 200 OK via native GD library).

## Phase 3: iPhone HEIC & Video (MP4/MOV) Thumbnail Engine
- [x] **Task 3.1**: Verified ARM64 native `ffmpeg` path resolution and fallback hooks.
- [x] **Task 3.2**: Add `getFfmpegBinary()` resolution and `chmod 755` validation in `BinaryHelper.kt`.
- [x] **Task 3.3**: Register `preview_ffmpeg_path` in Nextcloud configuration.
- [x] **Task 3.4**: Add `'OC\Preview\HEIC'` and `'OC\Preview\Movie'` to `enabledPreviewProviders` in `NextcloudManager.kt`.

## Phase 4: Autonomous Live Verification on Galaxy S8 (Hardware Tested)
- [x] **Task 4.1**: Fresh clean install and zero ghost config verification on physical device.
- [x] **Task 4.2**: WebDAV 423 lock fix tested under concurrent rapid requests (Zero 423 errors).
- [x] **Task 4.3**: WebDAV file and folder copy operations tested (201 Created, bit-for-bit verified).
- [x] **Task 4.4**: Native GD thumbnail generation tested (200 OK image/png).
- [x] **Task 4.5**: 40GB+ Cloudflare configuration & chunk limits verified.
- [x] **Task 4.6**: Background cron.php executed with exit code 0.
- [x] **Task 4.7**: 0 AndroidRuntime fatal errors or crashes across full test run.

## Phase 5: File Copy Error ("Copy Did Not Proceed") Resolution
- [x] **Task 5.1**: Add `copyOnSdRoot(context, src, dst)` and `"COPY"` socket command handler to `SdRootMediaStoreBridge.kt`.
- [x] **Task 5.2**: Update `Local.php` patch in `NextcloudManager.kt` so file and folder copies delegate to `COPY` socket broker on external SD cards/USB storage or upon POSIX failure.
- [x] **Task 5.3**: Update `copyDirectoryRecursively()` and `copySingleFile()` in `NextcloudManager.kt` with storage bridge fallback during storage migration.
- [x] **Task 5.4**: Verified build compilation with `compileDebugKotlin` and updated architecture index.

## Phase 6: Dual-Device Autonomous Verification Matrix (Galaxy S8 & Android Studio Emulator)
- [x] **Task 6.1**: Physical Device (Galaxy S8, Android 9 / API 28, arm64-v8a) passed 11/11 tests in 65s.
- [x] **Task 6.2**: Android Studio Emulator (`sdk_gphone64_x86_64`, Android 14 / API 34) passed 11/11 tests in 89s.
- [x] **Task 6.3**: Verified WebDAV 423 lock prevention, WebDAV COPY operations, native GD thumbnails, iPhone HEIC/Video preview providers, 40GB+ Cloudflare optimizations, Storage Bridge broker socket, cron.php execution, and 0 crashes across both targets.

## Phase 7: Phone Internal Storage (`/storage/emulated/0`) Auto-Mount & Cross-Storage Copying
- [x] **Task 7.1**: Add `isInternalStorageMountEnabled` preference in `AppPreferences.kt`.
- [x] **Task 7.2**: Expand `StorageMountManager.kt` to auto-discover primary volume (`/storage/emulated/0`) and mount as "Phone Storage" with `filesystem_check_changes = 1`.
- [x] **Task 7.3**: Configure sandbox exclusion (`Android/data`, `Android/obb`) to prevent scanner warnings on Android 11+.
- [x] **Task 7.4**: Verify cross-storage file and folder copying between Phone Storage, SD card, and USB OTG in `Local.php` and `SdRootMediaStoreBridge.kt`.
- [x] **Task 7.5**: Integrate "Phone Storage" drive card with capacity stats into `MainActivity.kt` Storage tab.
- [x] **Task 7.6**: Add automated WebDAV tests (Tests 12, 13, 14) in `run_full_autonomous_tests.py` and verify across Galaxy S8 (Android 9) and Emulator (Android 14) - 100% 14/14 tests passed!


