package com.nextcloud.server

import android.content.Context
import java.io.File

object BinaryHelper {

    /**
     * Resolves the executable path for the PHP CLI binary.
     * Checks `nativeLibraryDir` first (libphp.so), which is extracted by Android PackageManager
     * and granted SELinux execution permissions (rx) on all Android versions (Android 8 - 17).
     * Falls back to `files/bin/php` if present.
     */
    fun getPhpBinary(context: Context): String {
        val nativeBin = File(context.applicationInfo.nativeLibraryDir, "libphp.so")
        if (nativeBin.exists()) {
            return nativeBin.absolutePath
        }
        val filesBin = File(context.filesDir, "bin/php")
        if (filesBin.exists()) {
            return filesBin.absolutePath
        }
        return nativeBin.absolutePath
    }

    /**
     * Resolves the executable path for the PHP-FPM FastCGI server binary.
     */
    fun getPhpFpmBinary(context: Context): String {
        val nativeBin = File(context.applicationInfo.nativeLibraryDir, "libphp-fpm.so")
        if (nativeBin.exists()) {
            return nativeBin.absolutePath
        }
        val filesBin = File(context.filesDir, "bin/php-fpm")
        if (filesBin.exists()) {
            return filesBin.absolutePath
        }
        val fallbackPhp = File(context.applicationInfo.nativeLibraryDir, "libphp.so")
        if (fallbackPhp.exists()) {
            return fallbackPhp.absolutePath
        }
        return nativeBin.absolutePath
    }

    /**
     * Resolves the executable path for the Nginx web server binary.
     */
    fun getNginxBinary(context: Context): String {
        val nativeBin = File(context.applicationInfo.nativeLibraryDir, "libnginx.so")
        if (nativeBin.exists()) {
            return nativeBin.absolutePath
        }
        val filesBin = File(context.filesDir, "bin/nginx")
        if (filesBin.exists()) {
            return filesBin.absolutePath
        }
        return nativeBin.absolutePath
    }

    /**
     * Resolves the executable path for the SQLite3 CLI binary.
     */
    fun getSqlite3Binary(context: Context): String {
        val nativeBin = File(context.applicationInfo.nativeLibraryDir, "libsqlite3_cli.so")
        if (nativeBin.exists()) {
            return nativeBin.absolutePath
        }
        val filesBin = File(context.filesDir, "bin/sqlite3")
        if (filesBin.exists()) {
            return filesBin.absolutePath
        }
        return nativeBin.absolutePath
    }

    /**
     * Constructs the LD_LIBRARY_PATH environment string if custom fallback directories
     * (files/lib or files/bin) exist. If all libraries reside in nativeLibraryDir (standard APK install),
     * returns null to let the system dynamic linker and translation thunks resolve them naturally.
     */
    fun getLdLibraryPath(context: Context): String? {
        val filesLib = File(context.filesDir, "lib")
        val filesBin = File(context.filesDir, "bin")
        val customPaths = mutableListOf<String>()
        if (filesLib.exists() && filesLib.list()?.isNotEmpty() == true) {
            customPaths.add(filesLib.absolutePath)
        }
        if (filesBin.exists() && filesBin.list()?.isNotEmpty() == true) {
            customPaths.add(filesBin.absolutePath)
        }
        if (customPaths.isEmpty()) {
            return null
        }
        customPaths.add(context.applicationInfo.nativeLibraryDir)
        return customPaths.joinToString(":")
    }
}
