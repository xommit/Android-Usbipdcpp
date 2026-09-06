package com.yunsmall.usbipdcpp

import android.hardware.usb.UsbDevice

/**
 * Known USB devices that should not be offered for USB/IP sharing.
 *
 * IMPORTANT:
 * - Entries must always use the exact VID + PID pair.
 * - Do not blacklist a whole vendor unless there is an exceptional,
 *   well-documented reason.
 * - Keep this list small and evidence-based.
 * - Prefer standard USB class detection (Hub, Billboard, etc.) whenever
 *   a device can be identified reliably without VID/PID matching.
 *
 * The list is intentionally empty by default.
 */
data class UsbDeviceBlacklistEntry(
    val vendorId: Int,
    val productId: Int,
    val reason: String,
    val reference: String? = null
)

object UsbDeviceBlacklist {

    /*
     * Add confirmed incompatible devices here.
     *
     * Example:
     *
     * UsbDeviceBlacklistEntry(
     *     vendorId = 0x1234,
     *     productId = 0x5678,
     *     reason = "Known to fail USB/IP descriptor handling",
     *     reference = "https://github.com/.../issues/123"
     * )
     *
     * Do not leave unverified/example entries enabled.
     */
    private val entries: List<UsbDeviceBlacklistEntry> = emptyList()

    fun find(device: UsbDevice): UsbDeviceBlacklistEntry? {
        return entries.firstOrNull { entry ->
            entry.vendorId == device.vendorId &&
                entry.productId == device.productId
        }
    }

    fun isBlacklisted(device: UsbDevice): Boolean {
        return find(device) != null
    }
}
