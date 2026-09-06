package com.yunsmall.usbipdcpp

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import com.yunsmall.usbipdcpp.ui.theme.UsbipdcppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale

// 文件顶层常量：MainScreen 是顶层函数而非 MainActivity 方法，
// 常量放 companion（private）会访问不到
private const val TAG = "MainActivity"

private const val CAMERA_ACTION_AUTHORIZE = "authorize"
private const val CAMERA_ACTION_BIND = "bind"

data class NetworkAddress(
    val interfaceName: String,
    val address: String,
    val priority: Int
)

private data class ParsedLogLine(
    val timestamp: String?,
    val level: String?,
    val message: String
)

private fun parseLogLine(rawMessage: String): ParsedLogLine {
    val message = rawMessage.trim()

    // Compatibilité avec l'ancien format natif spdlog :
    // [HH:mm:ss] [level] message
    //
    // Après la modification de jni_callback_sink.h, le callback recevra
    // uniquement le message brut. Ce parseur permet donc aux deux versions
    // de fonctionner sans double horodatage.
    val nativePattern = Regex(
        pattern = """^\[(\d{2}:\d{2}:\d{2})]\s*\[([^\]]+)]\s*(.*)$""",
        option = RegexOption.DOT_MATCHES_ALL
    )
    val nativeMatch = nativePattern.matchEntire(message)

    if (nativeMatch != null) {
        return ParsedLogLine(
            timestamp = nativeMatch.groupValues[1],
            level = nativeMatch.groupValues[2],
            message = nativeMatch.groupValues[3].trim()
        )
    }

    val timestampOnlyPattern = Regex(
        pattern = """^\[(\d{2}:\d{2}:\d{2})]\s*(.*)$""",
        option = RegexOption.DOT_MATCHES_ALL
    )
    val timestampOnlyMatch = timestampOnlyPattern.matchEntire(message)

    if (timestampOnlyMatch != null) {
        return ParsedLogLine(
            timestamp = timestampOnlyMatch.groupValues[1],
            level = null,
            message = timestampOnlyMatch.groupValues[2].trim()
        )
    }

    return ParsedLogLine(
        timestamp = null,
        level = null,
        message = message
    )
}

/*
 * spdlog::level::level_enum :
 * trace = 0, debug = 1, info = 2, warn = 3,
 * err = 4, critical = 5, off = 6.
 *
 * Le niveau est déjà transmis séparément par le callback JNI.
 */
private fun nativeLogLevelName(level: Int): String? {
    return when (level) {
        0 -> "trace"
        1 -> "debug"
        2 -> "info"
        3 -> "warn"
        4 -> "error"
        5 -> "critical"
        else -> null
    }
}

class MainActivity : AppCompatActivity() {

    private val usbManager: UsbManager by lazy {
        getSystemService(USB_SERVICE) as UsbManager
    }

    private val permissionManager: UsbPermissionManager by lazy {
        UsbPermissionManager(this, usbManager)
    }

    private var refreshDevicesCallback: (() -> Unit)? = null

    // 用于通知 Compose Service 状态变化
    private var onServiceStateChanged: (() -> Unit)? = null

    private var usbService: UsbService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as UsbService.UsbBinder
            usbService = binder.getService()
            serviceBound = true
            refreshDevicesCallback?.invoke()
            onServiceStateChanged?.invoke()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            usbService = null
            serviceBound = false
            onServiceStateChanged?.invoke()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        permissionManager.registerReceiver()

        // 启动并绑定 Service
        val serviceIntent = Intent(this, UsbService::class.java)
        startForegroundService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        setContent {
            UsbipdcppTheme {
                // 用 State 观察 Service 变化
                var serviceState by remember {
                    mutableStateOf(Pair<UsbService?, Boolean>(null, false))
                }

                DisposableEffect(Unit) {
                    onServiceStateChanged = {
                        serviceState = Pair(usbService, serviceBound)
                    }
                    // 立即触发一次以获取当前状态
                    serviceState = Pair(usbService, serviceBound)
                    onDispose {
                        onServiceStateChanged = null
                    }
                }

                MainScreen(
                    usbManager = usbManager,
                    permissionManager = permissionManager,
                    usbService = serviceState.first,
                    serviceBound = serviceState.second,
                    onRefreshCallbackReady = { callback ->
                        refreshDevicesCallback = callback
                    }
                )
            }
        }

        handleUsbIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            Log.d(TAG, "USB device attached via intent")
            refreshDevicesCallback?.invoke()
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // 释放回调引用：闭包捕获 Compose 状态，不清理会在销毁后滞留。
        // Compose 的 onDispose 也会置空，这里双保险覆盖"onDestroy 后、
        // Compose 销毁前"的窗口
        refreshDevicesCallback = null
        onServiceStateChanged = null

        permissionManager.unregisterReceiver()

        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }

        // 不停止 Service，让它继续运行
    }
}

fun isCameraDevice(device: UsbDevice): Boolean {
    for (i in 0 until device.interfaceCount) {
        if (
            device.getInterface(i).interfaceClass ==
            UsbConstants.USB_CLASS_VIDEO
        ) {
            return true
        }
    }

    return false
}

