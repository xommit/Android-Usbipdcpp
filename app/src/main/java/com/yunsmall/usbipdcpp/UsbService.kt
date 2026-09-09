package com.yunsmall.usbipdcpp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

enum class VirtualOpticalMountResult {
    Success,
    InvalidImage,
    Failed
}

class UsbService : Service() {

    companion object {
        private const val TAG = "UsbService"
        private const val NOTIFICATION_CHANNEL_ID = "usbipd_service"
        private const val NOTIFICATION_ID = 1
        private const val OPTICAL_PREFERENCES = "virtual_optical_drive"
        private const val OPTICAL_URI_KEY = "media_uri"
        private const val OPTICAL_NAME_KEY = "media_name"
    }

    private val binder = UsbBinder()

    // Génération du moniteur réseau. Chaque démarrage/arrêt invalide le
    // moniteur précédent sans interruption forcée de thread.
    private val listenMonitorGeneration =
        java.util.concurrent.atomic.AtomicInteger(0)

    // 保存活跃的USB连接
    private data class DeviceInfo(
        val connection: UsbDeviceConnection,
        val fd: Int,
        val busid: String
    )
    // 写操作都在 nativeDispatcher 单线程，但 UI 线程会读（boundDeviceNames/getBusid），
    // 用 ConcurrentHashMap：弱一致迭代不会抛 ConcurrentModificationException
    private val activeDevices = java.util.concurrent.ConcurrentHashMap<String, DeviceInfo>()

    // 写在 nativeDispatcher 线程、读在 UI 线程，需要 @Volatile 保证可见性
    @Volatile
    var serverRunning = false
        private set

    @Volatile
    var port = 3240
        private set

    // Adresse IPv4 réellement utilisée par le serveur natif.
    // 0.0.0.0 conserve le comportement historique : écoute sur toutes les interfaces.
    @Volatile
    var listenAddress = "0.0.0.0"
        private set

    @Volatile
    var virtualOpticalMediaMounted = false
        private set

    @Volatile
    var virtualOpticalMediaName: String? = null
        private set

    @Volatile
    var virtualOpticalMediaSize = 0L
        private set

    @Volatile
    var virtualOpticalMediaUri: String? = null
        private set

    @Volatile
    var virtualOpticalBusid: String? = null
        private set

    val boundDeviceNames: Set<String>
        // 返回拷贝而非 keys 视图：UI 线程迭代时 native 线程可能正在改 map，
        // 视图迭代会抛 ConcurrentModificationException
        get() = activeDevices.keys.toSet()

    fun getBusid(deviceName: String): String? = activeDevices[deviceName]?.busid

    inner class UsbBinder : Binder() {
        fun getService(): UsbService = this@UsbService
    }

    // native 层初始化状态：init 失败（库加载失败等）时后续绑定/启服全部
    // 不可用，暴露给 UI 层检查
    @Volatile
    var nativeReady = false
        private set

    // 服务自注册的拔出监听：Activity 销毁（用户退出 UI）后服务仍在前台运行，
    // 拔出事件不能依赖 UI 层的接收器，否则 activeDevices 残留、连接无法清理
    private val deviceDetachedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            device?.let { usbDevice ->
                // onReceive 在主线程，handleDeviceDetached 是挂起函数且要切
                // nativeDispatcher，直接 runBlocking 会卡主线程，用后台线程执行
                Thread {
                    runBlocking { handleDeviceDetached(usbDevice.deviceName) }
                }.start()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        nativeReady = UsbIpNative.init()
        if (!nativeReady) {
            Log.e(TAG, "Native initialization failed, USB/IP features unavailable")
        } else {
            virtualOpticalBusid = UsbIpNative.getVirtualOpticalBusidNative()
            restoreVirtualOpticalMedia()
        }

        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(deviceDetachedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(deviceDetachedReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()

        // Empêche un moniteur d'adresse en cours de continuer après la
        // destruction du Service.
        listenMonitorGeneration.incrementAndGet()
        try {
            unregisterReceiver(deviceDetachedReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Receiver already unregistered", e)
        }
        // native 清理含 join 线程等耗时操作，在主线程 runBlocking 会卡 ANR，
        // 放到后台线程执行（进程退出时系统回收，不保证执行完）。
        // closeAllDevices 也放在 native 线程内：销毁期间 MainActivity 的协程
        // 可能仍在 nativeDispatcher 上执行 bindDevice，map 写必须同线程串行。
        // 闭包持有 this 是强引用：JVM 下 Service 对象在清理线程运行期间不会被
        // GC，不存在 C++ 那样的 use-after-free
        Thread {
            UsbIpNative.runOnNativeThread {
                if (UsbIpNative.isServerRunning()) {
                    UsbIpNative.stopServer()
                }
                // 这里调的是 native 的 stopServer（external），不会清理
                // Kotlin 层连接，closeAllDevices 只执行一次，无重复
                closeAllDevices()
                UsbIpNative.release()
            }
        }.start()
    }

    private fun resolveVirtualOpticalDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    return@use null
                }

                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column < 0) {
                    null
                } else {
                    cursor.getString(column)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve virtual optical media name", e)
            null
        }
    }

