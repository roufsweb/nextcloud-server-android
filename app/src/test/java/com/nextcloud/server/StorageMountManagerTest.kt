package com.nextcloud.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for StorageMountManager classification, filesystem parsing,
 * and zero-click storage identification across Android versions (Android 8 to 15+).
 */
class StorageMountManagerTest {

    @Test
    fun testParseFilesystemTypeFromMountLine() {
        // FAT32 variations
        assertEquals("FAT32", StorageMountManager.parseFilesystemTypeFromMountLine(
            "/dev/block/vold/public:179,1 /mnt/media_rw/1234-5678 vfat rw,dirsync 0 0"
        ))
        assertEquals("FAT32", StorageMountManager.parseFilesystemTypeFromMountLine(
            "/dev/block/vold/public:8,1 /mnt/media_rw/ABCD-EF01 fat32 rw 0 0"
        ))
        assertEquals("FAT32", StorageMountManager.parseFilesystemTypeFromMountLine(
            "/dev/fuse /storage/1234-5678 fuse rw 0 0 (msdos)"
        ))

        // exFAT variations
        assertEquals("exFAT", StorageMountManager.parseFilesystemTypeFromMountLine(
            "/dev/block/vold/public:8,1 /mnt/media_rw/ABCD-EF01 exfat rw 0 0"
        ))
        assertEquals("exFAT", StorageMountManager.parseFilesystemTypeFromMountLine(
            "/dev/block/vold/public:8,1 /mnt/media_rw/ABCD-EF01 sdfat rw,fs=exfat 0 0"
        ))

        // NTFS variations (read-only indicator)
        assertEquals("NTFS", StorageMountManager.parseFilesystemTypeFromMountLine(
            "/dev/block/vold/public:8,1 /mnt/media_rw/ABCD-EF01 ntfs ro 0 0"
        ))
        assertEquals("NTFS", StorageMountManager.parseFilesystemTypeFromMountLine(
            "/dev/block/vold/public:8,1 /mnt/media_rw/ABCD-EF01 fuseblk ro 0 0"
        ))

        // ext4 / modern Linux filesystems
        assertEquals("ext4", StorageMountManager.parseFilesystemTypeFromMountLine(
            "/dev/block/vold/public:8,1 /mnt/media_rw/ABCD-EF01 ext4 rw 0 0"
        ))
        assertEquals("F2FS", StorageMountManager.parseFilesystemTypeFromMountLine(
            "/dev/block/vold/public:8,1 /mnt/media_rw/ABCD-EF01 f2fs rw 0 0"
        ))
    }

    @Test
    fun testIsSdCardClassification_MmcBlockDevice() {
        // StorageVolume ID contains 179 (MMC block device major) -> MicroSD Card
        assertTrue(StorageMountManager.isSdCardClassification(
            desc = "SanDisk SD card",
            volId = "public:179,0"
        ))
        assertTrue(StorageMountManager.isSdCardClassification(
            desc = "External Storage",
            volId = "public:179,1"
        ))
        assertTrue(StorageMountManager.isSdCardClassification(
            desc = "External Storage",
            volId = "mmcblk1p1"
        ))
    }

    @Test
    fun testIsSdCardClassification_UsbAndVirtualDisks() {
        // SCSI Mass Storage (major 8) -> USB OTG / SSD / HDD, NOT SD Card
        assertFalse(StorageMountManager.isSdCardClassification(
            desc = "Samsung Flash Drive",
            volId = "public:8,1"
        ))

        // Virtual loopback disk (major 7) -> USB OTG emulation, NOT SD Card
        assertFalse(StorageMountManager.isSdCardClassification(
            desc = "Virtual SD card",
            volId = "public:7,1"
        ))

        // Description explicitly mentions USB or OTG -> NOT SD Card
        assertFalse(StorageMountManager.isSdCardClassification(
            desc = "USB Storage",
            volId = null
        ))
        assertFalse(StorageMountManager.isSdCardClassification(
            desc = "OTG Flash Drive",
            volId = null
        ))
    }

    @Test
    fun testIsSdCardClassification_ProcMountsFallback() {
        val mountsWithMmc = listOf(
            "/dev/block/vold/public:179,1 /mnt/media_rw/DCBC-EC41 vfat rw 0 0"
        )
        assertTrue(StorageMountManager.isSdCardClassification(
            desc = "Removable Storage",
            volId = null,
            mountLines = mountsWithMmc,
            uuid = "DCBC-EC41"
        ))

        val mountsWithUsb = listOf(
            "/dev/block/vold/public:8,1 /mnt/media_rw/3990-1410 vfat rw 0 0"
        )
        assertFalse(StorageMountManager.isSdCardClassification(
            desc = "Removable Storage",
            volId = null,
            mountLines = mountsWithUsb,
            uuid = "3990-1410"
        ))
    }

    @Test
    fun testIsSdCardClassification_DescriptionFallback() {
        // When no low-level kernel major number is available, fall back to descriptive names
        assertTrue(StorageMountManager.isSdCardClassification(
            desc = "MicroSD Card",
            volId = null
        ))
        assertTrue(StorageMountManager.isSdCardClassification(
            desc = "SD card",
            volId = null
        ))
        assertTrue(StorageMountManager.isSdCardClassification(
            desc = "TF Card",
            volId = null
        ))
    }
}
