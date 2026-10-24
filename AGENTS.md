# Nextcloud Server Android - Master Architectural Guidelines & Swarm Manifest

This document serves as the master architectural manifest and operational index for Nextcloud Server Android. All agents and developers must strictly adhere to these rules and the modular rule swarm located in `.agents/rules/`.

---

## 1. Project Overview & Architecture
This application runs a self-hosted, full-featured **Nextcloud Server** directly on Android devices (`arm64-v8a` / ARM 64-bit).

* **Package Name**: `com.nextcloud.server`
* **App Layer**: Kotlin, Android Architecture Components (Foreground Service, WorkManager, Material 3, Coroutines/Flow).
* **Server Stack**:
  * **Nginx** (Reverse Proxy & Web Server on customizable port, e.g. `8000` / `8080`).
  * **PHP-FPM** (FastCGI Process Manager on `127.0.0.1:9000`).
  * **PHP CLI** (`bin/php`) for running `nextcloud/cron.php` background tasks and `occ`.
  * **SQLite** embedded database (`pdo_sqlite`) with WAL mode.
  * **Nextcloud Core** extracted into `/data/data/com.nextcloud.server/files/nextcloud/`.

---

## 2. Modular Rule Swarm Manifest
Detailed domain-specific guidelines are partitioned into modular rule files within `.agents/rules/`:

1. [01-architecture-and-compatibility.md](file:///e:/rouf/android-apk/nextcloud-server-android/.agents/rules/01-architecture-and-compatibility.md): Clean Architecture, MVI/MVVM, `minSdk 26`, `compileSdk 34`, strict `arm64-v8a` ELF linker (`/system/bin/linker64`), and dynamic library resolution.
2. [02-service-lifecycle-and-stability.md](file:///e:/rouf/android-apk/nextcloud-server-android/.agents/rules/02-service-lifecycle-and-stability.md): Android Foreground Service lifecycle, `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`, `NotificationChannel`, `RECEIVER_NOT_EXPORTED`, and continuous `PowerManager.PARTIAL_WAKE_LOCK`.
3. [03-universal-storage-and-media.md](file:///e:/rouf/android-apk/nextcloud-server-android/.agents/rules/03-universal-storage-and-media.md): Native internal folder presentation (`📁 Internal Storage`, `📁 MicroSD Card`, `📁 USB Storage`), zero-click auto-discovery, Android 8–15+ write strategies, multi-user `/NextcloudUsers/<username>/` isolation, and cross-storage `MOVE` (`EXDEV` handling).
4. [04-server-stack-and-security.md](file:///e:/rouf/android-apk/nextcloud-server-android/.agents/rules/04-server-stack-and-security.md): Nginx FastCGI chunking, PHP-FPM memory limits, PHP2Shell (`session.upload_progress.enabled = 0`) & HTTPoxy (`fastcgi_param HTTP_PROXY ""`) mitigations, native GD library, and SQLite `0700` app-data isolation.
5. [05-codebase-navigation-and-guardrails.md](file:///e:/rouf/android-apk/nextcloud-server-android/.agents/rules/05-codebase-navigation-and-guardrails.md): Token-efficient architecture map ([CODEBASE_INDEX.md](file:///e:/rouf/android-apk/nextcloud-server-android/.agents/skills/codebase-architecture-map/CODEBASE_INDEX.md)), connection graph ([CONNECTION_GRAPH.md](file:///e:/rouf/android-apk/nextcloud-server-android/.agents/skills/codebase-architecture-map/CONNECTION_GRAPH.md)), sequential edit chains, and re-indexing protocol.
6. [06-testing-and-clean-install.md](file:///e:/rouf/android-apk/nextcloud-server-android/.agents/rules/06-testing-and-clean-install.md): Strict fresh install protocol (`adb uninstall`), `android:allowBackup="false"`, dual-device verification matrix (Physical Galaxy S8 + Android 14 Emulator), zero skeleton bloat, and 24-feature test standard.
7. [07-ui-ux-simplicity.md](file:///e:/rouf/android-apk/nextcloud-server-android/.agents/rules/07-ui-ux-simplicity.md): Material Design 3 simplicity, elimination of wordy AI subtitles, clean tonal cards, educational storage protection dialog, and zero blocking popups.

---

## 3. Build & Maintenance Commands
* Build Debug APK: `.\gradlew.bat assembleDebug`
* Build Release APK: `.\gradlew.bat assembleRelease`
* Check Gradle tasks: `.\gradlew.bat tasks`
* Refresh Architecture Index & Connection Graph: `python scripts/generate_codebase_index.py`
* Run 21-Feature Autonomous Test Suite: `python scratch/run_full_autonomous_tests.py <device_id> <port>`
* Run Web UI, DOM & Preview Test Suite: `python scratch/test_webui_and_previews.py <device_id> <port>`

---

## 4. Swarm Engineering Roles & Skill Mapping
Engineering responsibilities are assigned across specialized autonomous agent roles:
* **Solutions & Environment Alignment Architect**: Profiles hardware/OS limits and aligns them with true user needs.
* **Resource & Task Orchestration Lead**: Manages capacity, decomposes initiatives, and routes tasks without waste.
* **Codebase Knowledge Steward (SME)**: Memorizes codebase layout, component relations, and sequential edit chains.
* **Feature Integration & Conflict Guardian (Merge Czar)**: Guards trunk against cross-feature collisions and broken dependencies.
* **Customer-End Quality & Autonomous Verification Lead**: Guarantees all features work in customers' hands across real hardware and emulators.
* **Staff Mobile Architect**: Enforces system modularization, Clean Architecture, and platform compatibility.
* **Senior Android Tech Lead**: Oversees foreground services, background execution, and core domain logic.
* **Systems & Native Engineer**: Manages NDK binaries, Nginx/PHP configs, SQLite WAL, and security hardening.
* **Product Android UI/UX Engineer**: Crafts Material 3 layouts, micro-animations, and clean, unbloated cards.
* **Mobile Platform & DevEx Engineer**: Maintains build speed, CI pipelines, and developer tooling.
* **Product Manager & Release Train Lead**: Defines PRDs, user journeys, time-based release trains, and canary rollouts.
