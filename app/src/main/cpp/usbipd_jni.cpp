#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <libusb-1.0/libusb.h>

#include <spdlog/spdlog.h>
#include <spdlog/sinks/android_sink.h>

#include <usbipdcpp/Server.h>
#include <usbipdcpp/LibusbHandler/LibusbServer.h>
#include <usbipdcpp/LibusbHandler/tools.h>

#include "jni_callback_sink.h"
#include "jni_log_callback.h"
#include "virtual_optical_drive.h"

#define LOG_TAG "UsbIpNative"

// 错误码定义 - 必须与 Kotlin 层 UsbIpNative.kt 中的常量保持一致
namespace ErrorCode {
    constexpr int SUCCESS = 0;
    constexpr int DEVICE_NOT_FOUND = 1;
    constexpr int DEVICE_IN_USE = 2;
    constexpr int DEVICE_OPEN_FAILED = 3;
    constexpr int GET_DESCRIPTOR_FAILED = 4;
    constexpr int GET_CONFIG_FAILED = 5;
    constexpr int CLAIM_INTERFACE_FAILED = 6;
    constexpr int UNKNOWN_ERROR = 99;
}

namespace VirtualOpticalMountResult {
    constexpr int SUCCESS = 0;
    constexpr int INVALID_IMAGE = 1;
    constexpr int MOUNT_FAILED = 2;
}

namespace {
    std::mutex g_server_mutex;
    std::unique_ptr<usbipdcpp::LibusbServer> g_server;
    std::unique_ptr<usbipdcpp::StringPool> g_virtual_string_pool;
    const std::shared_ptr<android_usbip::OpticalMediaSource> g_optical_media =
        std::make_shared<android_usbip::OpticalMediaSource>();
    // 原子变量：并发调用 nativeInit 时只初始化一次（实际调用路径
    // 经 nativeDispatcher 单线程串行，原子性作为防御）
    std::atomic<bool> g_initialized{false};
    std::atomic<bool> g_server_running{false};

    int toErrorCode(usbipdcpp::DeviceOperationResult result) {
        using namespace usbipdcpp;
        switch (result) {
            case DeviceOperationResult::Success: return ErrorCode::SUCCESS;
            case DeviceOperationResult::DeviceNotFound: return ErrorCode::DEVICE_NOT_FOUND;
            case DeviceOperationResult::DeviceInUse: return ErrorCode::DEVICE_IN_USE;
            case DeviceOperationResult::DeviceOpenFailed: return ErrorCode::DEVICE_OPEN_FAILED;
            case DeviceOperationResult::GetDescriptorFailed: return ErrorCode::GET_DESCRIPTOR_FAILED;
            case DeviceOperationResult::GetConfigFailed: return ErrorCode::GET_CONFIG_FAILED;
            case DeviceOperationResult::ClaimInterfaceFailed: return ErrorCode::CLAIM_INTERFACE_FAILED;
            default: return ErrorCode::UNKNOWN_ERROR;
        }
    }

    void resetVirtualDeviceState() {
        g_virtual_string_pool.reset();
    }

    bool registerVirtualOpticalDevice() {
        if (!g_server) {
            return false;
        }

        g_virtual_string_pool = std::make_unique<usbipdcpp::StringPool>();
        auto device = android_usbip::create_virtual_optical_device(
            *g_virtual_string_pool,
            g_optical_media
        );
        g_server->get_server().add_device(std::move(device));
        spdlog::info(
            "Virtual BD-ROM registered: busid={}",
            android_usbip::kVirtualOpticalBusId
        );
        return true;
    }
}

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
}