    private fun rememberVirtualOpticalUri(uri: Uri, displayName: String?) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Persistent read permission unavailable for optical media", e)
        }

        getSharedPreferences(OPTICAL_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(OPTICAL_URI_KEY, uri.toString())
            .putString(OPTICAL_NAME_KEY, displayName)
            .apply()
    }

    private fun restoreVirtualOpticalMedia() {
        val storedUri =
            getSharedPreferences(OPTICAL_PREFERENCES, Context.MODE_PRIVATE)
                .getString(OPTICAL_URI_KEY, null)
                ?: return

        virtualOpticalMediaUri = storedUri
        virtualOpticalMediaName =
            getSharedPreferences(OPTICAL_PREFERENCES, Context.MODE_PRIVATE)
                .getString(OPTICAL_NAME_KEY, null)

        Thread {
            runBlocking {
                if (
                    mountVirtualOptical(Uri.parse(storedUri), persistUri = false) !=
                    VirtualOpticalMountResult.Success
                ) {
                    Log.w(TAG, "Failed to restore virtual optical media")
                }
            }
        }.start()
    }

    private fun hasUnsupportedOpticalExtension(displayName: String?): Boolean {
        val name = displayName?.trim().orEmpty()
        if (name.isEmpty()) {
            return false
        }

        val dot = name.lastIndexOf('.')
        return dot > 0 && !name.endsWith(".iso", ignoreCase = true)
    }

    suspend fun mountVirtualOptical(
        uri: Uri,
        persistUri: Boolean = true
    ): VirtualOpticalMountResult {
        if (!nativeReady) {
            return VirtualOpticalMountResult.Failed
        }

        return withContext(UsbIpNative.nativeDispatcher) {
            val resolvedDisplayName = resolveVirtualOpticalDisplayName(uri)
            if (hasUnsupportedOpticalExtension(resolvedDisplayName)) {
                Log.w(
                    TAG,
                    "Rejected virtual optical media with unsupported filename: $resolvedDisplayName"
                )
                return@withContext VirtualOpticalMountResult.InvalidImage
            }

            val displayName = resolvedDisplayName ?: uri.lastPathSegment
            val nativeResult = try {
                contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                    UsbIpNative.mountVirtualOpticalNative(descriptor.fd)
                } ?: UsbIpNative.VirtualOpticalMountResult.MOUNT_FAILED
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open virtual optical media", e)
                UsbIpNative.VirtualOpticalMountResult.MOUNT_FAILED
            }

            val result = when (nativeResult) {
                UsbIpNative.VirtualOpticalMountResult.SUCCESS ->
                    VirtualOpticalMountResult.Success
                UsbIpNative.VirtualOpticalMountResult.INVALID_IMAGE ->
                    VirtualOpticalMountResult.InvalidImage
                else ->
                    VirtualOpticalMountResult.Failed
            }

            if (result == VirtualOpticalMountResult.Success) {
                if (persistUri) {
                    rememberVirtualOpticalUri(uri, displayName)
                }
                virtualOpticalMediaUri = uri.toString()
                virtualOpticalMediaName = displayName
                virtualOpticalMediaMounted = true
                virtualOpticalMediaSize =
                    UsbIpNative.getVirtualOpticalMediaSizeNative()
            }

            result
        }
    }

    suspend fun remountVirtualOptical(): VirtualOpticalMountResult {
        val storedUri = virtualOpticalMediaUri
            ?: return VirtualOpticalMountResult.Failed
        return mountVirtualOptical(
            Uri.parse(storedUri),
            persistUri = false
        )
    }

    suspend fun ejectVirtualOptical() {
        if (!nativeReady) {
            return
        }

        withContext(UsbIpNative.nativeDispatcher) {
            UsbIpNative.ejectVirtualOpticalNative()
            virtualOpticalMediaMounted = false
            virtualOpticalMediaSize = 0L
        }
    }

    suspend fun refreshVirtualOpticalState() {
        if (!nativeReady) {
            virtualOpticalMediaMounted = false
            virtualOpticalMediaSize = 0L
            return
        }

        withContext(UsbIpNative.nativeDispatcher) {
            virtualOpticalMediaMounted =
                UsbIpNative.isVirtualOpticalMediaMountedNative()
            virtualOpticalMediaSize =
                if (virtualOpticalMediaMounted) {
                    UsbIpNative.getVirtualOpticalMediaSizeNative()
                } else {
                    0L
                }
            if (virtualOpticalBusid == null) {
                virtualOpticalBusid =
                    UsbIpNative.getVirtualOpticalBusidNative()
            }
        }
    }

    /**
     * Compatibilité avec l'ancien appel : écoute sur toutes les interfaces IPv4.
     */
    suspend fun startServer(port: Int): Boolean {
        return startServer(port, "0.0.0.0")
    }

    /**
     * Démarre le serveur USB/IP sur une adresse IPv4 locale précise.
     *
     * 0.0.0.0 conserve le comportement historique et écoute sur toutes les interfaces.
     */
    suspend fun startServer(port: Int, listenAddress: String): Boolean {
        if (serverRunning) return true

        val normalizedAddress = listenAddress
            .trim()
            .ifEmpty { "0.0.0.0" }

        return withContext(UsbIpNative.nativeDispatcher) {
            val success = UsbIpNative.startServer(port, normalizedAddress)
            if (success) {
                this@UsbService.port = port
                this@UsbService.listenAddress = normalizedAddress
                serverRunning = true
                updateNotification()
                startListenAddressMonitor(normalizedAddress)
            }
            success
        }
    }

    suspend fun stopServer() {
        // Invalide immédiatement le moniteur associé au démarrage courant.
        listenMonitorGeneration.incrementAndGet()

        withContext(UsbIpNative.nativeDispatcher) {
            UsbIpNative.stopServer()
            serverRunning = false
            // 关设备也在 native 线程内，避免 UI 线程迭代/清空 map 与并发绑定冲突
            closeAllDevices()
        }
        updateNotification()
    }

    suspend fun bindDevice(usbManager: UsbManager, device: UsbDevice): DeviceBindResult {
        // 块内全是同步 JNI 调用、无挂起点，协程取消只会在 withContext 返回后
        // 抛出，不会中断块内的资源清理（connection 的开关都在块内完成）
        val result = withContext(UsbIpNative.nativeDispatcher) {
            // 防御：同一设备已绑定则拒绝，否则覆盖 map 条目导致旧连接泄漏
            if (activeDevices.containsKey(device.deviceName)) {
                return@withContext DeviceBindResult.Failure.DeviceInUse
            }

            val connection = usbManager.openDevice(device)
            if (connection == null) {
                return@withContext DeviceBindResult.Failure.DeviceOpenFailed
            }

            val fd = getFileDescriptorFromConnection(connection)
            if (fd < 0) {
                connection.close()
                return@withContext DeviceBindResult.Failure.DeviceOpenFailed
            }

            val outBusid = arrayOfNulls<String>(1)
            val nativeResult = UsbIpNative.bindUsbDeviceNative(fd, device.vendorId, device.productId, outBusid)

            when (nativeResult) {
                UsbIpNative.ErrorCode.SUCCESS -> {
                    // JNI 层 SUCCESS 时必已写 busid，防御性检查防止未来实现
                    // 改动导致 NPE
                    val busid = outBusid[0] ?: run {
                        connection.close()
                        return@withContext DeviceBindResult.Failure.UnknownError
                    }
                    activeDevices[device.deviceName] = DeviceInfo(connection, fd, busid)
                    Log.i(TAG, "Device bound: ${device.deviceName} -> $busid")
                    DeviceBindResult.Success(busid)
                }
                else -> {
                    connection.close()
                    mapErrorCodeToResult(nativeResult)
                }
            }
        }
        return result
    }

    suspend fun unbindDevice(deviceName: String): DeviceUnbindResult {
        val result = withContext(UsbIpNative.nativeDispatcher) {
            val info = activeDevices[deviceName]
            if (info == null) {
                return@withContext DeviceUnbindResult.Failure.DeviceNotFound
            }

            val nativeResult = UsbIpNative.unbindUsbDeviceNative(info.fd)

            when (nativeResult) {
                UsbIpNative.ErrorCode.SUCCESS -> {
                    activeDevices.remove(deviceName)?.connection?.close()
                    Log.i(TAG, "Device unbound: $deviceName")
                    DeviceUnbindResult.Success
                }
                UsbIpNative.ErrorCode.DEVICE_NOT_FOUND -> {
                    activeDevices.remove(deviceName)?.connection?.close()
                    Log.w(TAG, "Device already gone in native: $deviceName")
                    DeviceUnbindResult.Failure.DeviceNotFound
                }
                UsbIpNative.ErrorCode.DEVICE_IN_USE -> {
                    DeviceUnbindResult.Failure.DeviceInUse
                }
                else -> {
                    Log.e(TAG, "Unknown unbind error: $nativeResult for $deviceName")
                    DeviceUnbindResult.Failure.UnknownError
                }
            }
        }
        return result
    }

    suspend fun handleDeviceDetached(deviceName: String): Boolean {
        // 整个方法体在 nativeDispatcher 内执行：map 的读写都必须与
        // bindDevice/unbindDevice 同线程，否则 UI 线程的 remove 与
        // native 线程的写入并发修改 HashMap
        return withContext(UsbIpNative.nativeDispatcher) {
            val info = activeDevices[deviceName] ?: return@withContext false
            // 先通知 native 清理再关 Kotlin 连接：notify_device_removed 同步
            // 移除设备（available）或触发会话停止（using）；物理拔出后 fd 已
            // 失效，且 native 的 libusb handle 持有独立 fd，close 互不影响
            UsbIpNative.notifyDeviceRemovedNative(info.busid)
            activeDevices.remove(deviceName)?.connection?.close()
            Log.i(TAG, "Device detached: $deviceName")
            true
        }
    }

    /**
     * Surveille une adresse d'écoute ciblée même lorsque MainActivity est
     * détruite. 0.0.0.0 n'a pas besoin de surveillance : ce mode représente
     * volontairement toutes les interfaces.
     */
    private fun startListenAddressMonitor(address: String) {
        val generation = listenMonitorGeneration.incrementAndGet()

        if (address == NetworkInterfaceResolver.ALL_INTERFACES_ADDRESS) {
            return
        }

        Thread {
            try {
                while (
                    serverRunning &&
                    listenAddress == address &&
                    listenMonitorGeneration.get() == generation
                ) {
                    Thread.sleep(2000)

                    if (
                        !serverRunning ||
                        listenAddress != address ||
                        listenMonitorGeneration.get() != generation
                    ) {
                        break
                    }

                    if (
                        !NetworkInterfaceResolver.isAddressAvailable(
                            applicationContext,
                            address
                        )
                    ) {
                        Log.w(
                            TAG,
                            "Listen address disappeared: $address; stopping USB/IP server"
                        )

                        runBlocking {
                            stopServer()
                        }

                        break
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                // Ne jamais faire tomber le foreground service à cause du
                // moniteur. Le socket reste de toute façon lié à l'adresse
                // précise ; il ne bascule pas vers 0.0.0.0.
                Log.e(TAG, "Listen address monitor failed for $address", e)
            }
        }.apply {
            name = "UsbIpListenAddressMonitor"
            isDaemon = true
            start()
        }
    }

    private fun closeAllDevices() {
        activeDevices.values.forEach { it.connection.close() }
        activeDevices.clear()
        Log.i(TAG, "All devices closed")
    }

    private fun getFileDescriptorFromConnection(connection: UsbDeviceConnection): Int {
        // getFileDescriptor 是隐藏 API，没有公开替代，反射是唯一途径；
        // frameworks 层该实现多年未变，失败时返回 -1 由调用方兜底
        return try {
            val method = connection.javaClass.getDeclaredMethod("getFileDescriptor")
            method.isAccessible = true
            method.invoke(connection) as Int
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get file descriptor", e)
            -1
        }
    }

    private fun mapErrorCodeToResult(errorCode: Int): DeviceBindResult.Failure {
        return when (errorCode) {
            UsbIpNative.ErrorCode.DEVICE_NOT_FOUND -> DeviceBindResult.Failure.DeviceNotFound
            UsbIpNative.ErrorCode.DEVICE_IN_USE -> DeviceBindResult.Failure.DeviceInUse
            UsbIpNative.ErrorCode.DEVICE_OPEN_FAILED -> DeviceBindResult.Failure.DeviceOpenFailed
            UsbIpNative.ErrorCode.GET_DESCRIPTOR_FAILED -> DeviceBindResult.Failure.GetDescriptorFailed
            UsbIpNative.ErrorCode.GET_CONFIG_FAILED -> DeviceBindResult.Failure.GetConfigFailed
            UsbIpNative.ErrorCode.CLAIM_INTERFACE_FAILED -> DeviceBindResult.Failure.ClaimInterfaceFailed
            else -> DeviceBindResult.Failure.UnknownError
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "USB/IP Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            // 动态显示运行状态：服务器未运行时提示已停止，避免误导
            .setContentText(getString(if (serverRunning) R.string.server_running else R.string.server_stopped))
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification())
    }
}
