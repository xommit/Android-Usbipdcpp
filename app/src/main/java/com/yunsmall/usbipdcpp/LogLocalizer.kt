package com.yunsmall.usbipdcpp

import android.content.Context

/**
 * Converts native/runtime log messages into Android string resources.
 *
 * Native C++ logging remains language-neutral. This class recognizes the
 * messages currently emitted by the project (including the historical
 * Chinese messages) and lets Android choose the translation from values/,
 * values-fr/, values-zh/, etc.
 *
 * Unknown messages are intentionally returned unchanged so that diagnostics
 * are never lost if the native library adds a new log line.
 */
object LogLocalizer {

    fun localize(
        context: Context,
        sourceMessage: String
    ): String {
        val message = sourceMessage.trim()

        if (message.isEmpty()) {
            return message
        }

        // Android-side device refresh message.
        Regex(
            pattern = """^Found\s+(\d+)\s+USB device\(s\)$""",
            option = RegexOption.IGNORE_CASE
        ).matchEntire(message)?.let { match ->
            val count = match.groupValues[1].toIntOrNull()
                ?: return@let
            return context.getString(
                R.string.log_devices_found,
                count
            )
        }

        // Starting USB/IP server on port 3240
        Regex(
            pattern = """^Starting USB/IP server on port\s+(\d+)$""",
            option = RegexOption.IGNORE_CASE
        ).matchEntire(message)?.let { match ->
            val port = match.groupValues[1].toIntOrNull()
                ?: return@let
            return context.getString(
                R.string.log_server_starting,
                port
            )
        }

        // Listening on 0.0.0.0:3240
        Regex(
            pattern = """^Listening on\s+(.+):(\d+)$""",
            option = RegexOption.IGNORE_CASE
        ).matchEntire(message)?.let { match ->
            val host = match.groupValues[1]
            val port = match.groupValues[2].toIntOrNull()
                ?: return@let
            return context.getString(
                R.string.log_listening,
                host,
                port
            )
        }

        // Exact messages currently observed in the native layer.
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

            "USB/IP session started" ->
                return context.getString(R.string.log_session_started)

            "USB/IP session stopped" ->
                return context.getString(R.string.log_session_stopped)
        }

        // Common messages with one textual parameter.
        matchSingleArgument(
            message = message,
            regex = Regex(
                """^Client connected:\s*(.+)$""",
                RegexOption.IGNORE_CASE
            )
        )?.let { value ->
            return context.getString(
                R.string.log_client_connected,
                value
            )
        }

        matchSingleArgument(
            message = message,
            regex = Regex(
                """^Client disconnected:\s*(.+)$""",
                RegexOption.IGNORE_CASE
            )
        )?.let { value ->
            return context.getString(
                R.string.log_client_disconnected,
                value
            )
        }

        matchSingleArgument(
            message = message,
            regex = Regex(
                """^USB device attached:\s*(.+)$""",
                RegexOption.IGNORE_CASE
            )
        )?.let { value ->
            return context.getString(
                R.string.log_device_attached,
                value
            )
        }

        matchSingleArgument(
            message = message,
            regex = Regex(
                """^USB device detached:\s*(.+)$""",
                RegexOption.IGNORE_CASE
            )
        )?.let { value ->
            return context.getString(
                R.string.log_device_detached_native,
                value
            )
        }

        matchSingleArgument(
            message = message,
            regex = Regex(
                """^USB device bound:\s*(.+)$""",
                RegexOption.IGNORE_CASE
            )
        )?.let { value ->
            return context.getString(
                R.string.log_device_bound,
                value
            )
        }

        matchSingleArgument(
            message = message,
            regex = Regex(
                """^USB device unbound:\s*(.+)$""",
                RegexOption.IGNORE_CASE
            )
        )?.let { value ->
            return context.getString(
                R.string.log_device_unbound,
                value
            )
        }

        // Important: preserve unknown native diagnostics as-is.
        // Do not hide or mistranslate new C++ messages.
        return context.getString(
            R.string.log_unknown_message,
            message
        )
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
