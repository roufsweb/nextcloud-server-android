# Tasks: Universal External Storage & SD Card Root Engine

## Phase 1: Galaxy S8 Baseline & Environment Setup
- [ ] **Task 1:** Perform clean uninstall on Galaxy S8 (`adb uninstall com.nextcloud.server`), install latest `nextcloud-server-v1.1.0-arm64-v8a.apk`, and verify service startup & initialization.
- [ ] **Task 2:** Test and log direct POSIX read/write access to Galaxy S8 SD card (`/storage/DCBC-EC41/`) using app credentials.

## Checkpoint 1: Galaxy S8 Baseline Verified
- [ ] Galaxy S8 running latest code cleanly.
- [ ] Server accessible via local Wi-Fi / loopback port.

## Phase 2: Direct Root SD Card Mount in Nextcloud
- [ ] **Task 3:** Update `StorageMountManager.kt` to support mounting the physical drive root (`/storage/<UUID>/`) as a choice or automatic default when root is writable.
- [ ] **Task 4:** Ensure Nextcloud's `files_external:create` correctly binds `/storage/<UUID>/` and handles directory permission probes.

## Phase 3: Android 11–17 Zero-Picker Broker Engine
- [ ] **Task 5:** Implement `SdRootMediaStoreBridge.kt` with `LocalServerSocket` and `SCM_RIGHTS` fd passing.
- [ ] **Task 6:** Implement dotfile exception handling (`.ocdata`, `.part`) and directory creation handlers.

## Phase 4: Live Device Testing, Logging & Fixes
- [ ] **Task 7:** Deploy to Galaxy S8 and verify `/storage/DCBC-EC41/` mounts into Nextcloud `files_external`.
- [ ] **Task 8:** Run `files:scan` on the SD card mount point and verify Nextcloud indexes all songs, photos, and files already on the card.
- [ ] **Task 9:** Perform end-to-end write test (create file, edit, delete) on the SD card via Nextcloud WebDAV / OCC.
- [ ] **Task 10:** Review logs (`adb logcat`, `php_error.log`, `nginx_error.log`), fix any detected bugs, and confirm clean operation.
