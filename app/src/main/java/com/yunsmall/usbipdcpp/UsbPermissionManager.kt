package com.yunsmall.usbipdcpp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

class UsbPermissionManager(
    private val context: Context,
    private val usbManager: UsbManager
) {
    companion object {
        private const val TAG = "UsbPermissionManager"

        const val ACTION_USB_PERMISSION =
            "com.yunsmall.usbipdcpp.USB_PERMISSION"

        private const val EXTRA_REQUEST_DEVICE_NAME =
            "com.yunsmall.usbipdcpp.EXTRA_REQUEST_DEVICE_NAME"
    }

    /*
     * Verrou unique protégeant les demandes de permission actuellement
     * en attente.
     */
    private val lock = Any()

    /*
     * Plusieurs périphériques peuvent demander une autorisation en parallèle.
     * La clé correspond au deviceName Android.
     */
    private val pendingCallbacks =
        mutableMapOf<String, (UsbDevice, Boolean) -> Unit>()

    private var onDeviceAttached: (() -> Unit)? = null

    private var onDeviceDetached:
        ((UsbDevice) -> Unit)? = null

    private var onPermissionRequested:
        ((UsbDevice) -> Unit)? = null

    private var onPermissionResult:
        ((UsbDevice, Boolean) -> Unit)? = null

    private var permissionReceiverRegistered = false
    private var deviceReceiverRegistered = false

    fun setOnDeviceAttachedListener(
        listener: (() -> Unit)?
    ) {
        onDeviceAttached = listener
    }

    fun setOnDeviceDetachedListener(
        listener: ((UsbDevice) -> Unit)?
    ) {
        onDeviceDetached = listener
    }

    fun setOnPermissionRequestedListener(
        listener: ((UsbDevice) -> Unit)?
    ) {
        onPermissionRequested = listener
    }

    fun setOnPermissionResultListener(
        listener: ((UsbDevice, Boolean) -> Unit)?
    ) {
        onPermissionResult = listener
    }

    /*
     * Crée un PendingIntent distinct pour chaque périphérique.
     *
     * FLAG_MUTABLE est nécessaire ici car UsbManager ajoute au résultat :
     * - UsbManager.EXTRA_DEVICE
     * - UsbManager.EXTRA_PERMISSION_GRANTED
     *
     * L'Intent est explicitement limité au package de l'application, ce qui
     * est requis pour les PendingIntent mutables sur les versions Android
     * récentes.
     */
    private fun createPermissionIntent(
        device: UsbDevice
    ): PendingIntent {
        val intent =
            Intent(ACTION_USB_PERMISSION)
                .setPackage(context.packageName)
                .putExtra(
                    EXTRA_REQUEST_DEVICE_NAME,
                    device.deviceName
                )

        val flags =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.S
            ) {
                PendingIntent.FLAG_MUTABLE or
                    PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

        /*
         * requestCode distinct par deviceName pour éviter qu'une demande
         * d'un périphérique remplace celle d'un autre.
         */
        return PendingIntent.getBroadcast(
            context,
            device.deviceName.hashCode(),
            intent,
            flags
        )
    }

    /*
     * Receiver privé du résultat de UsbManager.requestPermission().
     */
    private val permissionReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context,
                intent: Intent
            ) {
                if (
                    intent.action !=
                    ACTION_USB_PERMISSION
                ) {
                    return
                }

                /*
                 * Chemin normal : Android fournit EXTRA_DEVICE.
                 */
                var device =
                    if (
                        Build.VERSION.SDK_INT >=
                        Build.VERSION_CODES.TIRAMISU
                    ) {
                        intent.getParcelableExtra(
                            UsbManager.EXTRA_DEVICE,
                            UsbDevice::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(
                            UsbManager.EXTRA_DEVICE
                        )
                    }

                /*
                 * Chemin de secours :
                 * certaines implémentations constructeur peuvent fournir un
                 * résultat incomplet. Le deviceName ajouté à notre Intent
                 * permet alors de retrouver le périphérique encore connecté.
                 */
                if (device == null) {
                    val requestedDeviceName =
                        intent.getStringExtra(
                            EXTRA_REQUEST_DEVICE_NAME
                        )

                    if (
                        !requestedDeviceName.isNullOrEmpty()
                    ) {
                        device =
                            usbManager.deviceList[
                                requestedDeviceName
                            ]
                    }
                }

                val granted =
                    intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED,
                        false
                    )

                if (device == null) {
                    /*
                     * Ne jamais laisser l'interface bloquée indéfiniment.
                     *
                     * Si Android n'a fourni aucun périphérique exploitable,
                     * supprimer les demandes devenues impossibles à associer.
                     */
                    Log.e(
                        TAG,
                        "USB permission result received without device"
                    )

                    val callbacks =
                        synchronized(lock) {
                            val copy =
                                pendingCallbacks.toMap()

                            pendingCallbacks.clear()
                            copy
                        }

                    callbacks.forEach {
                        (deviceName, callback) ->

                        val pendingDevice =
                            usbManager.deviceList[
                                deviceName
                            ]

                        if (pendingDevice != null) {
                            onPermissionResult?.invoke(
                                pendingDevice,
                                false
                            )

                            callback.invoke(
                                pendingDevice,
                                false
                            )
                        }
                    }

                    return
                }

                Log.d(
                    TAG,
                    "USB permission result for ${device.deviceName}: $granted"
                )

                val callback =
                    synchronized(lock) {
                        pendingCallbacks.remove(
                            device.deviceName
                        )
                    }

                onPermissionResult?.invoke(
                    device,
                    granted
                )

                callback?.invoke(
                    device,
                    granted
                )
            }
        }

    /*
     * Receiver des événements physiques USB :
     * - branchement
     * - débranchement
     */
    private val deviceReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context,
                intent: Intent
            ) {
                when (intent.action) {

                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        Log.d(
                            TAG,
                            "USB device attached"
                        )

                        onDeviceAttached?.invoke()
                    }

                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        val device =
                            if (
                                Build.VERSION.SDK_INT >=
                                Build.VERSION_CODES.TIRAMISU
                            ) {
                                intent.getParcelableExtra(
                                    UsbManager.EXTRA_DEVICE,
                                    UsbDevice::class.java
                                )
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(
                                    UsbManager.EXTRA_DEVICE
                                )
                            }

                        device ?: return

                        Log.d(
                            TAG,
                            "USB device detached: ${device.deviceName}"
                        )

                        /*
                         * Une permission USB Android disparaît lorsque le
                         * périphérique est physiquement débranché.
                         *
                         * Si une demande était encore en attente, elle ne doit
                         * pas rester bloquée.
                         */
                        val pendingCallback =
                            synchronized(lock) {
                                pendingCallbacks.remove(
                                    device.deviceName
                                )
                            }

                        pendingCallback?.invoke(
                            device,
                            false
                        )

                        onDeviceDetached?.invoke(
                            device
                        )

                        onDeviceAttached?.invoke()
                    }
                }
            }
        }

    fun registerReceiver() {
        /*
         * Receiver du résultat de permission USB.
         */
        if (!permissionReceiverRegistered) {
            val permissionFilter =
                IntentFilter(
                    ACTION_USB_PERMISSION
                )

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.TIRAMISU
            ) {
                context.registerReceiver(
                    permissionReceiver,
                    permissionFilter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                context.registerReceiver(
                    permissionReceiver,
                    permissionFilter
                )
            }

            permissionReceiverRegistered = true
        }

        /*
         * Receiver des événements USB système.
         */
        if (!deviceReceiverRegistered) {
            val deviceFilter =
                IntentFilter().apply {
                    addAction(
                        UsbManager.ACTION_USB_DEVICE_ATTACHED
                    )
                    addAction(
                        UsbManager.ACTION_USB_DEVICE_DETACHED
                    )
                }

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.TIRAMISU
            ) {
                context.registerReceiver(
                    deviceReceiver,
                    deviceFilter,
                    Context.RECEIVER_EXPORTED
                )
            } else {
                context.registerReceiver(
                    deviceReceiver,
                    deviceFilter
                )
            }

            deviceReceiverRegistered = true
        }
    }

    fun unregisterReceiver() {
        if (permissionReceiverRegistered) {
            try {
                context.unregisterReceiver(
                    permissionReceiver
                )
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Error unregistering permission receiver",
                    e
                )
            } finally {
                permissionReceiverRegistered = false
            }
        }

        if (deviceReceiverRegistered) {
            try {
                context.unregisterReceiver(
                    deviceReceiver
                )
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Error unregistering device receiver",
                    e
                )
            } finally {
                deviceReceiverRegistered = false
            }
        }

        synchronized(lock) {
            pendingCallbacks.clear()
        }

        onDeviceAttached = null
        onDeviceDetached = null
        onPermissionRequested = null
        onPermissionResult = null
    }

    fun getDeviceList(): Map<String, UsbDevice> {
        return usbManager.deviceList
    }

    fun hasPermission(
        device: UsbDevice
    ): Boolean {
        return usbManager.hasPermission(
            device
        )
    }

    fun isPermissionRequestPending(
        device: UsbDevice
    ): Boolean {
        return synchronized(lock) {
            pendingCallbacks.containsKey(
                device.deviceName
            )
        }
    }

    /**
     * Demande à Android l'autorisation d'accéder au périphérique USB.
     *
     * @return true :
     * - une nouvelle demande a été lancée ;
     * - ou la permission existait déjà et le callback a été exécuté.
     *
     * @return false :
     * - une demande est déjà en cours pour ce même périphérique.
     */
    fun requestPermission(
        device: UsbDevice,
        callback: (UsbDevice, Boolean) -> Unit
    ): Boolean {

        if (
            usbManager.hasPermission(
                device
            )
        ) {
            callback(
                device,
                true
            )

            return true
        }

        synchronized(lock) {
            if (
                pendingCallbacks.containsKey(
                    device.deviceName
                )
            ) {
                return false
            }

            pendingCallbacks[
                device.deviceName
            ] = callback
        }

        try {
            usbManager.requestPermission(
                device,
                createPermissionIntent(
                    device
                )
            )

            Log.d(
                TAG,
                "USB permission requested for ${device.deviceName}"
            )

            onPermissionRequested?.invoke(
                device
            )
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Unable to request USB permission for ${device.deviceName}",
                e
            )

            synchronized(lock) {
                pendingCallbacks.remove(
                    device.deviceName
                )
            }

            /*
             * Informe aussi l'interface du résultat afin qu'elle puisse
             * immédiatement retirer un éventuel indicateur d'attente.
             */
            onPermissionResult?.invoke(
                device,
                false
            )

            callback(
                device,
                false
            )
        }

        return true
    }

    fun openDevice(
        device: UsbDevice
    ): UsbDeviceConnection? {
        return if (
            usbManager.hasPermission(
                device
            )
        ) {
            usbManager.openDevice(
                device
            )
        } else {
            null
        }
    }
}
