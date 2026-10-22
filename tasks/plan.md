# Implementation Plan: Universal External Storage & Root SD Card Engine (Android 8–17)

## 1. Overview
Build and deploy a universal, zero-root external storage architecture for Nextcloud Server on Android. This enables full read, write, create, and delete access across the entire root of any connected MicroSD card or USB OTG storage (`/storage/<UUID>/`) seamlessly across Android 8.0 through Android 17.

The architecture eliminates manual folder picker prompts using a two-tier strategy:
1. **Tier 1 (Android 8.0 – 10 / API 26–29):** Direct native POSIX filesystem access via `WRITE_EXTERNAL_STORAGE` and `requestLegacyExternalStorage="true"`.
2. **Tier 2 (Android 11 – 17 / API 30–36+):** `MANAGE_EXTERNAL_STORAGE` ("All files access") combined with a native `libsaf_shim.so` (or Kotlin Unix socket broker using `MediaStore.Files` + SAF fallback for dotfiles like `.ocdata`).

---

## 2. Architecture & Design Decisions

### A. Dual-Engine POSIX & Broker Bridge
* **Android 8–10 (Galaxy S8 testbed):**
  * Galaxy S8 runs Android 9 (API 28). With `WRITE_EXTERNAL_STORAGE`, the Linux kernel assigns GID `sdcard_rw` (1015), allowing raw POSIX C calls (`open`, `mkdir`, `unlink`, `rename`) directly to `/storage/<UUID>/`.
  * No shim or socket overhead is needed; operations run at 100% native kernel speed.
* **Android 11–17:**
  * Uses `MANAGE_EXTERNAL_STORAGE`.
  * For raw POSIX writes blocked by Android 11+ FUSE mount masks:
    * Standard files are mediated via `MediaStore.Files.getContentUri(uuid)` obtaining detached Linux file descriptors (`pfd.detachFd()`) passed to native processes via `SCM_RIGHTS`.
    * Dotfiles (`.ocdata`, `.htaccess`, `.part`) and atomic renames use the SAF URI broker.

### B. Mount Point Flexibility: Bare Root vs. Dedicated Subfolder
* Provide full support for mounting the **entire bare root** of the SD card (`/storage/<UUID>/`) directly into Nextcloud's `files_external`, so all existing music, photos, documents, and videos on the user's SD card immediately appear in Nextcloud.
* Alternatively allow dedicated folder (`/storage/<UUID>/Nextcloud/`) if preferred, with auto-detection.

---

## 3. Phased Task Breakdown

### Phase 1: Clean Deployment & Baseline on Samsung Galaxy S8
* **Task 1:** Clean uninstall of outdated Oct 19 build on Galaxy S8, install fresh `v1.1.0` release APK, verify initial extraction, PHP runtime, and foreground service.
* **Task 2:** Verify and log native POSIX read/write access to Galaxy S8 SD card (`/storage/DCBC-EC41/`) under the app's UID.

### Phase 2: Core Storage Engine Upgrade (`StorageMountManager`)
* **Task 3:** Implement bare SD card root mounting support in `StorageMountManager.kt` (mounting `/storage/<UUID>/` directly into `files_external`).
* **Task 4:** Add `.ocdata` and probe validation for SD card root so Nextcloud accepts it as external storage without throwing storage exceptions.

### Phase 3: Android 11–17 MediaStore/SAF Broker Implementation
* **Task 5:** Implement `SdRootMediaStoreBridge.kt` and Unix Domain Socket server in Kotlin (`LocalServerSocket`) supporting `SCM_RIGHTS` fd passing.
* **Task 6:** Build dotfile fallback handler in the broker for `.ocdata`, `.part`, and `.htaccess`.

### Phase 4: Integration, Testing & Live Verification
* **Task 7:** Deploy updated build to Galaxy S8, mount SD Card root (`DCBC-EC41`), and verify in Nextcloud OCC (`files_external:list` and `files:scan`).
* **Task 8:** Test file creation, upload, deletion, and gallery thumbnail generation on the physical SD card.
* **Task 9:** Inspect all runtime logs (`php_error.log`, `nginx_error.log`, `logcat`) and fix any edge cases or regressions.

---

## 4. Risks & Mitigations

| Risk | Impact | Mitigation |
| :--- | :--- | :--- |
| **Dotfile rejection in MediaStore (Android 11+)** | High | Dotfiles starting with `.` bypass MediaStore and use cached SAF tree or direct app-scoped fallback. |
| **Nextcloud scan performance on large SD cards** | Medium | Use background debounced scan queue (`files:scan --path=...`) with non-blocking worker threads. |
| **FAT32/exFAT permission mask differences** | Medium | Force permissive umask handling and fallback file probes before reporting write failure. |

---

## 5. Verification Checklist
- [ ] Fresh app running on Galaxy S8 (Android 9).
- [ ] Galaxy S8 SD card root (`/storage/DCBC-EC41`) mounted in Nextcloud.
- [ ] Read, write, and delete tests pass directly on the SD card.
- [ ] Nextcloud files scan discovers existing files (e.g. `MP3_Collection`, `Maroon5`, `Photos`).
- [ ] Web UI / HTTP API serves SD card files with HTTP 200.