fun setLanguage(language: String) {
    val localeList = if (language == "system") {
        LocaleListCompat.getEmptyLocaleList()
    } else {
        LocaleListCompat.forLanguageTags(language)
    }

    AppCompatDelegate.setApplicationLocales(localeList)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    usbManager: UsbManager,
    permissionManager: UsbPermissionManager,
    usbService: UsbService?,
    serviceBound: Boolean,
    onRefreshCallbackReady: (() -> Unit) -> Unit = {}
) {
    var serverRunning by remember { mutableStateOf(false) }
    var isStarting by remember { mutableStateOf(false) }
    var isStopping by remember { mutableStateOf(false) }
    var portText by remember { mutableStateOf("3240") }
    var logMessages by remember { mutableStateOf(listOf<String>()) }
    var devices by remember { mutableStateOf(mapOf<String, UsbDevice>()) }
    var boundDevices by remember { mutableStateOf(setOf<String>()) }

    /*
     * État de permission USB par deviceName.
     *
     * UsbManager.hasPermission() n'est pas un State Compose : on garde donc
     * une copie observable afin que l'interface se mette immédiatement à jour
     * après une autorisation ou un refus.
     */
    var usbPermissions by remember {
        mutableStateOf<Map<String, Boolean>>(emptyMap())
    }

    /*
     * Périphériques pour lesquels la boîte de dialogue Android de permission
     * USB est actuellement en attente.
     */
    var pendingPermissionDevices by remember {
        mutableStateOf(setOf<String>())
    }

    var showFullLog by remember { mutableStateOf(false) }
    var showLanguageMenu by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var busyDevices by remember { mutableStateOf(setOf<String>()) }

    /*
     * Pour une caméra USB, Android peut demander CAMERA avant la permission
     * USB. On mémorise le périphérique et l'action à reprendre après le
     * résultat de la boîte de dialogue CAMERA.
     */
    var pendingCameraDeviceName by rememberSaveable {
        mutableStateOf<String?>(null)
    }
    var pendingCameraAction by rememberSaveable {
        mutableStateOf<String?>(null)
    }

    val scope = rememberCoroutineScope()

    // 页面导航：
    // 0 = 服务器 / USB 设备
    // 1 = 日志
    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { 2 }
    )

    val context = LocalContext.current

    // performBind 会被 rememberLauncherForActivityResult 的回调长期持有（首次组合
    // 的实例），必须经 rememberUpdatedState 读最新 usbService，否则授权后拿到
    // 的是服务绑定前的 null 快照，绑定必然失败
    val currentUsbService by rememberUpdatedState(usbService)

    /*
     * Exécute réellement le bind natif.
     *
     * Cette fonction suppose normalement que la permission USB a déjà été
     * accordée, mais performBind() garde une vérification défensive.
     */
    fun bindAuthorizedDevice(device: UsbDevice) {
        val service = currentUsbService ?: run {
            Toast.makeText(
                context,
                context.getString(R.string.service_not_ready),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (!service.nativeReady) {
            Toast.makeText(
                context,
                context.getString(R.string.native_init_failed),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val deviceName =
            device.productName?.takeIf { it.isNotEmpty() }
                ?: context.getString(R.string.unknown_device)

        scope.launch {
            busyDevices = busyDevices + device.deviceName

            try {
                val result = service.bindDevice(
                    usbManager,
                    device
                )

                // 用局部 service 刷新：绑定期间 Activity 重建可能更换
                // usbService 引用，用外部变量会读到不一致的状态
                boundDevices = service.boundDeviceNames

                when (result) {
                    is DeviceBindResult.Success -> {
                        Toast.makeText(
                            context,
                            context.getString(
                                R.string.bind_success,
                                deviceName
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    is DeviceBindResult.Failure -> {
                        Toast.makeText(
                            context,
                            context.getString(
                                R.string.bind_failed,
                                result.getMessage(context)
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } finally {
                busyDevices = busyDevices - device.deviceName
            }
        }
    }

    /*
     * Demande uniquement la permission Android d'accéder au périphérique.
     *
     * Contrairement au bind, cette action est autorisée même lorsque le
     * serveur USB/IP est arrêté.
     */
    fun requestUsbPermissionOnly(device: UsbDevice) {
        val accepted = permissionManager.requestPermission(
            device
        ) { _, granted ->
            if (!granted) {
                Toast.makeText(
                    context,
                    context.getString(R.string.device_unavailable),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        if (!accepted) {
            Toast.makeText(
                context,
                context.getString(R.string.permission_request_pending),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /*
     * Bind avec vérification défensive de la permission USB.
     *
     * L'interface n'affiche normalement "Lier" que lorsque la permission est
     * déjà accordée. Cette vérification protège toutefois contre un changement
     * d'état entre l'affichage et le clic.
     */
    fun performBind(device: UsbDevice) {
        if (permissionManager.hasPermission(device)) {
            bindAuthorizedDevice(device)
            return
        }

        val accepted = permissionManager.requestPermission(
            device
        ) { _, granted ->
            if (granted) {
                bindAuthorizedDevice(device)
            } else {
                Toast.makeText(
                    context,
                    context.getString(R.string.device_unavailable),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        if (!accepted) {
            Toast.makeText(
                context,
                context.getString(R.string.permission_request_pending),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // 相机权限请求
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val deviceName =
            pendingCameraDeviceName
                ?: return@rememberLauncherForActivityResult

        val action = pendingCameraAction

        pendingCameraDeviceName = null
        pendingCameraAction = null

        // Activity 重建后从设备列表重新查找设备对象
        val device =
            usbManager.deviceList[deviceName]
                ?: return@rememberLauncherForActivityResult

        if (!granted) {
            Toast.makeText(
                context,
                context.getString(R.string.device_unavailable),
                Toast.LENGTH_SHORT
            ).show()

            return@rememberLauncherForActivityResult
        }

        when (action) {
            CAMERA_ACTION_AUTHORIZE -> {
                requestUsbPermissionOnly(device)
            }

            CAMERA_ACTION_BIND -> {
                performBind(device)
            }
        }
    }

    /*
     * Les périphériques USB vidéo nécessitent CAMERA sur les versions Android
     * concernées avant que l'accès USB puisse être utilisé.
     */
    fun runWithCameraPermission(
        device: UsbDevice,
        action: String,
        block: () -> Unit
    ) {
        if (
            !isCameraDevice(device) ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            block()
            return
        }

        /*
         * Une boîte de dialogue CAMERA est déjà ouverte : ne pas écraser
         * l'action mémorisée avec un second clic.
         */
        if (pendingCameraDeviceName != null) {
            return
        }

        pendingCameraDeviceName = device.deviceName
        pendingCameraAction = action

        cameraPermissionLauncher.launch(
            Manifest.permission.CAMERA
        )
    }

    fun addLog(
        message: String,
        level: Int? = null
    ) {
        val parsed = parseLogLine(message)

        val timestamp =
            parsed.timestamp
                ?: java.text.SimpleDateFormat(
                    "HH:mm:ss",
                    Locale.getDefault()
                ).format(java.util.Date())

        // Avec l'ancien C++, le niveau peut encore être présent dans le texte.
        // Avec le nouveau jni_callback_sink.h, on utilise le niveau transmis
        // séparément par JNI.
        val levelName =
            parsed.level
                ?.takeIf { it.isNotBlank() }
                ?: level?.let { nativeLogLevelName(it) }

        val levelPrefix =
            levelName
                ?.let { "[$it] " }
                ?: ""

        // Toute la traduction du journal est centralisée dans LogLocalizer.kt,
        // qui utilise les ressources Android values/values-fr/values-zh.
        val localizedMessage = LogLocalizer.localize(
            context = context,
            sourceMessage = parsed.message
        )

        logMessages =
            logMessages +
                "[$timestamp] $levelPrefix$localizedMessage"
    }

    fun refreshDevices() {
        val currentDevices =
            permissionManager.getDeviceList()

        devices = currentDevices

        usbPermissions =
            currentDevices.mapValues { (_, device) ->
                permissionManager.hasPermission(device)
            }

        /*
         * Supprime les états "permission en attente" de périphériques qui
         * n'existent plus.
         */
        pendingPermissionDevices =
            pendingPermissionDevices.intersect(
                currentDevices.keys
            )

        // Message canonique reconnu par LogLocalizer.kt.
        addLog(
            message = "Found ${devices.size} USB device(s)",
            level = 2
        )
    }

    fun refreshState() {
        usbService?.let { service ->
            serverRunning = service.serverRunning
            boundDevices = service.boundDeviceNames
            portText = service.port.toString()
        }
    }

    // 获取所有适合客户端连接的 IPv4 地址。
    // 服务器监听 0.0.0.0，因此 Wi-Fi、VPN、Ethernet 等地址都可能可用。
    fun getDeviceIpAddresses(): List<NetworkAddress> {
        return try {
            val candidates =
                mutableListOf<NetworkAddress>()

            val interfaces =
                NetworkInterface.getNetworkInterfaces()
                    ?: return emptyList()

            while (interfaces.hasMoreElements()) {
                val networkInterface =
                    interfaces.nextElement()

                if (
                    networkInterface.isLoopback ||
                    !networkInterface.isUp
                ) {
                    continue
                }

                val ifName =
                    networkInterface.name
                        ?.lowercase(Locale.getDefault())
                        ?: continue

                // Android 内部/移动网络接口不适合作为 USB/IP 客户端地址显示。
                if (
                    ifName.startsWith("clat") ||
                    ifName.startsWith("v4-") ||
                    ifName.startsWith("dummy") ||
                    ifName.startsWith("sit") ||
                    ifName.startsWith("ip6tnl") ||
                    ifName.startsWith("rmnet")
                ) {
                    continue
                }

                val priority = when {
                    // WireGuard / VPN
                    ifName.startsWith("wg") -> 0
                    ifName.startsWith("tun") -> 0
                    ifName.startsWith("vpn") -> 0

                    // Wi-Fi
                    ifName.startsWith("wlan") -> 1
                    ifName.startsWith("swlan") -> 1

                    // Ethernet
                    ifName.startsWith("eth") -> 2
                    ifName.startsWith("en") -> 2

                    // USB / tethering
                    ifName.startsWith("rndis") -> 3
                    ifName.startsWith("usb") -> 3

                    else -> 10
                }

                val addresses =
                    networkInterface.inetAddresses

                while (addresses.hasMoreElements()) {
                    val address =
                        addresses.nextElement()

                    val host =
                        address.hostAddress
                            ?: continue

                    if (
                        address !is Inet4Address ||
                        address.isLoopbackAddress ||
                        address.isLinkLocalAddress ||
                        host.startsWith("192.0.0.")
                    ) {
                        continue
                    }

                    candidates.add(
                        NetworkAddress(
                            interfaceName =
                                networkInterface.name
                                    ?: ifName,
                            address = host,
                            priority = priority
                        )
                    )
                }
            }

            candidates
                .distinctBy { it.address }
                .sortedWith(
                    compareBy<NetworkAddress> { it.priority }
                        .thenBy { it.interfaceName }
                )
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Failed to get IP addresses",
                e
            )

            emptyList()
        }
    }

    val ipAddresses =
        remember {
            mutableStateOf<List<NetworkAddress>>(
                emptyList()
            )
        }

    // 刷新地址：VPN 在服务器已运行时启用/禁用，也会自动更新界面。
    LaunchedEffect(serverRunning) {
        if (!serverRunning) {
            ipAddresses.value = emptyList()
            return@LaunchedEffect
        }

        while (true) {
            ipAddresses.value =
                withContext(Dispatchers.IO) {
                    getDeviceIpAddresses()
                }

            delay(2000)
        }
    }

    // 设置native日志回调
    DisposableEffect(Unit) {
        // onLog 由 native 日志线程回调，直接更新 Compose 状态会跨线程写，
        // 切到主线程再执行
        val mainHandler =
            Handler(Looper.getMainLooper())

        val callback =
            object : LogCallback {
                override fun onLog(
                    level: Int,
                    message: String
                ) {
                    mainHandler.post {
                        addLog(
                            message = message.trim(),
                            level = level
                        )
                    }
                }
            }

        UsbIpNative.setLogCallback(callback)

        onDispose {
            // 必须清回调：旋转重建时新 setLogCallback 会替换旧引用（无需清理），
            // 但应用退后台（Activity 销毁、不再有新回调）时不清的话，JNI 的
            // 全局引用会一直持有旧 Activity，导致泄漏
            UsbIpNative.setLogCallback(null)
        }
    }

    // Service 状态变化时刷新
    LaunchedEffect(
        serviceBound,
        usbService
    ) {
        onRefreshCallbackReady {
            refreshDevices()
        }

        refreshDevices()
        refreshState()
    }

    // 监听USB设备插入/拔出（通过BroadcastReceiver）
    // 用 rememberUpdatedState 确保 lambda 始终读取最新值，不会被 DisposableEffect 捕获旧引用
    val currentService by rememberUpdatedState(usbService)

    DisposableEffect(permissionManager) {
        permissionManager.setOnDeviceAttachedListener {
            refreshDevices()
        }

        permissionManager.setOnDeviceDetachedListener { device ->
            pendingPermissionDevices =
                pendingPermissionDevices -
                    device.deviceName

            usbPermissions =
                usbPermissions -
                    device.deviceName

            scope.launch {
                val service =
                    currentService

                val wasBound =
                    service?.handleDeviceDetached(
                        device.deviceName
                    ) ?: false

                boundDevices =
                    service?.boundDeviceNames
                        ?: emptySet()

                if (wasBound) {
                    val deviceName =
                        device.productName
                            ?.takeIf { it.isNotEmpty() }
                            ?: context.getString(
                                R.string.unknown_device
                            )

                    Toast.makeText(
                        context,
                        context.getString(
                            R.string.device_detached,
                            deviceName
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        /*
         * La demande de permission est désormais un événement visible dans
         * l'interface et dans le journal.
         */
        permissionManager.setOnPermissionRequestedListener { device ->
            pendingPermissionDevices =
                pendingPermissionDevices +
                    device.deviceName

            val deviceName =
                device.productName
                    ?.takeIf { it.isNotEmpty() }
                    ?: context.getString(
                        R.string.unknown_device
                    )

            addLog(
                message = context.getString(
                    R.string.usb_permission_requested_log,
                    deviceName
                ),
                level = 2
            )
        }

        permissionManager.setOnPermissionResultListener { device, granted ->
            pendingPermissionDevices =
                pendingPermissionDevices -
                    device.deviceName

            usbPermissions =
                usbPermissions +
                    (
                        device.deviceName to
                            permissionManager.hasPermission(device)
                    )

            val deviceName =
                device.productName
                    ?.takeIf { it.isNotEmpty() }
                    ?: context.getString(
                        R.string.unknown_device
                    )

            if (granted) {
                addLog(
                    message = context.getString(
                        R.string.usb_permission_granted_log,
                        deviceName
                    ),
                    level = 2
                )
            } else {
                addLog(
                    message = context.getString(
                        R.string.usb_permission_denied_log,
                        deviceName
                    ),
                    level = 3
                )
            }
        }

        onDispose {
            permissionManager.setOnDeviceAttachedListener(null)
            permissionManager.setOnDeviceDetachedListener(null)
            permissionManager.setOnPermissionRequestedListener(null)
            permissionManager.setOnPermissionResultListener(null)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            R.string.app_title
                        )
                    )
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor =
                            MaterialTheme.colorScheme.primaryContainer
                    ),
                actions = {
                    TextButton(
                        onClick = {
                            showAbout = true
                        }
                    ) {
                        Text(
                            stringResource(
                                R.string.about
                            )
                        )
                    }

                    Box {
                        TextButton(
                            onClick = {
                                showLanguageMenu = true
                            }
                        ) {
                            Text(
                                stringResource(
                                    R.string.language
                                )
                            )
                        }

                        DropdownMenu(
                            expanded =
                                showLanguageMenu,
                            onDismissRequest = {
                                showLanguageMenu = false
                            }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            R.string.language_en
                                        )
                                    )
                                },
                                onClick = {
                                    setLanguage("en")
                                    showLanguageMenu = false
                                }
                            )

                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            R.string.language_fr
                                        )
                                    )
                                },
                                onClick = {
                                    setLanguage("fr")
                                    showLanguageMenu = false
                                }
                            )

                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            R.string.language_zh
                                        )
                                    )
                                },
                                onClick = {
                                    setLanguage("zh")
                                    showLanguageMenu = false
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
            ) { page ->
                when (page) {
                    // Page 1 : serveur et périphériques USB
                    0 -> {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(
                                        start = 16.dp,
                                        top = 16.dp,
                                        end = 16.dp,
                                        bottom = 8.dp
                                    ),
                            verticalArrangement =
                                Arrangement.spacedBy(
                                    16.dp
                                )
                        ) {
                            ServerControlPanel(
                                serverRunning =
                                    serverRunning,
                                isStarting =
                                    isStarting,
                                isStopping =
                                    isStopping,
                                portText =
                                    portText,
                                onPortChange = {
                                    portText = it
                                },
                                onStart = {
                                    val port =
                                        portText.toIntOrNull()
                                            ?: 3240

                                    val service =
                                        usbService

                                    if (service == null) {
                                        Toast.makeText(
                                            context,
                                            context.getString(
                                                R.string.service_not_ready
                                            ),
                                            Toast.LENGTH_SHORT
                                        ).show()

                                        return@ServerControlPanel
                                    }

                                    // native 初始化失败时无法启动服务器，明确提示
                                    if (!service.nativeReady) {
                                        Toast.makeText(
                                            context,
                                            context.getString(
                                                R.string.native_init_failed
                                            ),
                                            Toast.LENGTH_SHORT
                                        ).show()

                                        return@ServerControlPanel
                                    }

                                    isStarting = true

                                    scope.launch {
                                        val success =
                                            service.startServer(
                                                port
                                            )

                                        isStarting = false

                                        if (success) {
                                            serverRunning = true
                                        }
                                    }
                                },
                                onStop = {
                                    val service =
                                        usbService

                                    if (service == null) {
                                        Toast.makeText(
                                            context,
                                            context.getString(
                                                R.string.service_not_ready
                                            ),
                                            Toast.LENGTH_SHORT
                                        ).show()

                                        return@ServerControlPanel
                                    }

                                    isStopping = true

                                    scope.launch {
                                        service.stopServer()
                                        isStopping = false
                                        serverRunning = false
                                        boundDevices = emptySet()
                                    }
                                }
                            )

                            StatusCard(
                                serverRunning =
                                    serverRunning,
                                boundCount =
                                    boundDevices.size,
                                ipAddresses =
                                    ipAddresses.value,
                                port =
                                    portText.toIntOrNull()
                                        ?: 3240
                            )

                            DeviceListSection(
                                devices =
                                    devices,
                                boundDevices =
                                    boundDevices,
                                busyDevices =
                                    busyDevices,
                                usbPermissions =
                                    usbPermissions,
                                pendingPermissionDevices =
                                    pendingPermissionDevices,
                                serverRunning =
                                    serverRunning,
                                getBusid = {
                                    usbService?.getBusid(it)
                                },
                                onAuthorizeDevice = { device ->
                                    /*
                                     * Autoriser est indépendant du serveur :
                                     * l'utilisateur peut préparer l'accès USB
                                     * avant de démarrer USB/IP.
                                     */
                                    runWithCameraPermission(
                                        device = device,
                                        action = CAMERA_ACTION_AUTHORIZE
                                    ) {
                                        requestUsbPermissionOnly(
                                            device
                                        )
                                    }
                                },
                                onBindDevice = { device ->
                                    if (!serverRunning) {
                                        Toast.makeText(
                                            context,
                                            context.getString(
                                                R.string.please_start_server
                                            ),
                                            Toast.LENGTH_SHORT
                                        ).show()

                                        return@DeviceListSection
                                    }

                                    if (usbService == null) {
                                        Toast.makeText(
                                            context,
                                            context.getString(
                                                R.string.service_not_ready
                                            ),
                                            Toast.LENGTH_SHORT
                                        ).show()

                                        return@DeviceListSection
                                    }

                                    runWithCameraPermission(
                                        device = device,
                                        action = CAMERA_ACTION_BIND
                                    ) {
                                        performBind(
                                            device
                                        )
                                    }
                                },
                                onUnbindDevice = { device ->
                                    val service =
                                        usbService

                                    if (service == null) {
                                        Toast.makeText(
                                            context,
                                            context.getString(
                                                R.string.service_not_ready
                                            ),
                                            Toast.LENGTH_SHORT
                                        ).show()

                                        return@DeviceListSection
                                    }

                                    val deviceName =
                                        device.productName
                                            ?.takeIf {
                                                it.isNotEmpty()
                                            }
                                            ?: context.getString(
                                                R.string.unknown_device
                                            )

                                    scope.launch {
                                        busyDevices =
                                            busyDevices +
                                                device.deviceName

                                        try {
                                            val result =
                                                service.unbindDevice(
                                                    device.deviceName
                                                )

                                            // 无论成功失败都刷新，确保 UI 与 Service 状态一致
                                            boundDevices =
                                                service.boundDeviceNames

                                            when (result) {
                                                is DeviceUnbindResult.Success -> {
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(
                                                            R.string.unbind_success,
                                                            deviceName
                                                        ),
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }

                                                is DeviceUnbindResult.Failure -> {
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(
                                                            R.string.unbind_failed,
                                                            result.getMessage(
                                                                context
                                                            )
                                                        ),
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                        } finally {
                                            busyDevices =
                                                busyDevices -
                                                    device.deviceName
                                        }
                                    }
                                },
                                onRefresh = {
                                    refreshDevices()
                                }
                            )
                        }
                    }

                    // Page 2 : journal
                    1 -> {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(
                                        start = 16.dp,
                                        top = 16.dp,
                                        end = 16.dp,
                                        bottom = 8.dp
                                    )
                        ) {
                            LogSection(
                                logMessages =
                                    logMessages,
                                onClear = {
                                    logMessages =
                                        emptyList()
                                },
                                onViewFullLog = {
                                    showFullLog = true
                                },
                                onCopyLog = {
                                    val clipboard =
                                        context.getSystemService(
                                            Context.CLIPBOARD_SERVICE
                                        ) as ClipboardManager

                                    clipboard.setPrimaryClip(
                                        ClipData.newPlainText(
                                            "Log",
                                            logMessages.joinToString(
                                                "\n"
                                            )
                                        )
                                    )

                                    Toast.makeText(
                                        context,
                                        context.getString(
                                            R.string.log_copied
                                        ),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                        }
                    }
                }
            }

            // Bulles fixes en bas : appui ou swipe pour changer de page.
            PageIndicator(
                pageCount = 2,
                currentPage =
                    pagerState.currentPage,
                onPageSelected = { page ->
                    scope.launch {
                        pagerState.animateScrollToPage(
                            page
                        )
                    }
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            top = 2.dp,
                            bottom = 8.dp
                        )
            )
        }
    }

    if (showFullLog) {
        FullLogDialog(
            logMessages = logMessages,
            onDismiss = {
                showFullLog = false
            }
        )
    }

    if (showAbout) {
        // 当前包名查不到自己的信息理论上不可能，但规范上还是防御一下
        val version =
            try {
                context.packageManager
                    .getPackageInfo(
                        context.packageName,
                        0
                    )
                    .versionName
                    ?: "unknown"
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Failed to get package info",
                    e
                )

                "unknown"
            }

        val githubUrl =
            "https://github.com/yunsmall/Android-Usbipdcpp"

        AlertDialog(
            onDismissRequest = {
                showAbout = false
            },
            title = {
                Text(
                    stringResource(
                        R.string.about_title
                    )
                )
            },
            text = {
                Column {
                    Text(
                        stringResource(
                            R.string.about_description
                        )
                    )

                    Spacer(
                        modifier =
                            Modifier.height(
                                8.dp
                            )
                    )

                    Text(
                        stringResource(
                            R.string.about_version,
                            version
                        )
                    )

                    Text(
                        stringResource(
                            R.string.about_license
                        )
                    )

                    Spacer(
                        modifier =
                            Modifier.height(
                                8.dp
                            )
                    )

                    TextButton(
                        onClick = {
                            val intent =
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(
                                        githubUrl
                                    )
                                )

                            context.startActivity(
                                intent
                            )
                        }
                    ) {
                        Text(
                            stringResource(
                                R.string.about_github
                            ),
                            color =
                                MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAbout = false
                    }
                ) {
                    Text(
                        stringResource(
                            R.string.close
                        )
                    )
                }
            }
        )
    }
}

@Composable
fun PageIndicator(
    pageCount: Int,
    currentPage: Int,
    onPageSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement =
            Arrangement.Center,
        verticalAlignment =
            Alignment.CenterVertically
    ) {
        repeat(pageCount) { page ->
            IconButton(
                onClick = {
                    onPageSelected(
                        page
                    )
                }
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(
                                if (
                                    currentPage ==
                                    page
                                ) {
                                    10.dp
                                } else {
                                    8.dp
                                }
                            )
                            .background(
                                color =
                                    if (
                                        currentPage ==
                                        page
                                    ) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                shape =
                                    RoundedCornerShape(
                                        50
                                    )
                            )
                )
            }
        }
    }
}

@Composable
fun ServerControlPanel(
    serverRunning: Boolean,
    isStarting: Boolean,
    isStopping: Boolean,
    portText: String,
    onPortChange: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Card(
        modifier =
            Modifier.fillMaxWidth()
    ) {
        Column(
            modifier =
                Modifier.padding(
                    16.dp
                ),
            verticalArrangement =
                Arrangement.spacedBy(
                    12.dp
                )
        ) {
            Text(
                stringResource(
                    R.string.server_control
                ),
                style =
                    MaterialTheme.typography.titleMedium
            )

            Row(
                modifier =
                    Modifier.fillMaxWidth(),
                verticalAlignment =
                    Alignment.CenterVertically,
                horizontalArrangement =
                    Arrangement.spacedBy(
                        16.dp
                    )
            ) {
                OutlinedTextField(
                    value = portText,
                    onValueChange = {
                        onPortChange(
                            it.filter { c ->
                                c.isDigit()
                            }
                        )
                    },
                    label = {
                        Text(
                            stringResource(
                                R.string.port
                            )
                        )
                    },
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType =
                                KeyboardType.Number
                        ),
                    modifier =
                        Modifier.width(
                            100.dp
                        ),
                    enabled =
                        !serverRunning &&
                        !isStarting,
                    singleLine = true
                )

                Spacer(
                    modifier =
                        Modifier.weight(
                            1f
                        )
                )

                if (
                    serverRunning ||
                    isStopping
                ) {
                    Button(
                        onClick = onStop,
                        enabled =
                            !isStopping,
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor =
                                    MaterialTheme.colorScheme.error
                            )
                    ) {
                        Row(
                            horizontalArrangement =
                                Arrangement.spacedBy(
                                    8.dp
                                ),
                            verticalAlignment =
                                Alignment.CenterVertically
                        ) {
                            if (isStopping) {
                                CircularProgressIndicator(
                                    modifier =
                                        Modifier.size(
                                            16.dp
                                        ),
                                    strokeWidth =
                                        2.dp,
                                    color =
                                        MaterialTheme.colorScheme.onError
                                )

                                Text(
                                    stringResource(
                                        R.string.stopping
                                    )
                                )
                            } else {
                                Text(
                                    stringResource(
                                        R.string.stop_server
                                    )
                                )
                            }
                        }
                    }
                } else {
                    Button(
                        onClick = onStart,
                        enabled =
                            !isStarting
                    ) {
                        Row(
                            horizontalArrangement =
                                Arrangement.spacedBy(
                                    8.dp
                                ),
                            verticalAlignment =
                                Alignment.CenterVertically
                        ) {
                            if (isStarting) {
                                CircularProgressIndicator(
                                    modifier =
                                        Modifier.size(
                                            16.dp
                                        ),
                                    strokeWidth =
                                        2.dp,
                                    color =
                                        MaterialTheme.colorScheme.onPrimary
                                )

                                Text(
                                    stringResource(
                                        R.string.starting
                                    )
                                )
                            } else {
                                Text(
                                    stringResource(
                                        R.string.start_server
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatusCard(
    serverRunning: Boolean,
    boundCount: Int,
    ipAddresses: List<NetworkAddress>,
    port: Int
) {
    var showCopyMenu by remember {
        mutableStateOf(false)
    }

    val context =
        LocalContext.current

    Box {
        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(
                        enabled =
                            serverRunning &&
                            ipAddresses.isNotEmpty()
                    ) {
                        showCopyMenu = true
                    },
            colors =
                CardDefaults.cardColors(
                    containerColor =
                        if (serverRunning) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                )
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            16.dp
                        ),
                verticalAlignment =
                    Alignment.CenterVertically
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(
                                12.dp
                            )
                            .background(
                                if (
                                    serverRunning
                                ) {
                                    Color.Green
                                } else {
                                    Color.Red
                                },
                                RoundedCornerShape(
                                    50
                                )
                            )
                )

                Spacer(
                    modifier =
                        Modifier.width(
                            12.dp
                        )
                )

                Column {
                    Text(
                        text =
                            if (
                                serverRunning
                            ) {
                                stringResource(
                                    R.string.server_running
                                )
                            } else {
                                stringResource(
                                    R.string.server_stopped
                                )
                            },
                        style =
                            MaterialTheme.typography.titleMedium
                    )

                    if (serverRunning) {
                        ipAddresses.forEach {
                            networkAddress ->
                            Text(
                                text =
                                    "${networkAddress.interfaceName} • " +
                                        stringResource(
                                            R.string.address,
                                            networkAddress.address,
                                            port
                                        ),
                                style =
                                    MaterialTheme.typography.bodySmall
                            )
                        }

                        Text(
                            text =
                                stringResource(
                                    R.string.devices_bound,
                                    boundCount
                                ),
                            style =
                                MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        DropdownMenu(
            expanded =
                showCopyMenu,
            onDismissRequest = {
                showCopyMenu = false
            }
        ) {
            val clipboard =
                context.getSystemService(
                    Context.CLIPBOARD_SERVICE
                ) as ClipboardManager

            ipAddresses.forEach {
                networkAddress ->
                DropdownMenuItem(
                    text = {
                        Text(
                            "${networkAddress.interfaceName} — " +
                                stringResource(
                                    R.string.copy_address,
                                    networkAddress.address,
                                    port
                                )
                        )
                    },
                    onClick = {
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText(
                                "Address",
                                "${networkAddress.address}:$port"
                            )
                        )

                        showCopyMenu = false
                    }
                )
            }

            if (
                ipAddresses.isNotEmpty()
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                R.string.copy_port,
                                port
                            )
                        )
                    },
                    onClick = {
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText(
                                "Port",
                                port.toString()
                            )
                        )

                        showCopyMenu = false
                    }
                )
            }
        }
    }
}

@Composable
fun ColumnScope.DeviceListSection(
    devices: Map<String, UsbDevice>,
    boundDevices: Set<String>,
    busyDevices: Set<String>,
    usbPermissions: Map<String, Boolean>,
    pendingPermissionDevices: Set<String>,
    serverRunning: Boolean,
    getBusid: (String) -> String?,
    onAuthorizeDevice: (UsbDevice) -> Unit,
    onBindDevice: (UsbDevice) -> Unit,
    onUnbindDevice: (UsbDevice) -> Unit,
    onRefresh: () -> Unit
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .weight(
                    1.5f,
                    fill = false
                )
    ) {
        Column(
            modifier =
                Modifier.padding(
                    16.dp
                )
        ) {
            Row(
                modifier =
                    Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.SpaceBetween,
                verticalAlignment =
                    Alignment.CenterVertically
            ) {
                Text(
                    stringResource(
                        R.string.usb_devices
                    ),
                    style =
                        MaterialTheme.typography.titleMedium
                )

                TextButton(
                    onClick = onRefresh
                ) {
                    Text(
                        stringResource(
                            R.string.refresh
                        )
                    )
                }
            }

            if (devices.isEmpty()) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(
                                100.dp
                            ),
                    contentAlignment =
                        Alignment.Center
                ) {
                    Text(
                        stringResource(
                            R.string.no_devices
                        ),
                        style =
                            MaterialTheme.typography.bodyMedium,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(
                                1f
                            ),
                    verticalArrangement =
                        Arrangement.spacedBy(
                            8.dp
                        )
                ) {
                    items(
                        items =
                            devices.entries.toList(),
                        key = {
                            it.key
                        }
                    ) { entry ->
                        val device =
                            entry.value

                        val isBound =
                            boundDevices.contains(
                                device.deviceName
                            )

                        val hasUsbPermission =
                            usbPermissions[
                                device.deviceName
                            ] == true

                        val isPermissionPending =
                            pendingPermissionDevices.contains(
                                device.deviceName
                            )

                        val busid =
                            getBusid(
                                device.deviceName
                            )

                        DeviceItem(
                            device =
                                device,
                            isBound =
                                isBound,
                            isBusy =
                                busyDevices.contains(
                                    device.deviceName
                                ),
                            hasUsbPermission =
                                hasUsbPermission,
                            isPermissionPending =
                                isPermissionPending,
                            busid =
                                busid,
                            canBind =
                                serverRunning &&
                                hasUsbPermission &&
                                !isBound,
                            onAuthorize = {
                                onAuthorizeDevice(
                                    device
                                )
                            },
                            onBind = {
                                onBindDevice(
                                    device
                                )
                            },
                            onUnbind = {
                                onUnbindDevice(
                                    device
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceItem(
    device: UsbDevice,
    isBound: Boolean,
    isBusy: Boolean,
    hasUsbPermission: Boolean,
    isPermissionPending: Boolean,
    busid: String?,
    canBind: Boolean,
    onAuthorize: () -> Unit,
    onBind: () -> Unit,
    onUnbind: () -> Unit
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    vertical = 4.dp
                ),
        horizontalArrangement =
            Arrangement.SpaceBetween,
        verticalAlignment =
            Alignment.CenterVertically
    ) {
        Column(
            modifier =
                Modifier.weight(
                    1f
                )
        ) {
            Text(
                text =
                    device.productName
                        ?.takeIf {
                            it.isNotEmpty()
                        }
                        ?: stringResource(
                            R.string.unknown_device
                        ),
                style =
                    MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow =
                    TextOverflow.Ellipsis
            )

            Text(
                text =
                    "VID: ${
                        device.vendorId
                            .toString(16)
                            .uppercase()
                    }, PID: ${
                        device.productId
                            .toString(16)
                            .uppercase()
                    }",
                style =
                    MaterialTheme.typography.bodySmall,
                color =
                    MaterialTheme.colorScheme.onSurfaceVariant
            )

            /*
             * État explicite de la permission USB Android.
             */
            Text(
                text =
                    if (hasUsbPermission) {
                        stringResource(
                            R.string.usb_permission_granted
                        )
                    } else {
                        stringResource(
                            R.string.usb_permission_required
                        )
                    },
                style =
                    MaterialTheme.typography.bodySmall,
                color =
                    if (hasUsbPermission) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
            )

            if (busid != null) {
                Text(
                    text =
                        "BUSID: $busid",
                    style =
                        MaterialTheme.typography.bodySmall,
                    color =
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(
            modifier =
                Modifier.width(
                    8.dp
                )
        )

        when {
            isBusy || isPermissionPending -> {
                CircularProgressIndicator(
                    modifier =
                        Modifier.size(
                            24.dp
                        ),
                    strokeWidth =
                        2.dp
                )
            }

            isBound -> {
                TextButton(
                    onClick =
                        onUnbind,
                    modifier =
                        Modifier.height(
                            36.dp
                        )
                ) {
                    Text(
                        stringResource(
                            R.string.unbind
                        ),
                        fontSize =
                            12.sp,
                        color =
                            MaterialTheme.colorScheme.error
                    )
                }
            }

            !hasUsbPermission -> {
                Button(
                    onClick =
                        onAuthorize,
                    modifier =
                        Modifier.height(
                            36.dp
                        )
                ) {
                    Text(
                        stringResource(
                            R.string.authorize
                        ),
                        fontSize =
                            12.sp
                    )
                }
            }

            else -> {
                Button(
                    onClick =
                        onBind,
                    modifier =
                        Modifier.height(
                            36.dp
                        ),
                    enabled =
                        canBind
                ) {
                    Text(
                        stringResource(
                            R.string.bind
                        ),
                        fontSize =
                            12.sp
                    )
                }
            }
        }
    }
}

@Composable
fun ColumnScope.LogSection(
    logMessages: List<String>,
    onClear: () -> Unit,
    onViewFullLog: () -> Unit,
    onCopyLog: () -> Unit
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .weight(
                    1.5f
                )
    ) {
        Column(
            modifier =
                Modifier.padding(
                    16.dp
                )
        ) {
            Row(
                modifier =
                    Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.SpaceBetween,
                verticalAlignment =
                    Alignment.CenterVertically
            ) {
                Text(
                    stringResource(
                        R.string.log
                    ),
                    style =
                        MaterialTheme.typography.titleMedium
                )

                Row {
                    TextButton(
                        onClick =
                            onViewFullLog
                    ) {
                        Text(
                            stringResource(
                                R.string.expand
                            )
                        )
                    }

                    TextButton(
                        onClick =
                            onCopyLog
                    ) {
                        Text(
                            stringResource(
                                R.string.copy
                            )
                        )
                    }

                    TextButton(
                        onClick =
                            onClear
                    ) {
                        Text(
                            stringResource(
                                R.string.clear
                            )
                        )
                    }
                }
            }

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(
                            min = 150.dp
                        )
                        .weight(
                            1f
                        )
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(
                                8.dp
                            )
                        )
                        .padding(
                            8.dp
                        )
            ) {
                if (
                    logMessages.isEmpty()
                ) {
                    Text(
                        stringResource(
                            R.string.no_logs
                        ),
                        style =
                            MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val scrollState =
                        rememberScrollState()

                    LaunchedEffect(
                        logMessages.size
                    ) {
                        scrollState.animateScrollTo(
                            scrollState.maxValue
                        )
                    }

                    Text(
                        text =
                            logMessages.joinToString(
                                "\n"
                            ),
                        style =
                            MaterialTheme.typography.bodySmall,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .verticalScroll(
                                    scrollState
                                )
                    )
                }
            }
        }
    }
}

@Composable
fun FullLogDialog(
    logMessages: List<String>,
    onDismiss: () -> Unit
) {
    val scrollState =
        rememberScrollState()

    LaunchedEffect(
        logMessages.size
    ) {
        scrollState.animateScrollTo(
            scrollState.maxValue
        )
    }

    AlertDialog(
        onDismissRequest =
            onDismiss,
        title = {
            Text(
                stringResource(
                    R.string.log_messages
                )
            )
        },
        text = {
            SelectionContainer {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(
                                max = 400.dp
                            )
                            .verticalScroll(
                                scrollState
                            )
                ) {
                    Text(
                        text =
                            logMessages.joinToString(
                                "\n"
                            ),
                        style =
                            MaterialTheme.typography.bodySmall,
                        modifier =
                            Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick =
                    onDismiss
            ) {
                Text(
                    stringResource(
                        R.string.close
                    )
                )
            }
        }
    )
}
