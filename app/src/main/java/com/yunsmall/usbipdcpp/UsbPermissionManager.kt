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
    }

    /*
     * PendingIntent utilisé par UsbManager.requestPermission().
     *
     * Android ajoute automatiquement :
     * - UsbManager.EXTRA_DEVICE
     * - UsbManager.EXTRA_PERMISSION_GRANTED
     *
     * FLAG_IMMUTABLE est suffisant pour cette utilisation.
     */
    private val permissionIntent: PendingIntent by lazy {
        val intent = Intent(ACTION_USB_PERMISSION)
            .setPackage(context.packageName)

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }

        PendingIntent.getBroadcast(
            context,
            0,
            intent,
            flags
        )
    }

    /*
     * Verrou unique protégeant la liste des demandes de permission
     * actuellement en attente.
     */
    private val lock = Any()

    /*
     * Une demande peut être en cours pour plusieurs périphériques USB
     * simultanément.
     *
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

    /*
     * Évite les doubles registerReceiver / unregisterReceiver.
     */
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

    /*
     * Appelé lorsqu'une véritable demande de permission USB
     * est envoyée à Android.
     *
     * Si la permission existe déjà, ce callback n'est pas appelé.
     */
    fun setOnPermissionRequestedListener(
        listener: ((UsbDevice) -> Unit)?
    ) {
        onPermissionRequested = listener
    }

    /*
     * Appelé lorsque la boîte de dialogue Android renvoie son résultat.
     *
     * granted = true  -> permission accordée
     * granted = false -> permission refusée
     */
    fun setOnPermissionResultListener(
        listener: ((UsbDevice, Boolean) -> Unit)?
    ) {
        onPermissionResult = listener
    }

    /*
     * Receiver privé destiné uniquement au résultat de
     * UsbManager.requestPermission().
     */
    private val permissionReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context,
                intent: Intent
            ) {
                if (intent.action != ACTION_USB_PERMISSION) {
                    return
                }

                val device =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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

                val granted =
                    intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED,
                        false
                    )

                device ?: return

                Log.d(
                    TAG,
                    "USB permission result for ${device.deviceName}: $granted"
                )

                /*
                 * Retirer le callback sous verrou.
                 *
                 * Le callback lui-même est exécuté hors du verrou afin
                 * d'éviter un blocage s'il déclenche une autre opération USB.
                 */
                val callback = synchronized(lock) {
                    pendingCallbacks.remove(
                        device.deviceName
                    )
                }

                /*
                 * Informe l'interface de l'état de la permission.
                 */
                onPermissionResult?.invoke(
                    device,
                    granted
                )

                /*
                 * Exécute ensuite l'action qui attendait cette permission.
                 *
                 * Cela pourra être :
                 * - simplement autoriser le périphérique ;
                 * - poursuivre automatiquement un bind USB/IP.
                 */
                callback?.invoke(
                    device,
                    granted
                )
            }
        }

    /*
     * Receiver des événements physiques USB :
     *
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
                         * Une permission USB Android est perdue lorsque le
                         * périphérique est physiquement débranché.
                         *
                         * Si une demande était encore en attente pour ce
                         * périphérique, elle ne doit pas rester bloquée.
                         */
                        val pendingCallback = synchronized(lock) {
                            pendingCallbacks.remove(
                                device.deviceName
                            )
                        }

                        /*
                         * Informe l'appelant que l'opération en attente ne
                         * peut plus aboutir.
                         */
                        pendingCallback?.invoke(
                            device,
                            false
                        )

                        /*
                         * Permet au Service de nettoyer un éventuel bind
                         * USB/IP correspondant à ce périphérique.
                         */
                        onDeviceDetached?.invoke(
                            device
                        )

                        /*
                         * Met à jour la liste des périphériques affichés.
                         */
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
                /*
                 * USB_DEVICE_ATTACHED et USB_DEVICE_DETACHED proviennent
                 * du système Android.
                 */
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

        /*
         * Ne conserver aucun callback lorsque l'Activity disparaît.
         *
         * Les callbacks peuvent capturer un ancien contexte ou un ancien
         * état Compose.
         */
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

    /*
     * Vérifie si Android autorise actuellement l'application
     * à accéder au périphérique.
     */
    fun hasPermission(
        device: UsbDevice
    ): Boolean {
        return usbManager.hasPermission(
            device
        )
    }

    /*
     * Indique si une demande de permission est déjà en cours
     * pour ce périphérique.
     */
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

        /*
         * Si Android nous a déjà accordé l'accès, aucune boîte de dialogue
         * supplémentaire n'est nécessaire.
         */
        if (usbManager.hasPermission(device)) {
            callback(
                device,
                true
            )

            return true
        }

        /*
         * Empêche plusieurs clics rapides d'ouvrir ou de remplacer
         * plusieurs demandes pour le même périphérique.
         */
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
                permissionIntent
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
             * Informe immédiatement l'appelant que la demande n'a pas
             * pu être lancée.
             */
            callback(
                device,
                false
            )
        }

        return true
    }

    /*
     * Ouvre le périphérique uniquement si Android a déjà accordé
     * la permission USB.
     */
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
