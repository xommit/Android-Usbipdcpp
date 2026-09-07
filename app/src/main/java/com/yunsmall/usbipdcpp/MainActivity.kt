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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
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

/*
 * USB-IF class code for Billboard devices.
 * Android UsbConstants does not expose a dedicated Billboard constant.
 */
private const val USB_CLASS_BILLBOARD = 0x11

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

/**
 * Entrée de journal conservée sous sa forme native/non traduite.
 *
 * La traduction est faite au moment de l'affichage. Ainsi, lorsqu'Android
 * recrée l'Activity après un changement de langue, les anciennes lignes sont
 * immédiatement réaffichées dans la nouvelle langue.
 */
data class RawLogEntry(
    val timestamp: String,
    val level: String?,
    val message: String
)

/**
 * Le ViewModel survit aux recréations de MainActivity dues aux changements de
 * configuration (notamment AppCompatDelegate.setApplicationLocales()).
 *
 * Il ne contient aucun Context afin d'éviter toute fuite d'Activity.
 */
class LogViewModel : ViewModel() {
    val entries = mutableStateListOf<RawLogEntry>()

    fun add(entry: RawLogEntry) {
        entries.add(entry)
    }

    fun clear() {
        entries.clear()
    }
}

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

/**
 * Liste large des IPv4 locales affichables lorsque le serveur écoute sur
 * 0.0.0.0 ("Toutes").
 *
 * Cette fonction ne sert JAMAIS à choisir l'interface de bind. La sélection
 * VPN / Wi-Fi / Ethernet repose exclusivement sur NetworkInterfaceResolver et
 * NetworkCapabilities. Ici on conserve seulement le comportement historique
 * d'affichage, notamment pour USB tethering / RNDIS et certaines interfaces
 * locales non représentées comme Network Android sélectionnable.
 */
