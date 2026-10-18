package com.nextcloud.server

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton

/**
 * Clean, modern Material 3 bottom-sheet dialog that prompts the user
 * with a friendly 1-tap grant flow to unlock full root write and upload access
 * to external storage (MicroSD cards, USB OTG drives, external SSDs) via SAF.
 */
class SdCardAccessDialog(
    private val context: Context,
    private val storage: StorageMountManager.DetectedStorage,
    private val onGrantClicked: (storageUuid: String) -> Unit,
    private val onSkipped: ((storageUuid: String) -> Unit)? = null
) {

    fun show() {
        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_saf_access_grant, null)
        dialog.setContentView(view)

        val iconView = view.findViewById<ImageView>(R.id.safDialogIcon)
        val titleView = view.findViewById<TextView>(R.id.safDialogTitle)
        val messageView = view.findViewById<TextView>(R.id.safDialogMessage)
        val driveInfoView = view.findViewById<TextView>(R.id.safDialogDriveInfo)
        val grantButton = view.findViewById<MaterialButton>(R.id.safGrantButton)
        val skipButton = view.findViewById<MaterialButton>(R.id.safSkipButton)

        val cleanUuid = storage.id.removeSuffix("_root").removeSuffix("_uploads")
        val isSd = storage.displayName.contains("SD", ignoreCase = true)
        iconView.setImageResource(if (isSd) R.drawable.ic_sd_storage else R.drawable.ic_usb)

        val driveName = storage.displayName + if (storage.label.isNotBlank()) " [${storage.label}]" else ""
        titleView.text = "Enable Full $driveName Access"

        val totalGb = String.format(java.util.Locale.US, "%.1f GB", storage.totalBytes / (1024.0 * 1024.0 * 1024.0))
        val freeGb = String.format(java.util.Locale.US, "%.1f GB", storage.freeBytes / (1024.0 * 1024.0 * 1024.0))
        driveInfoView.text = "$driveName • $totalGb ($freeGb free)\nFormat: ${storage.fsType} • Path: ${storage.displayPath}"

        messageView.text = "To allow Nextcloud to read and write files directly on this storage drive, tap 'Grant Access' below.\n\nIn the system file picker that appears, simply tap 'USE THIS FOLDER' or 'ALLOW' at the bottom.\n\nThis is a one-time setup that persists permanently across reboots."

        grantButton.setOnClickListener {
            markDialogShown(context, cleanUuid)
            dialog.dismiss()
            onGrantClicked(cleanUuid)
        }

        skipButton.setOnClickListener {
            markDialogShown(context, cleanUuid)
            dialog.dismiss()
            onSkipped?.invoke(cleanUuid)
        }

        dialog.setOnDismissListener {
            markDialogShown(context, cleanUuid)
        }

        dialog.show()
    }

    companion object {
        private const val PREFS_NAME = "NextcloudSafPrefs"
        private const val KEY_SHOWN_PREFIX = "saf_dialog_prompted_"

        fun wasDialogShown(context: Context, storageUuid: String): Boolean {
            val cleanUuid = storageUuid.removeSuffix("_root").removeSuffix("_uploads")
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean("$KEY_SHOWN_PREFIX$cleanUuid", false)
        }

        fun markDialogShown(context: Context, storageUuid: String) {
            val cleanUuid = storageUuid.removeSuffix("_root").removeSuffix("_uploads")
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean("$KEY_SHOWN_PREFIX$cleanUuid", true).apply()
        }

        fun resetDialogShown(context: Context, storageUuid: String) {
            val cleanUuid = storageUuid.removeSuffix("_root").removeSuffix("_uploads")
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove("$KEY_SHOWN_PREFIX$cleanUuid").apply()
        }

        /**
         * Builds an Intent that opens the Android system SAF document tree picker
         * pre-navigated directly to the root of the designated storage volume.
         * Works across Android 8.0 (API 26) through Android 15 (API 35).
         */
        fun buildSafPickerIntent(context: Context, storageUuid: String): Intent {
            val cleanUuid = storageUuid.removeSuffix("_root").removeSuffix("_uploads")
            val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ (API 29+): Official volume-specific picker intent
                val matchingVolume = storageManager.storageVolumes.firstOrNull { it.uuid == cleanUuid }
                if (matchingVolume != null) {
                    val intent = matchingVolume.createOpenDocumentTreeIntent()
                    intent.addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                    )
                    return intent
                }
            }

            // Universal fallback for Android 8, 9, or unmapped volumes
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            val treeUri = Uri.parse("content://com.android.externalstorage.documents/tree/${Uri.encode("$cleanUuid:")}")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, treeUri)
            }

            intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
            return intent
        }
    }
}