JNIEXPORT jboolean JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_nativeInit(JNIEnv* env, jobject thiz) {
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Initializing native layer");

    // compare_exchange 原子抢锁：并发调用时只有一个线程执行初始化。
    // 调用路径经 nativeDispatcher 单线程串行，此处作为并发防御
    bool expected = false;
    if (!g_initialized.compare_exchange_strong(expected, true)) {
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Already initialized");
        return JNI_TRUE;
    }

    libusb_set_option(nullptr, LIBUSB_OPTION_WEAK_AUTHORITY);

    int err = libusb_init(nullptr);
    if (err < 0) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Failed to initialize libusb: %s", libusb_strerror(err));
        // 初始化失败要复位，否则永久卡在"已初始化"状态无法重试
        g_initialized = false;
        return JNI_FALSE;
    }

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Native layer initialized successfully");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_setLogCallback(JNIEnv* env, jobject thiz, jobject callback) {
    if (callback == nullptr) {
        // null 表示清除回调：UI 销毁（应用退后台）时释放全局引用，
        // 否则旧 Activity 会被 JNI 全局引用一直持有导致泄漏
        jni_log::cleanup(env);
        return;
    }

    jobject callback_global = env->NewGlobalRef(callback);
    if (callback_global == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "NewGlobalRef failed");
        return;
    }

    jclass callback_class = env->GetObjectClass(callback);
    jmethodID log_method = env->GetMethodID(callback_class, "onLog", "(ILjava/lang/String;)V");
    env->DeleteLocalRef(callback_class);
    if (log_method == nullptr) {
        // GetMethodID 失败时 JNI 栈上挂着 NoSuchMethodError，清掉并释放引用。
        // 不重置 spdlog 与旧回调：保留旧回调继续输出日志，比静默丢日志更合理
        env->ExceptionClear();
        env->DeleteGlobalRef(callback_global);
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Failed to find onLog method");
        return;
    }

    jni_log::init(env, callback_global, log_method);

    auto android_sink = std::make_shared<spdlog::sinks::android_sink_mt>("usbipdcpp");
    auto jni_sink = std::make_shared<jni_callback_sink_mt>(jni_log::log_callback);

    auto logger = std::make_shared<spdlog::logger>("usbipdcpp", spdlog::sinks_init_list{android_sink, jni_sink});
    logger->set_level(spdlog::level::debug);
    spdlog::set_default_logger(logger);
    spdlog::set_pattern("[%H:%M:%S] [%l] %v");

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Log callback set");
}

JNIEXPORT jint JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_bindUsbDeviceNative(
    JNIEnv* env, jobject thiz, jint fd, jint vendor_id, jint product_id, jobjectArray outBusid) {

    spdlog::info("Binding USB device: fd={}, vid=0x{:04x}, pid=0x{:04x}", fd, vendor_id, product_id);

    if (!g_initialized) {
        spdlog::error("Native layer not initialized");
        return ErrorCode::UNKNOWN_ERROR;
    }

    std::lock_guard<std::mutex> lock(g_server_mutex);

    if (!g_server) {
        spdlog::error("Server not created");
        return ErrorCode::DEVICE_NOT_FOUND;
    }

    auto result = g_server->bind_host_device_with_wrapped_fd(static_cast<intptr_t>(fd));
    if (result != usbipdcpp::DeviceOperationResult::Success) {
        spdlog::error("bind_host_device_with_wrapped_fd failed: {}", static_cast<int>(result));
        return toErrorCode(result);
    }

    // 获取 busid
    // 再次 wrap fd 是 usbipdcpp 的标准用法：bind_host_device_with_wrapped_fd
    // 内部同样是"临时 wrap → 取 libusb_device → 关 handle"，且服务器是
    // lazy binding（客户端连接时才真正打开设备），这里 close 临时句柄不会
    // 影响服务器侧
    libusb_device_handle* temp_handle = nullptr;
    int err = libusb_wrap_sys_device(nullptr, static_cast<intptr_t>(fd), &temp_handle);
    if (err < 0) {
        spdlog::error("Failed to get device info for busid: {}", libusb_strerror(err));
        // 设备已绑定成功，取 busid 失败则回滚，避免 native 层残留已绑定设备
        if (g_server->unbind_host_device_by_fd(static_cast<intptr_t>(fd)) != usbipdcpp::DeviceOperationResult::Success) {
            spdlog::warn("Rollback unbind failed for fd={}", fd);
        }
        return ErrorCode::DEVICE_OPEN_FAILED;
    }

    libusb_device* dev = libusb_get_device(temp_handle);
    if (!dev) {
        spdlog::error("libusb_get_device returned nullptr");
        libusb_close(temp_handle);
        if (g_server->unbind_host_device_by_fd(static_cast<intptr_t>(fd)) != usbipdcpp::DeviceOperationResult::Success) {
            spdlog::warn("Rollback unbind failed for fd={}", fd);
        }
        return ErrorCode::DEVICE_NOT_FOUND;
    }

    std::string busid = usbipdcpp::get_device_busid(dev);
    libusb_close(temp_handle);

    spdlog::info("Device bound successfully: {}", busid);

    // 输出 busid
    if (outBusid != nullptr && env->GetArrayLength(outBusid) > 0) {
        jstring busid_str = env->NewStringUTF(busid.c_str());
        if (busid_str == nullptr) {
            // OOM 时 NewStringUTF 返回 null：写 null 进数组会让 Kotlin 侧
            // outBusid[0]!! 抛 NPE，回滚绑定并返回错误
            env->ExceptionClear();
            if (g_server->unbind_host_device_by_fd(static_cast<intptr_t>(fd)) != usbipdcpp::DeviceOperationResult::Success) {
                spdlog::warn("Rollback unbind failed for fd={}", fd);
            }
            return ErrorCode::UNKNOWN_ERROR;
        }
        env->SetObjectArrayElement(outBusid, 0, busid_str);
        env->DeleteLocalRef(busid_str);
    }

    return ErrorCode::SUCCESS;
}

