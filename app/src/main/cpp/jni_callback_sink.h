#pragma once

#include <spdlog/sinks/base_sink.h>
#include <spdlog/spdlog.h>
#include <functional>
#include <mutex>
#include <string>
#include <utility>

// 自定义sink，通过JNI回调把日志传到Kotlin
// 这里只传递原始日志消息（payload），不再使用 spdlog formatter。
// 时间戳和日志级别由 Android/Kotlin 侧统一格式化，避免重复显示。
template<typename Mutex>
class jni_callback_sink : public spdlog::sinks::base_sink<Mutex> {
public:
    using callback_fn =
        std::function<void(spdlog::level::level_enum, const std::string&)>;

    explicit jni_callback_sink(callback_fn callback)
        : callback_(std::move(callback)) {}

protected:
    void sink_it_(const spdlog::details::log_msg& msg) override {
        if (!callback_) {
            return;
        }

        // msg.payload contient uniquement le texte du message émis par spdlog,
        // sans heure, sans niveau et sans formatage du sink.
        const std::string message(
            msg.payload.data(),
            msg.payload.size()
        );

        // Le niveau est transmis séparément au callback JNI.
        callback_(msg.level, message);
    }

    void flush_() override {}

private:
    callback_fn callback_;
};

using jni_callback_sink_mt = jni_callback_sink<std::mutex>;
