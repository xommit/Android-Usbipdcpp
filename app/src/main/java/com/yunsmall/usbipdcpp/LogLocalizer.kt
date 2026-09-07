package com.yunsmall.usbipdcpp

import android.content.Context

/**
 * Converts native/runtime log messages into Android string resources.
 *
 * The native project may emit messages in English or Chinese.
 * This class recognizes the known native messages and lets Android
 * display them using the selected app language through values/,
 * values-fr/, values-zh/, etc.
 *
 * Unknown messages are returned unchanged so that diagnostic
 * information is never lost.
 */
object LogLocalizer {

    fun localize(
        context: Context,
        sourceMessage: String
    ): String {
        val message = sourceMessage.trim()

        if (message.isEmpty()) return message

        Regex(
            """^Found\s+(\d+)\s+USB device\(s\)$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(message)?.let { match ->
            val count = match.groupValues[1].toIntOrNull() ?: return@let
            return context.getString(R.string.log_devices_found, count)
        }

        Regex(
            """^USB permission requested for\s+(.+)$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(message)?.let { match ->
            return context.getString(
                R.string.log_usb_permission_requested,
                match.groupValues[1]
            )
        }

        Regex(
            """^USB permission granted for\s+(.+)$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(message)?.let { match ->
            return context.getString(
                R.string.log_usb_permission_granted,
                match.groupValues[1]
            )
        }

        Regex(
            """^USB permission denied for\s+(.+)$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(message)?.let { match ->
            return context.getString(
                R.string.log_usb_permission_denied,
                match.groupValues[1]
            )
        }

        /*
         * Ancien format JNI :
         * Starting USB/IP server on port 3240
         */
        Regex(
            """^Starting USB/IP server on port\s+(\d+)$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(message)?.let { match ->
            val port = match.groupValues[1].toIntOrNull() ?: return@let
            return context.getString(R.string.log_server_starting, port)
        }

        /*
         * Nouveau format JNI avec bind réel :
         * Starting USB/IP server on 10.8.0.2:3240
         *
         * On réutilise la chaîne localisée existante de démarrage et on
         * ajoute simplement l'adresse technique, qui n'a pas à être traduite.
         */
        Regex(
            """^Starting USB/IP server on\s+(.+):(\d+)$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(message)?.let { match ->
            val address = match.groupValues[1].trim()
            val port = match.groupValues[2].toIntOrNull() ?: return@let
            return "${context.getString(R.string.log_server_starting, port)} • $address:$port"
        }

        /*
         * Nouveau format après démarrage réussi :
         * Server started successfully on 10.8.0.2:3240
         */
        Regex(
            """^Server started successfully on\s+(.+):(\d+)$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(message)?.let { match ->
            val address = match.groupValues[1].trim()
            val port = match.groupValues[2].toIntOrNull() ?: return@let
            return "${context.getString(R.string.log_server_started)} • $address:$port"
        }

        Regex(
            """^Listening on\s+(.+):(\d+)$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(message)?.let { match ->
            val port = match.groupValues[2].toIntOrNull() ?: return@let
            return context.getString(
                R.string.log_listening,
                match.groupValues[1],
                port
            )
        }

        Regex(
            """^Binding USB device:\s*fd=(\d+),\s*vid=(0x[0-9A-Fa-f]+),\s*pid=(0x[0-9A-Fa-f]+)$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(message)?.let { match ->
            val fd = match.groupValues[1].toIntOrNull() ?: return@let
            return context.getString(
                R.string.log_binding_usb_device,
                fd,
                match.groupValues[2],
                match.groupValues[3]
            )
        }

        Regex(
            """^(?:无法获取设备当前的配置描述符|Failed to get current device configuration descriptor):\s*(.+)$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(message)?.let { match ->
            return context.getString(
                R.string.log_current_config_descriptor_failed,
                match.groupValues[1]
            )
        }

        Regex(
            """^bind_host_device_with_wrapped_fd failed:\s*(\d+)$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(message)?.let { match ->
            val code = match.groupValues[1].toIntOrNull() ?: return@let
            return context.getString(
                R.string.log_bind_host_device_failed,
                code
            )
        }

        Regex(
            """^设备\s+(.+?)\s+已添加到可用列表\s*\(fd=(\d+)\)$"""
        ).matchEntire(message)?.let { match ->
            val fd = match.groupValues[2].toIntOrNull() ?: return@let
            return context.getString(
                R.string.log_device_added_available,
                match.groupValues[1],
                fd
            )
        }

        Regex(
            """^设备状态:\s*可用\s*(\d+)\s*个\s*\[(.*?)\]\s*\|\s*使用中\s*(\d+)\s*个\s*\[(.*?)\]$"""
        ).matchEntire(message)?.let { match ->
            val available = match.groupValues[1].toIntOrNull() ?: return@let
            val inUse = match.groupValues[3].toIntOrNull() ?: return@let
            return context.getString(
                R.string.log_device_status,
                available,
                match.groupValues[2],
                inUse,
                match.groupValues[4]
            )
        }

        Regex(
            """^Device bound successfully:\s*(.+)$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(message)?.let { match ->
            return context.getString(
                R.string.log_device_bound_successfully,
                match.groupValues[1]
            )
        }

        Regex(
            """^Unbinding USB device with fd=(\d+)$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(message)?.let { match ->
            val fd = match.groupValues[1].toIntOrNull() ?: return@let
            return context.getString(
                R.string.log_unbinding_usb_device,
                fd
            )
        }

        Regex(
            """^成功取消绑定设备\s+(.+?)\s+\(fd=(\d+)\)$"""
        ).matchEntire(message)?.let { match ->
            val fd = match.groupValues[2].toIntOrNull() ?: return@let
            return context.getString(
                R.string.log_device_unbound_native_successfully,
                match.groupValues[1],
                fd
            )
        }

        when (message) {
            "Server started successfully" ->
                return context.getString(R.string.log_server_started)

            "Stopping USB/IP server" ->
                return context.getString(R.string.log_server_stopping)

            "Server stopped successfully" ->
                return context.getString(R.string.log_server_stop_success)

            "USB/IP server stopped",
            "usbip服务器关闭",
            "USBIP服务器关闭",
            "usbip 服务器关闭" ->
                return context.getString(R.string.log_server_stopped)

            "Waiting for all sessions to close",
            "等待所有session关闭",
            "等待所有 session 关闭" ->
                return context.getString(R.string.log_waiting_sessions)

            "All sessions were successfully closed",
            "All sessions successfully closed",
            "All sessions closed successfully" ->
                return context.getString(R.string.log_sessions_closed)

            "Starting libusb event thread",
            "启动一个libusb device handle的libusb事件循环线程",
            "启动一个 libusb device handle 的 libusb 事件循环线程" ->
                return context.getString(R.string.log_libusb_thread_starting)

            "libusb event thread started" ->
                return context.getString(R.string.log_libusb_thread_started)

            "Waiting for libusb event thread to stop",
            "Waiting for libusb event thread to finish",
            "等待libusb事件线程结束",
            "等待 libusb 事件线程结束" ->
                return context.getString(R.string.log_libusb_thread_stopping)

            "libusb event thread stopped",
            "libusb event thread finished",
            "libusb事件线程结束",
            "libusb 事件线程结束" ->
                return context.getString(R.string.log_libusb_thread_stopped)

            "Device unbound successfully" ->
                return context.getString(
                    R.string.log_device_unbound_successfully
                )

            "USB/IP session started" ->
                return context.getString(R.string.log_session_started)

            "USB/IP session stopped" ->
                return context.getString(R.string.log_session_stopped)
        }

        matchSingleArgument(
            message,
            Regex("""^Client connected:\s*(.+)$""", RegexOption.IGNORE_CASE)
        )?.let {
            return context.getString(R.string.log_client_connected, it)
        }

        matchSingleArgument(
            message,
            Regex("""^Client disconnected:\s*(.+)$""", RegexOption.IGNORE_CASE)
        )?.let {
            return context.getString(R.string.log_client_disconnected, it)
        }

        matchSingleArgument(
            message,
            Regex("""^USB device attached:\s*(.+)$""", RegexOption.IGNORE_CASE)
        )?.let {
            return context.getString(R.string.log_device_attached, it)
        }

        matchSingleArgument(
            message,
            Regex("""^USB device detached:\s*(.+)$""", RegexOption.IGNORE_CASE)
        )?.let {
            return context.getString(R.string.log_device_detached_native, it)
        }

        matchSingleArgument(
            message,
            Regex("""^USB device bound:\s*(.+)$""", RegexOption.IGNORE_CASE)
        )?.let {
            return context.getString(R.string.log_device_bound, it)
        }

        matchSingleArgument(
            message,
            Regex("""^USB device unbound:\s*(.+)$""", RegexOption.IGNORE_CASE)
        )?.let {
            return context.getString(R.string.log_device_unbound, it)
        }

        return context.getString(R.string.log_unknown_message, message)
    }

    private fun matchSingleArgument(
        message: String,
        regex: Regex
    ): String? {
        return regex
            .matchEntire(message)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }
}