JNIEXPORT jboolean JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_startServer(
    JNIEnv* env,
    jobject thiz,
    jint port,
    jstring listen_address_jstring) {

    // 防御性默认值：Kotlin 层正常情况下始终传入非空字符串，
    // 这里保留 0.0.0.0 作为兼容/安全兜底。
    std::string listen_address = "0.0.0.0";

    if (listen_address_jstring != nullptr) {
        const char* listen_address_chars =
            env->GetStringUTFChars(listen_address_jstring, nullptr);

        if (listen_address_chars == nullptr) {
            // OOM 等情况下避免继续使用空指针。
            env->ExceptionClear();
            spdlog::error("Failed to read listen address");
            return JNI_FALSE;
        }

        listen_address.assign(listen_address_chars);
        env->ReleaseStringUTFChars(
            listen_address_jstring,
            listen_address_chars
        );

        // 空字符串不应由 Kotlin 传入，但保留旧行为作为防御。
        if (listen_address.empty()) {
            listen_address = "0.0.0.0";
        }
    }

    spdlog::info(
        "Starting USB/IP server on {}:{}",
        listen_address,
        port
    );

    if (!g_initialized) {
        spdlog::error("Native layer not initialized, initializing now...");
        if (!Java_com_yunsmall_usbipdcpp_UsbIpNative_nativeInit(env, thiz)) {
            return JNI_FALSE;
        }
    }

    std::lock_guard<std::mutex> lock(g_server_mutex);

    if (g_server_running) {
        spdlog::info("Server already running");
        return JNI_TRUE;
    }

    try {
        // 解析 Kotlin 传入的真实监听地址。
        // 当前 Android UI/网络检测设计只使用 IPv4。
        const asio::ip::address address =
            asio::ip::make_address(listen_address);

        if (!address.is_v4()) {
            spdlog::error(
                "Only IPv4 listen addresses are supported: {}",
                listen_address
            );
            return JNI_FALSE;
        }

        g_server = std::make_unique<usbipdcpp::LibusbServer>();
        g_server->set_hotplug_enabled(false);

        // Register the virtual device on the same server as physical libusb devices.
        if (!registerVirtualOpticalDevice()) {
            spdlog::error("Failed to register virtual BD-ROM");
            g_server.reset();
            resetVirtualDeviceState();
            return JNI_FALSE;
        }

        // 0.0.0.0 保留原有行为；具体 IPv4 则真正限制 socket
        // 只监听该本地地址。
        asio::ip::tcp::endpoint endpoint(
            address,
            static_cast<unsigned short>(port)
        );

        // v1.0.8 起 start 不再抛异常，启动失败（如端口被占用、
        // 地址当前不存在等）通过返回值报告
        auto ec = g_server->start(endpoint);
        if (ec) {
            spdlog::error(
                "Failed to start server on {}:{}: {}",
                listen_address,
                port,
                ec.message()
            );
            // start 失败路径内部已自清理（热插拔监控、libusb 事件线程），无需 stop 直接析构
            g_server.reset();
            resetVirtualDeviceState();
            return JNI_FALSE;
        }

        g_server_running = true;
        spdlog::info(
            "Server started successfully on {}:{}",
            listen_address,
            port
        );
        return JNI_TRUE;

    } catch (const std::exception& e) {
        // make_address / make_unique 等构造路径的异常兜底。
        // start 本身从 v1.0.8 起通过 error_code 报告失败。
        spdlog::error(
            "Failed to start server on {}:{}: {}",
            listen_address,
            port,
            e.what()
        );

        if (g_server) {
            try {
                g_server->stop();
            } catch (...) {}
        }

        g_server.reset();
        resetVirtualDeviceState();
        return JNI_FALSE;
    }
}

