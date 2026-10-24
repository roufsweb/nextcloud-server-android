# Implementation Checklist: Universal Zero-Popup Storage, Clean Root & Multi-Format Previews

## Stage 1: Zero-Popup Out-Of-The-Box External Storage Mount Engine
- [x] **Task 1.1**: Update `StorageMountManager.kt`:
  - Enforce zero-popup write detection.
  - If bare root `/storage/<UUID>` is not directly writable by POSIX (unrooted Android 8–10 phones), auto-route to the guaranteed writable directory on that physical drive (`/storage/<UUID>/Android/data/com.nextcloud.server/files/Nextcloud`).
  - Guarantee `isWritable = true` and ensure no blocking permission dialogs or SAF pickers ever interrupt the user.
- [x] **Task 1.2**: Update `SdRootMediaStoreBridge.kt`:
  - Enhance `openRootWritablePfd()` so that if bare root write fails or is blocked on non-rooted Android 8–10, it transparently opens the target in the volume's writable app path.

## Stage 2: Total Nextcloud Skeleton Bloat Elimination
- [x] **Task 2.1**: Update `NextcloudManager.kt` to ensure `'skeletondirectory' => '',` is permanently configured and create `purgeAllDefaultDemoFiles()` that recursively wipes all default demo files (*Nextcloud Manual.pdf*, *Nextcloud intro.mp4*, demo images, sample Photos/Documents folders).
- [x] **Task 2.2**: Trigger Nextcloud `files:cleanup` and `files:scan --all` so that new and existing user accounts display a 100% clean root view with only their actual storage folders (`📁 Phone Storage`, `📁 SD Card`, `📁 USB Drive`).

## Stage 3: Cross-Storage File Copy & Move Engine
- [x] **Task 3.1**: Validate and harden cross-storage file copy/move between `Phone Storage` $\leftrightarrow$ `SD Card` $\leftrightarrow$ `USB Drive` in `Local.php` and `SdRootMediaStoreBridge.kt`.
- [x] **Task 3.2**: Verify that directory and file moves across partition boundaries (`EXDEV`) succeed cleanly with bit-for-bit checksum verification.

## Stage 4: Multi-Format Web UI Previews & Video Streaming
- [x] **Task 4.1**: Verify all preview providers (`PNG`, `JPEG`, `GIF`, `BMP`, `WebP`, `HEIC`, `Movie`, `PDF`, `TXT`, `MarkDown`) are active in Nextcloud configuration.
- [x] **Task 4.2**: Verify HTTP 206 Partial Content video streaming and Web UI sidebar storage widget responsiveness.

## Stage 5: Autonomous Dual-Device Verification
- [x] **Task 5.1**: Build fresh debug/release APK.
- [x] **Task 5.2**: Execute autonomous test suite on Samsung Galaxy S8 (`ce10171ab3e9620804`, Android 9).
- [x] **Task 5.3**: Execute autonomous test suite on Android 14 Emulator (`emulator-5554`, API 34).
- [x] **Task 5.4**: Synchronize codebase architecture map (`python scripts/generate_codebase_index.py`).