private fun getAllClientIpv4Addresses(): List<NetworkAddress> {
    return try {
        val candidates = mutableListOf<NetworkAddress>()

        val interfaces =
            NetworkInterface.getNetworkInterfaces()
                ?: return emptyList()

        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()

            if (
                networkInterface.isLoopback ||
                !networkInterface.isUp
            ) {
                continue
            }

            val interfaceName =
                networkInterface.name
                    ?.takeIf { it.isNotBlank() }
                    ?: continue

            val lowerName =
                interfaceName.lowercase(Locale.ROOT)

            /*
             * Conserver les exclusions historiques d'interfaces Android
             * internes / traduction IPv4 / mobile qui ne sont pas utiles comme
             * adresse USB/IP à présenter au client.
             */
            if (
                lowerName.startsWith("clat") ||
                lowerName.startsWith("v4-") ||
                lowerName.startsWith("dummy") ||
                lowerName.startsWith("sit") ||
                lowerName.startsWith("ip6tnl") ||
                lowerName.startsWith("rmnet")
            ) {
                continue
            }

            val priority = when {
                lowerName.startsWith("wg") ||
                    lowerName.startsWith("tun") ||
                    lowerName.startsWith("vpn") -> 0

                lowerName.startsWith("wlan") ||
                    lowerName.startsWith("swlan") -> 1

                lowerName.startsWith("eth") ||
                    lowerName.startsWith("en") -> 2

                lowerName.startsWith("rndis") ||
                    lowerName.startsWith("usb") -> 3

                else -> 10
            }

            val addresses = networkInterface.inetAddresses

            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                val host = address.hostAddress ?: continue

                if (
                    address !is Inet4Address ||
                    address.isLoopbackAddress ||
                    address.isLinkLocalAddress ||
                    host.startsWith("192.0.0.")
                ) {
                    continue
                }

                candidates += NetworkAddress(
                    interfaceName = interfaceName,
                    address = host,
                    priority = priority
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
        Log.e(TAG, "Failed to get client IPv4 addresses", e)
        emptyList()
    }
}

/*
 * Textes réseau temporaires localisés ici pour ne pas rendre ce fichier
 * dépendant de nouvelles ressources XML au même commit.
 *
 * Ils couvrent les trois langues actuellement proposées par l'application.
 * Ils pourront être déplacés dans strings.xml dans une étape dédiée.
 */
private fun networkUiLanguage(context: Context): String {
    return context.resources.configuration.locales[0].language
}

private fun listenInterfaceTypeLabel(
    context: Context,
    type: ListenInterfaceType
): String {
    return when (networkUiLanguage(context)) {
        "fr" -> when (type) {
            ListenInterfaceType.ALL -> "Toutes"
            ListenInterfaceType.VPN -> "VPN"
            ListenInterfaceType.WIFI -> "Wi-Fi"
            ListenInterfaceType.ETHERNET -> "Ethernet"
        }

        "zh" -> when (type) {
            ListenInterfaceType.ALL -> "全部"
            ListenInterfaceType.VPN -> "VPN"
            ListenInterfaceType.WIFI -> "Wi-Fi"
            ListenInterfaceType.ETHERNET -> "以太网"
        }

        else -> when (type) {
            ListenInterfaceType.ALL -> "All"
            ListenInterfaceType.VPN -> "VPN"
            ListenInterfaceType.WIFI -> "Wi-Fi"
            ListenInterfaceType.ETHERNET -> "Ethernet"
        }
    }
}

private fun listenInterfaceFieldLabel(context: Context): String {
    return when (networkUiLanguage(context)) {
        "fr" -> "Interface réseau du serveur"
        "zh" -> "服务器网络接口"
        else -> "Server network interface"
    }
}

private fun allInterfacesLabel(context: Context): String {
    return when (networkUiLanguage(context)) {
        "fr" -> "Toutes les interfaces (0.0.0.0)"
        "zh" -> "所有接口 (0.0.0.0)"
        else -> "All interfaces (0.0.0.0)"
    }
}

private fun listenAddressFieldLabel(context: Context): String {
    return when (networkUiLanguage(context)) {
        "fr" -> "Adresse d'écoute"
        "zh" -> "监听地址"
        else -> "Listen address"
    }
}

private fun noListenAddressText(context: Context): String {
    return when (networkUiLanguage(context)) {
        "fr" -> "Aucune adresse disponible"
        "zh" -> "没有可用地址"
        else -> "No address available"
    }
}

private fun selectListenAddressText(context: Context): String {
    return when (networkUiLanguage(context)) {
        "fr" -> "Sélectionner une adresse"
        "zh" -> "选择地址"
        else -> "Select an address"
    }
}

private fun listenInterfaceUnavailableText(context: Context): String {
    return when (networkUiLanguage(context)) {
        "fr" -> "L'interface réseau sélectionnée n'est plus disponible."
        "zh" -> "所选网络接口已不可用。"
        else -> "The selected network interface is no longer available."
    }
}

private fun serverStoppedNetworkLostText(context: Context): String {
    return when (networkUiLanguage(context)) {
        "fr" -> "Interface réseau perdue : serveur USB/IP arrêté."
        "zh" -> "网络接口已断开：USB/IP 服务器已停止。"
        else -> "Network interface lost: USB/IP server stopped."
    }
}

private fun defaultPortText(context: Context): String {
    return when (networkUiLanguage(context)) {
        "fr" -> "Défaut : 3240"
        "zh" -> "默认：3240"
        else -> "Default: 3240"
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

        // Ce ViewModel est conservé lorsque l'Activity est recréée lors d'un
        // changement de langue/configuration.
        val logViewModel = ViewModelProvider(this)[LogViewModel::class.java]

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
                    logViewModel = logViewModel,
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

/*
 * Certains périphériques déclarent leur classe au niveau du Device
 * Descriptor, d'autres au niveau d'une interface. Vérifier les deux évite
 * d'avoir besoin d'une liste VID/PID maintenue manuellement.
 */
private fun hasUsbClass(
    device: UsbDevice,
    usbClass: Int
): Boolean {
    if (device.deviceClass == usbClass) {
        return true
    }

    for (i in 0 until device.interfaceCount) {
        if (device.getInterface(i).interfaceClass == usbClass) {
            return true
        }
    }

    return false
}

fun isUsbHubDevice(device: UsbDevice): Boolean {
    return hasUsbClass(
        device,
        UsbConstants.USB_CLASS_HUB
    )
}

fun isUsbBillboardDevice(device: UsbDevice): Boolean {
    return hasUsbClass(
        device,
        USB_CLASS_BILLBOARD
    )
}

fun isNonShareableUsbDevice(device: UsbDevice): Boolean {
    return isUsbHubDevice(device) ||
        isUsbBillboardDevice(device) ||
        UsbDeviceBlacklist.isBlacklisted(device)
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
    logViewModel: LogViewModel,
    onRefreshCallbackReady: (() -> Unit) -> Unit = {}
) {
    var serverRunning by remember { mutableStateOf(false) }
    var isStarting by remember { mutableStateOf(false) }
    var isStopping by remember { mutableStateOf(false) }
    var portText by remember { mutableStateOf("3240") }

    var portFieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = portText,
                selection = TextRange(portText.length)
            )
        )
    }

    var devices by remember { mutableStateOf(mapOf<String, UsbDevice>()) }
    var boundDevices by remember { mutableStateOf(setOf<String>()) }

    /*
     * Sélection réelle de l'interface d'écoute.
     *
     * Le type est stocké sous forme de String pour que rememberSaveable puisse
     * le restaurer sans Saver personnalisé lors d'une recréation d'Activity.
     */
    var selectedListenTypeName by rememberSaveable {
        mutableStateOf(ListenInterfaceType.ALL.name)
    }
    var selectedListenAddress by rememberSaveable {
        mutableStateOf(NetworkInterfaceResolver.ALL_INTERFACES_ADDRESS)
    }
    var availableListenAddresses by remember {
        mutableStateOf<List<ListenAddressOption>>(emptyList())
    }
    var allClientIpAddresses by remember {
        mutableStateOf<List<NetworkAddress>>(emptyList())
    }

    val selectedListenType = remember(selectedListenTypeName) {
        runCatching {
            ListenInterfaceType.valueOf(selectedListenTypeName)
        }.getOrDefault(ListenInterfaceType.ALL)
    }

    val validPort =
        portText.toIntOrNull()
            ?.takeIf { it in 1024..65535 }

    LaunchedEffect(portText) {
        if (portFieldValue.text != portText) {
            portFieldValue = TextFieldValue(
                text = portText,
                selection = TextRange(portText.length)
            )
        }
    }


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
    // 0 = 服务器控制
    // 1 = USB 状态 / 设备（主页面）
    // 2 = 日志
    //
    // La page USB reste la page principale même si la page de contrôle
    // est placée avant elle dans l'ordre de navigation.
    val pagerState = rememberPagerState(
        initialPage = 1,
        pageCount = { 3 }
    )

    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    /*
     * Quand l'utilisateur quitte la page de contrôle du serveur, libérer le
     * focus du champ Port. Cela ferme également le clavier et évite que le
     * champ reste visuellement en mode édition sur les autres pages.
     */
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != 0) {
            focusManager.clearFocus(force = true)
        }
    }

    /*
     * IMPORTANT : les entrées sont conservées brutes dans le ViewModel et
     * localisées ici à chaque composition. Un changement de langue recrée
     * l'Activity mais conserve le ViewModel : le journal reste présent et
     * toutes les anciennes lignes utilisent automatiquement la nouvelle
     * langue.
     */
    val logMessages = logViewModel.entries.map { entry ->
        val levelPrefix = entry.level
            ?.takeIf { it.isNotBlank() }
            ?.let { "[$it] " }
            ?: ""

        val localizedMessage = LogLocalizer.localize(
            context = context,
            sourceMessage = entry.message
        )

        "[${entry.timestamp}] $levelPrefix$localizedMessage"
    }

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

        // Ne pas traduire ici : on conserve le message source pour pouvoir
        // retraduire toutes les anciennes lignes après un changement de langue.
        logViewModel.add(
            RawLogEntry(
                timestamp = timestamp,
                level = levelName,
                message = parsed.message
            )
        )
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

            if (service.serverRunning) {
                selectedListenAddress = service.listenAddress

                if (
                    service.listenAddress ==
                    NetworkInterfaceResolver.ALL_INTERFACES_ADDRESS
                ) {
                    selectedListenTypeName =
                        ListenInterfaceType.ALL.name
                } else {
                    availableListenAddresses
                        .firstOrNull {
                            it.address == service.listenAddress
                        }
                        ?.let {
                            selectedListenTypeName =
                                it.type.name
                        }
                }
            }
        }
    }

    /*
     * Actualise périodiquement les réseaux Android, même lorsque le serveur
     * est arrêté, afin que l'utilisateur puisse sélectionner une interface
     * avant le démarrage.
     */
    LaunchedEffect(Unit) {
        while (true) {
            availableListenAddresses =
                NetworkInterfaceResolver.getAvailableAddresses(context)

            allClientIpAddresses =
                withContext(Dispatchers.IO) {
                    getAllClientIpv4Addresses()
                }

            delay(2000)
        }
    }

    /*
     * Quand le type change et que le serveur est arrêté :
     * - ALL sélectionne toujours 0.0.0.0 ;
     * - un type avec une seule IPv4 est sélectionné automatiquement ;
     * - plusieurs IPv4 exigent un choix explicite.
     */
    LaunchedEffect(
        selectedListenType,
        availableListenAddresses,
        serverRunning
    ) {
        if (serverRunning) {
            return@LaunchedEffect
        }

        if (selectedListenType == ListenInterfaceType.ALL) {
            selectedListenAddress =
                NetworkInterfaceResolver.ALL_INTERFACES_ADDRESS
            return@LaunchedEffect
        }

        val candidates =
            availableListenAddresses.filter {
                it.type == selectedListenType
            }

        if (
            candidates.none {
                it.address == selectedListenAddress
            }
        ) {
            selectedListenAddress =
                if (candidates.size == 1) {
                    candidates.first().address
                } else {
                    ""
                }
        }
    }

    /*
     * Si une Activity est recréée pendant qu'un serveur est déjà actif,
     * availableListenAddresses peut arriver après refreshState(). Dès qu'elle
     * est disponible, retrouver le type correspondant à l'adresse réellement
     * utilisée par le Service.
     */
    LaunchedEffect(
        serverRunning,
        availableListenAddresses,
        usbService?.listenAddress
    ) {
        val service = usbService ?: return@LaunchedEffect

        if (!serverRunning) {
            return@LaunchedEffect
        }

        val activeAddress = service.listenAddress

        if (
            activeAddress ==
            NetworkInterfaceResolver.ALL_INTERFACES_ADDRESS
        ) {
            selectedListenTypeName =
                ListenInterfaceType.ALL.name
        } else {
            availableListenAddresses
                .firstOrNull {
                    it.address == activeAddress
                }
                ?.let {
                    selectedListenTypeName =
                        it.type.name
                    selectedListenAddress =
                        it.address
                }
        }
    }

    /*
     * Protection active : si l'interface réellement utilisée disparaît après
     * le démarrage (VPN coupé, Wi-Fi perdu, câble Ethernet retiré), arrêter le
     * serveur au lieu de laisser l'utilisateur croire qu'il écoute encore sur
     * cette interface.
     */
    LaunchedEffect(
        serverRunning,
        selectedListenType,
        selectedListenAddress,
        usbService
    ) {
        if (
            !serverRunning ||
            selectedListenType == ListenInterfaceType.ALL ||
            selectedListenAddress.isBlank()
        ) {
            return@LaunchedEffect
        }

        while (serverRunning) {
            delay(2000)

            val stillAvailable =
                NetworkInterfaceResolver.isListenAddressAvailable(
                    context = context,
                    type = selectedListenType,
                    address = selectedListenAddress
                )

            if (!stillAvailable) {
                val service = usbService

                if (service != null && service.serverRunning) {
                    isStopping = true
                    service.stopServer()
                    isStopping = false
                }

                serverRunning = false
                boundDevices = emptySet()

                addLog(
                    message = "USB/IP server stopped",
                    level = 3
                )

                Toast.makeText(
                    context,
                    serverStoppedNetworkLostText(context),
                    Toast.LENGTH_LONG
                ).show()

                break
            }
        }
    }

    /*
     * Adresses réellement utiles au client :
     * - mode ALL : conserver la liste large historique des IPv4 locales
     *   affichables (VPN, Wi-Fi, Ethernet, USB/RNDIS, etc.) ;
     * - mode ciblé : afficher uniquement l'adresse sur laquelle le socket
     *   natif est réellement lié.
     */
    val statusIpAddresses =
        if (!serverRunning) {
            emptyList()
        } else {
            val activeAddress =
                usbService?.listenAddress
                    ?: selectedListenAddress

            if (
                activeAddress ==
                NetworkInterfaceResolver.ALL_INTERFACES_ADDRESS
            ) {
                allClientIpAddresses
            } else {
                availableListenAddresses
                    .filter { it.address == activeAddress }
                    .map { option ->
                        val priority = when (option.type) {
                            ListenInterfaceType.VPN -> 0
                            ListenInterfaceType.WIFI -> 1
                            ListenInterfaceType.ETHERNET -> 2
                            ListenInterfaceType.ALL -> 10
                        }

                        val typeLabel =
                            listenInterfaceTypeLabel(
                                context,
                                option.type
                            )

                        val interfaceLabel =
                            option.interfaceName
                                ?.takeIf { it.isNotBlank() }
                                ?.let { "$typeLabel • $it" }
                                ?: typeLabel

                        NetworkAddress(
                            interfaceName = interfaceLabel,
                            address = option.address,
                            priority = priority
                        )
                    }
                    .distinctBy { it.address }
                    .sortedWith(
                        compareBy<NetworkAddress> { it.priority }
                            .thenBy { it.interfaceName }
                    )
            }
        }

    // 设置native日志回调
    DisposableEffect(Unit) {
        // onLog 由 native 日志线程回调，直接更新 Compose 状态会跨线程写，
        // 切到主线程再执行
        val mainHandler = Handler(Looper.getMainLooper())

        val callback = object : LogCallback {
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
                pendingPermissionDevices - device.deviceName

            usbPermissions =
                usbPermissions - device.deviceName

            scope.launch {
                val service = currentService

                val wasBound =
                    service?.handleDeviceDetached(
                        device.deviceName
                    ) ?: false

                boundDevices =
                    service?.boundDeviceNames ?: emptySet()

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
                pendingPermissionDevices + device.deviceName

            val deviceName =
                device.productName
                    ?.takeIf { it.isNotEmpty() }
                    ?: context.getString(
                        R.string.unknown_device
                    )

            // Message canonique : la traduction reste centralisée
            // dans LogLocalizer.kt.
            addLog(
                message = "USB permission requested for $deviceName",
                level = 2
            )
        }

        permissionManager.setOnPermissionResultListener { device, granted ->
            pendingPermissionDevices =
                pendingPermissionDevices - device.deviceName

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
                    message = "USB permission granted for $deviceName",
                    level = 2
                )
            } else {
                addLog(
                    message = "USB permission denied for $deviceName",
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
                    Text(stringResource(R.string.app_title))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                actions = {
                    TextButton(onClick = { showAbout = true }) {
                        Text(stringResource(R.string.about))
                    }

                    Box {
                        TextButton(onClick = { showLanguageMenu = true }) {
                            Text(stringResource(R.string.language))
                        }

                        DropdownMenu(
                            expanded = showLanguageMenu,
                            onDismissRequest = { showLanguageMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.language_en)) },
                                onClick = {
                                    setLanguage("en")
                                    showLanguageMenu = false
                                }
                            )

                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.language_fr)) },
                                onClick = {
                                    setLanguage("fr")
                                    showLanguageMenu = false
                                }
                            )

                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.language_zh)) },
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
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                when (page) {
                    // Page 0 : contrôle du serveur (placée avant la page principale)
                    0 -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(
                                    start = 16.dp,
                                    top = 16.dp,
                                    end = 16.dp,
                                    bottom = 8.dp
                                ),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            ServerControlPanel(
                                serverRunning = serverRunning,
                                isStarting = isStarting,
                                isStopping = isStopping,
                                portText = portFieldValue,
                                onPortChange = { value ->
                                    val digitsOnly =
                                        value.text
                                            .filter { c -> c.isDigit() }
                                            .take(5)

                                    portText = digitsOnly

                                    val coercedSelectionStart =
                                        value.selection.start
                                            .coerceIn(0, digitsOnly.length)
                                    val coercedSelectionEnd =
                                        value.selection.end
                                            .coerceIn(0, digitsOnly.length)

                                    portFieldValue = TextFieldValue(
                                        text = digitsOnly,
                                        selection = TextRange(
                                            coercedSelectionStart,
                                            coercedSelectionEnd
                                        )
                                    )
                                },
                                listenType = selectedListenType,
                                availableListenAddresses = availableListenAddresses,
                                selectedListenAddress = selectedListenAddress,
                                onListenTypeChange = { newType ->
                                    selectedListenTypeName =
                                        newType.name

                                    selectedListenAddress =
                                        if (
                                            newType ==
                                            ListenInterfaceType.ALL
                                        ) {
                                            NetworkInterfaceResolver.ALL_INTERFACES_ADDRESS
                                        } else {
                                            ""
                                        }
                                },
                                onListenAddressChange = {
                                    selectedListenAddress = it
                                }
                            )
                        }
                    }

                    // Page 1 : statut et périphériques USB — page principale
                    1 -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(
                                    start = 16.dp,
                                    top = 16.dp,
                                    end = 16.dp,
                                    bottom = 8.dp
                                ),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            ServerActionButton(
                                serverRunning = serverRunning,
                                isStarting = isStarting,
                                isStopping = isStopping,
                                canStart =
                                    validPort != null &&
                                        (
                                            selectedListenType ==
                                                ListenInterfaceType.ALL ||
                                                selectedListenAddress.isNotBlank()
                                        ),
                                onStart = {
                                    val port =
                                        validPort
                                            ?: return@ServerActionButton

                                    val service = usbService

                                    if (service == null) {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.service_not_ready),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        return@ServerActionButton
                                    }

                                    if (!service.nativeReady) {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.native_init_failed),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        return@ServerActionButton
                                    }

                                    /*
                                     * Revalider l'interface juste avant le
                                     * démarrage pour éviter tout fallback
                                     * silencieux vers 0.0.0.0.
                                     */
                                    val listenAddress =
                                        NetworkInterfaceResolver.resolveListenAddress(
                                            context = context,
                                            type = selectedListenType,
                                            preferredAddress =
                                                selectedListenAddress
                                                    .takeIf { it.isNotBlank() }
                                        )

                                    if (listenAddress == null) {
                                        Toast.makeText(
                                            context,
                                            listenInterfaceUnavailableText(context),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        return@ServerActionButton
                                    }

                                    if (
                                        !NetworkInterfaceResolver.isListenAddressAvailable(
                                            context = context,
                                            type = selectedListenType,
                                            address = listenAddress
                                        )
                                    ) {
                                        Toast.makeText(
                                            context,
                                            listenInterfaceUnavailableText(context),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        return@ServerActionButton
                                    }

                                    isStarting = true

                                    scope.launch {
                                        val success =
                                            service.startServer(
                                                port = port,
                                                listenAddress = listenAddress
                                            )

                                        isStarting = false

                                        if (success) {
                                            selectedListenAddress =
                                                listenAddress
                                            serverRunning = true
                                        }
                                    }
                                },
                                onStop = {
                                    val service = usbService

                                    if (service == null) {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.service_not_ready),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        return@ServerActionButton
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
                                serverRunning = serverRunning,
                                boundCount = boundDevices.size,
                                ipAddresses = statusIpAddresses,
                                port =
                                    usbService?.port
                                        ?: validPort
                                        ?: 0
                            )

                            DeviceListSection(
                                devices = devices,
                                boundDevices = boundDevices,
                                busyDevices = busyDevices,
                                usbPermissions = usbPermissions,
                                pendingPermissionDevices = pendingPermissionDevices,
                                serverRunning = serverRunning,
                                getBusid = { usbService?.getBusid(it) },
                                onAuthorizeDevice = { device ->
                                    if (isNonShareableUsbDevice(device)) {
                                        return@DeviceListSection
                                    }

                                    runWithCameraPermission(
                                        device = device,
                                        action = CAMERA_ACTION_AUTHORIZE
                                    ) {
                                        requestUsbPermissionOnly(device)
                                    }
                                },
                                onBindDevice = { device ->
                                    if (isNonShareableUsbDevice(device)) {
                                        return@DeviceListSection
                                    }

                                    if (!serverRunning) {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.please_start_server),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        return@DeviceListSection
                                    }

                                    if (usbService == null) {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.service_not_ready),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        return@DeviceListSection
                                    }

                                    runWithCameraPermission(
                                        device = device,
                                        action = CAMERA_ACTION_BIND
                                    ) {
                                        performBind(device)
                                    }
                                },
                                onUnbindDevice = { device ->
                                    val service = usbService

                                    if (service == null) {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.service_not_ready),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        return@DeviceListSection
                                    }

                                    val deviceName =
                                        device.productName
                                            ?.takeIf { it.isNotEmpty() }
                                            ?: context.getString(R.string.unknown_device)

                                    scope.launch {
                                        busyDevices = busyDevices + device.deviceName

                                        try {
                                            val result = service.unbindDevice(device.deviceName)
                                            boundDevices = service.boundDeviceNames

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
                                },
                                onRefresh = { refreshDevices() }
                            )
                        }
                    }

                    // Page 2 : journal
                    2 -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(
                                    start = 16.dp,
                                    top = 16.dp,
                                    end = 16.dp,
                                    bottom = 8.dp
                                )
                        ) {
                            LogSection(
                                logMessages = logMessages,
                                onClear = { logViewModel.clear() },
                                onViewFullLog = { showFullLog = true },
                                onCopyLog = {
                                    val clipboard =
                                        context.getSystemService(
                                            Context.CLIPBOARD_SERVICE
                                        ) as ClipboardManager

                                    clipboard.setPrimaryClip(
                                        ClipData.newPlainText(
                                            "Log",
                                            logMessages.joinToString("\n")
                                        )
                                    )

                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.log_copied),
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
                pageCount = 3,
                currentPage = pagerState.currentPage,
                onPageSelected = { page ->
                    scope.launch {
                        pagerState.animateScrollToPage(page)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 8.dp)
            )
        }
    }

    if (showFullLog) {
        FullLogDialog(
            logMessages = logMessages,
            onDismiss = { showFullLog = false }
        )
    }

    if (showAbout) {
        val version = try {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName ?: "unknown"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get package info", e)
            "unknown"
        }

        val githubUrl = "https://github.com/yunsmall/Android-Usbipdcpp"

        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text(stringResource(R.string.about_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.about_description))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.about_version, version))
                    Text(stringResource(R.string.about_license))
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(githubUrl)
                            )
                            context.startActivity(intent)
                        }
                    ) {
                        Text(
                            stringResource(R.string.about_github),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) {
                    Text(stringResource(R.string.close))
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
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(pageCount) { page ->
            IconButton(onClick = { onPageSelected(page) }) {
                Box(
                    modifier = Modifier
                        .size(if (currentPage == page) 10.dp else 8.dp)
                        .background(
                            color = if (currentPage == page) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = RoundedCornerShape(50)
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
    portText: TextFieldValue,
    onPortChange: (TextFieldValue) -> Unit,
    listenType: ListenInterfaceType,
    availableListenAddresses: List<ListenAddressOption>,
    selectedListenAddress: String,
    onListenTypeChange: (ListenInterfaceType) -> Unit,
    onListenAddressChange: (String) -> Unit
) {
    val context = LocalContext.current

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.server_control),
                style = MaterialTheme.typography.titleMedium
            )

            /*
             * Sélection de l'interface d'écoute.
             *
             * "Toutes" reste séparé en haut et correspond à 0.0.0.0.
             * VPN / Wi-Fi / Ethernet sont ensuite présentés chacun dans leur
             * propre bloc, avec une puce par IPv4 réellement disponible.
             *
             * Une seule puce peut être sélectionnée à la fois.
             */
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = listenInterfaceFieldLabel(context),
                    style = MaterialTheme.typography.titleSmall
                )

                val canChangeListenInterface =
                    !serverRunning &&
                        !isStarting &&
                        !isStopping

                /*
                 * Choix global : écoute sur toutes les interfaces IPv4.
                 */
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                enabled = canChangeListenInterface
                            ) {
                                onListenTypeChange(
                                    ListenInterfaceType.ALL
                                )
                            }
                            .padding(
                                horizontal = 12.dp,
                                vertical = 8.dp
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected =
                                listenType ==
                                    ListenInterfaceType.ALL,
                            onClick = {
                                if (canChangeListenInterface) {
                                    onListenTypeChange(
                                        ListenInterfaceType.ALL
                                    )
                                }
                            },
                            enabled = canChangeListenInterface
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        Text(
                            text = allInterfacesLabel(context),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(
                            MaterialTheme.colorScheme.outlineVariant
                        )
                )

                /*
                 * Un bloc distinct par transport. Chaque IPv4 est directement
                 * une option radio : aucun menu d'adresse supplémentaire.
                 */
                listOf(
                    ListenInterfaceType.VPN,
                    ListenInterfaceType.WIFI,
                    ListenInterfaceType.ETHERNET
                ).forEach { type ->
                    val addresses =
                        availableListenAddresses
                            .filter { it.type == type }
                            .distinctBy { it.address }
                            .sortedWith(
                                compareBy<ListenAddressOption> {
                                    it.interfaceName.orEmpty()
                                }.thenBy {
                                    it.address
                                }
                            )

                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement =
                                Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = listenInterfaceTypeLabel(
                                    context,
                                    type
                                ),
                                style =
                                    MaterialTheme.typography.titleSmall
                            )

                            if (addresses.isEmpty()) {
                                Text(
                                    text = noListenAddressText(context),
                                    style =
                                        MaterialTheme.typography.bodyMedium,
                                    color =
                                        MaterialTheme.colorScheme
                                            .onSurfaceVariant
                                )
                            } else {
                                addresses.forEach { option ->
                                    val isSelected =
                                        listenType == type &&
                                            selectedListenAddress ==
                                                option.address

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(
                                                enabled =
                                                    canChangeListenInterface
                                            ) {
                                                /*
                                                 * Le callback de type remet
                                                 * d'abord l'adresse à vide ;
                                                 * le callback suivant fixe
                                                 * immédiatement l'IPv4 choisie.
                                                 */
                                                onListenTypeChange(type)
                                                onListenAddressChange(
                                                    option.address
                                                )
                                            }
                                            .padding(vertical = 2.dp),
                                        verticalAlignment =
                                            Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = isSelected,
                                            onClick = {
                                                if (
                                                    canChangeListenInterface
                                                ) {
                                                    onListenTypeChange(type)
                                                    onListenAddressChange(
                                                        option.address
                                                    )
                                                }
                                            },
                                            enabled =
                                                canChangeListenInterface
                                        )

                                        Spacer(
                                            modifier =
                                                Modifier.width(6.dp)
                                        )

                                        Text(
                                            text = option.interfaceName
                                                ?.takeIf { it.isNotBlank() }
                                                ?.let { interfaceName ->
                                                    "$interfaceName • ${option.address}"
                                                }
                                                ?: option.address,
                                            style =
                                                MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(
                        MaterialTheme.colorScheme.outlineVariant
                    )
            )

            val portEditable =
                !serverRunning &&
                    !isStarting &&
                    !isStopping

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "${stringResource(R.string.port)} TCP",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (portEditable) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )

                    Text(
                        text = defaultPortText(context),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                OutlinedTextField(
                    value = portText,
                    onValueChange = onPortChange,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    modifier = Modifier
                        .width(96.dp)
                        .onFocusChanged { focusState ->
                            if (
                                focusState.isFocused &&
                                    portText.text.isNotEmpty() &&
                                    portText.selection != TextRange(
                                        0,
                                        portText.text.length
                                    )
                            ) {
                                onPortChange(
                                    portText.copy(
                                        selection = TextRange(
                                            0,
                                            portText.text.length
                                        )
                                    )
                                )
                            }
                        },
                    enabled = portEditable,
                    singleLine = true,
                    isError =
                        portText.text.isNotEmpty() &&
                            (
                                portText.text.toIntOrNull()
                                    ?.let { it !in 1024..65535 }
                                    ?: true
                            ),
                    textStyle =
                        MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = "(1024 - 65535)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ServerActionButton(
    serverRunning: Boolean,
    isStarting: Boolean,
    isStopping: Boolean,
    canStart: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    if (serverRunning || isStopping) {
        Button(
            onClick = onStop,
            enabled = !isStopping,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            )
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isStopping) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onError
                    )
                    Text(stringResource(R.string.stopping))
                } else {
                    Text(stringResource(R.string.stop_server))
                }
            }
        }
    } else {
        Button(
            onClick = onStart,
            enabled = !isStarting && canStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isStarting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Text(stringResource(R.string.starting))
                } else {
                    Text(stringResource(R.string.start_server))
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
    var showCopyMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = serverRunning && ipAddresses.isNotEmpty()
                ) { showCopyMenu = true },
            colors = CardDefaults.cardColors(
                containerColor = if (serverRunning) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(
                            if (serverRunning) Color.Green else Color.Red,
                            RoundedCornerShape(50)
                        )
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = if (serverRunning) {
                            stringResource(R.string.server_running)
                        } else {
                            stringResource(R.string.server_stopped)
                        },
                        style = MaterialTheme.typography.titleMedium
                    )

                    if (serverRunning) {
                        ipAddresses.forEach { networkAddress ->
                            Text(
                                text = "${networkAddress.interfaceName} • " +
                                    stringResource(
                                        R.string.address,
                                        networkAddress.address,
                                        port
                                    ),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        Text(
                            text = stringResource(
                                R.string.devices_bound,
                                boundCount
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        DropdownMenu(
            expanded = showCopyMenu,
            onDismissRequest = { showCopyMenu = false }
        ) {
            val clipboard =
                context.getSystemService(
                    Context.CLIPBOARD_SERVICE
                ) as ClipboardManager

            ipAddresses.forEach { networkAddress ->
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

            if (ipAddresses.isNotEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text(stringResource(R.string.copy_port, port))
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
        modifier = Modifier
            .fillMaxWidth()
            .weight(1.5f, fill = false)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.usb_devices),
                    style = MaterialTheme.typography.titleMedium
                )

                TextButton(onClick = onRefresh) {
                    Text(stringResource(R.string.refresh))
                }
            }

            if (devices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.no_devices),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = devices.entries.toList(),
                        key = { it.key }
                    ) { entry ->
                        val device = entry.value
                        val isBound = boundDevices.contains(device.deviceName)
                        val hasUsbPermission =
                            usbPermissions[device.deviceName] == true
                        val isPermissionPending =
                            pendingPermissionDevices.contains(device.deviceName)
                        val busid = getBusid(device.deviceName)

                        DeviceItem(
                            device = device,
                            isBound = isBound,
                            isBusy = busyDevices.contains(device.deviceName),
                            hasUsbPermission = hasUsbPermission,
                            isPermissionPending = isPermissionPending,
                            busid = busid,
                            canBind =
                                serverRunning &&
                                    hasUsbPermission &&
                                    !isBound &&
                                    !isNonShareableUsbDevice(device),
                            onAuthorize = { onAuthorizeDevice(device) },
                            onBind = { onBindDevice(device) },
                            onUnbind = { onUnbindDevice(device) }
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
    val isUsbHub = isUsbHubDevice(device)
    val isUsbBillboard = isUsbBillboardDevice(device)
    val blacklistEntry = UsbDeviceBlacklist.find(device)
    val isBlacklisted = blacklistEntry != null
    val isNonShareable = isUsbHub || isUsbBillboard || isBlacklisted

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.productName
                    ?.takeIf { it.isNotEmpty() }
                    ?: stringResource(R.string.unknown_device),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = "VID: ${device.vendorId.toString(16).uppercase()}, " +
                    "PID: ${device.productId.toString(16).uppercase()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = when {
                    isUsbHub -> stringResource(R.string.usb_hub_not_shareable)
                    isUsbBillboard -> stringResource(R.string.usb_billboard_not_shareable)
                    isBlacklisted -> stringResource(R.string.usb_blacklisted_not_shareable)
                    hasUsbPermission -> stringResource(R.string.usb_permission_granted)
                    else -> stringResource(R.string.usb_permission_required)
                },
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    isNonShareable -> MaterialTheme.colorScheme.onSurfaceVariant
                    hasUsbPermission -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.error
                }
            )

            if (
                isBlacklisted &&
                blacklistEntry?.reason?.isNotBlank() == true
            ) {
                Text(
                    text = blacklistEntry.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (busid != null) {
                Text(
                    text = "BUSID: $busid",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        when {
            // Si un périphérique de cette classe avait été associé par une
            // ancienne version, conserver la possibilité de le dissocier.
            isBound -> {
                TextButton(
                    onClick = onUnbind,
                    modifier = Modifier.height(36.dp)
                ) {
                    Text(
                        stringResource(R.string.unbind),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            isNonShareable -> {
                // Aucun bouton : le statut explique pourquoi.
            }

            isBusy || isPermissionPending -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            }

            !hasUsbPermission -> {
                Button(
                    onClick = onAuthorize,
                    modifier = Modifier.height(36.dp)
                ) {
                    Text(
                        stringResource(R.string.authorize),
                        fontSize = 12.sp
                    )
                }
            }

            else -> {
                Button(
                    onClick = onBind,
                    modifier = Modifier.height(36.dp),
                    enabled = canBind
                ) {
                    Text(
                        stringResource(R.string.bind),
                        fontSize = 12.sp
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
        modifier = Modifier
            .fillMaxWidth()
            .weight(1.5f)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.log),
                    style = MaterialTheme.typography.titleMedium
                )

                Row {
                    TextButton(onClick = onViewFullLog) {
                        Text(stringResource(R.string.expand))
                    }
                    TextButton(onClick = onCopyLog) {
                        Text(stringResource(R.string.copy))
                    }
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.clear))
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 150.dp)
                    .weight(1f)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(8.dp)
            ) {
                if (logMessages.isEmpty()) {
                    Text(
                        stringResource(R.string.no_logs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val scrollState = rememberScrollState()

                    LaunchedEffect(logMessages.size) {
                        scrollState.animateScrollTo(scrollState.maxValue)
                    }

                    Text(
                        text = logMessages.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
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
    val scrollState = rememberScrollState()

    LaunchedEffect(logMessages.size) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.log_messages)) },
        text = {
            SelectionContainer {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(scrollState)
                ) {
                    Text(
                        text = logMessages.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}