JNIEXPORT void JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_stopServer(JNIEnv* env, jobject thiz) {

    spdlog::info("Stopping USB/IP server");

    std::lock_guard<std::mutex> lock(g_server_mutex);

    if (!g_server_running || !g_server) {
        spdlog::info("Server not running");
        return;
    }

    try {
        g_server->stop();
        g_server.reset();
        resetVirtualDeviceState();
        g_server_running = false;

        spdlog::info("Server stopped successfully");
    } catch (const std::exception& e) {
        // 停止失败也必须重置状态，否则 g_server_running 卡在 true 无法再次启动
        spdlog::error("Error stopping server: {}", e.what());
        g_server.reset();
        resetVirtualDeviceState();
        g_server_running = false;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_isServerRunning(JNIEnv* env, jobject thiz) {
    return g_server_running ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_unbindUsbDeviceNative(
    JNIEnv* env, jobject thiz, jint fd) {

    spdlog::info("Unbinding USB device with fd={}", fd);

    if (!g_initialized) {
        spdlog::error("Native layer not initialized");
        return ErrorCode::UNKNOWN_ERROR;
    }

    std::lock_guard<std::mutex> lock(g_server_mutex);

    if (!g_server) {
        spdlog::error("Server not created");
        return ErrorCode::DEVICE_NOT_FOUND;
    }

    auto result = g_server->unbind_host_device_by_fd(static_cast<intptr_t>(fd));
    int errorCode = toErrorCode(result);

    if (errorCode == ErrorCode::SUCCESS) {
        spdlog::info("Device unbound successfully");
    } else {
        spdlog::error("Failed to unbind device: {}", errorCode);
    }

    return errorCode;
}

JNIEXPORT void JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_notifyDeviceRemovedNative(
    JNIEnv* env, jobject thiz, jstring busid) {

    const char* busid_cstr = env->GetStringUTFChars(busid, nullptr);
    if (busid_cstr == nullptr) {
        // OOM：GetStringUTFChars 返回 null 并挂 OutOfMemoryError，
        // 直接构造 std::string 会崩溃，清异常后安全返回
        env->ExceptionClear();
        spdlog::error("Failed to get busid string");
        return;
    }
    std::string busid_str(busid_cstr);
    env->ReleaseStringUTFChars(busid, busid_cstr);

    spdlog::info("Notifying device removed: {}", busid_str);

    std::lock_guard<std::mutex> lock(g_server_mutex);

    if (!g_server) {
        spdlog::warn("Server not created, ignoring device removal notification");
        return;
    }

    g_server->notify_device_removed(busid_str);
}

JNIEXPORT jint JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_mountVirtualOpticalNative(
    JNIEnv* env, jobject thiz, jint fd) {
    (void) env;
    (void) thiz;

    if (fd < 0) {
        spdlog::error("Cannot mount virtual optical media: invalid fd={}", fd);
        return VirtualOpticalMountResult::MOUNT_FAILED;
    }

    const auto validation = android_usbip::validate_optical_image_fd(fd);
    if (validation == android_usbip::OpticalImageValidationResult::Invalid) {
        spdlog::warn("Rejected invalid virtual optical image from fd={}", fd);
        return VirtualOpticalMountResult::INVALID_IMAGE;
    }
    if (validation == android_usbip::OpticalImageValidationResult::IoError) {
        spdlog::error("Failed to validate virtual optical image from fd={}", fd);
        return VirtualOpticalMountResult::MOUNT_FAILED;
    }

    if (!g_optical_media->mount_from_fd(fd)) {
        spdlog::error("Failed to mount virtual optical media from fd={}", fd);
        return VirtualOpticalMountResult::MOUNT_FAILED;
    }

    spdlog::info(
        "Virtual optical media mounted: {} bytes",
        g_optical_media->size_bytes()
    );
    return VirtualOpticalMountResult::SUCCESS;
}

JNIEXPORT void JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_ejectVirtualOpticalNative(
    JNIEnv* env, jobject thiz) {
    (void) env;
    (void) thiz;

    g_optical_media->eject();
    spdlog::info("Virtual optical media ejected");
}

JNIEXPORT jboolean JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_isVirtualOpticalMediaMountedNative(
    JNIEnv* env, jobject thiz) {
    (void) env;
    (void) thiz;
    return g_optical_media->media_present() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_getVirtualOpticalMediaSizeNative(
    JNIEnv* env, jobject thiz) {
    (void) env;
    (void) thiz;
    return static_cast<jlong>(g_optical_media->size_bytes());
}

JNIEXPORT jstring JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_getVirtualOpticalBusidNative(
    JNIEnv* env, jobject thiz) {
    (void) thiz;
    return env->NewStringUTF(android_usbip::kVirtualOpticalBusId);
}

JNIEXPORT void JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_release(JNIEnv* env, jobject thiz) {
    spdlog::info("Releasing native resources");

    Java_com_yunsmall_usbipdcpp_UsbIpNative_stopServer(env, thiz);
    g_optical_media->eject();

    if (g_initialized) {
        libusb_exit(nullptr);
        g_initialized = false;
    }

    jni_log::cleanup(env);
}

} // extern "C"
