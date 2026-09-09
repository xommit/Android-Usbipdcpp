#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>

#include <usbipdcpp/Device.h>
#include <usbipdcpp/utils/StringPool.h>
#include <usbipdcpp/virtual_device/VirtualInterfaceHandler.h>
#include <usbipdcpp/virtual_device/MscConstants.h>

namespace android_usbip {

inline constexpr char kVirtualOpticalBusId[] = "99-1";
inline constexpr std::uint32_t kOpticalBlockSize = 2048;

enum class OpticalImageValidationResult : std::uint8_t {
    Valid,
    Invalid,
    IoError,
};

OpticalImageValidationResult validate_optical_image_fd(int fd);

class OpticalMediaSource {
public:
    OpticalMediaSource() = default;
    ~OpticalMediaSource();

    OpticalMediaSource(const OpticalMediaSource&) = delete;
    OpticalMediaSource& operator=(const OpticalMediaSource&) = delete;

    bool mount_from_fd(int fd);
    void eject();

    [[nodiscard]] bool media_present() const;
    [[nodiscard]] std::uint64_t size_bytes() const;
    [[nodiscard]] std::uint64_t block_count() const;

    bool read_bytes(std::uint64_t offset, std::uint8_t* destination, std::size_t length) const;

private:
    mutable std::mutex mutex_;
    int fd_ = -1;
    std::uint64_t size_bytes_ = 0;
};

class VirtualOpticalDriveHandler final : public usbipdcpp::VirtualInterfaceHandler {
public:
    VirtualOpticalDriveHandler(
        usbipdcpp::UsbInterface& handle_interface,
        usbipdcpp::StringPool& string_pool,
        std::shared_ptr<OpticalMediaSource> media
    );

    void on_new_connection(usbipdcpp::Session& current_session, usbipdcpp::error_code& ec) override;
    void on_disconnection(usbipdcpp::error_code& ec) override;

    void handle_bulk_transfer(
        std::uint32_t seqnum,
        const usbipdcpp::UsbEndpoint& ep,
        std::uint32_t transfer_flags,
        std::uint32_t transfer_buffer_length,
        usbipdcpp::TransferHandle transfer,
        std::error_code& ec
    ) override;

    void handle_non_standard_request_type_control_urb(
        std::uint32_t seqnum,
        const usbipdcpp::UsbEndpoint& ep,
        std::uint32_t transfer_flags,
        std::uint32_t transfer_buffer_length,
        const usbipdcpp::SetupPacket& setup_packet,
        usbipdcpp::TransferHandle transfer,
        std::error_code& ec
    ) override;

    void request_clear_feature(std::uint16_t feature_selector, std::uint32_t* status) override;
    void request_endpoint_clear_feature(
        std::uint16_t feature_selector,
        std::uint8_t ep_address,
        std::uint32_t* status
    ) override;
    std::uint8_t request_get_interface(std::uint32_t* status) override;
    void request_set_interface(std::uint16_t alternate_setting, std::uint32_t* status) override;
    std::uint16_t request_get_status(std::uint32_t* status) override;
    std::uint16_t request_endpoint_get_status(std::uint8_t ep_address, std::uint32_t* status) override;
    void request_set_feature(std::uint16_t feature_selector, std::uint32_t* status) override;
    void request_endpoint_set_feature(
        std::uint16_t feature_selector,
        std::uint8_t ep_address,
        std::uint32_t* status
    ) override;
    [[nodiscard]] usbipdcpp::data_type get_class_specific_descriptor() override;

private:
    enum class State : std::uint8_t {
        Idle,
        DataInBuffer,
        DataInMedia,
        Status,
    };

    struct SenseState {
        std::uint8_t key = 0;
        std::uint8_t asc = 0;
        std::uint8_t ascq = 0;
    };

    void reset_transport();
    void process_cbw(const usbipdcpp::CBW& cbw);
    void prepare_command();

    void set_sense(std::uint8_t key, std::uint8_t asc, std::uint8_t ascq);
    void clear_sense();
    void fail_command(std::uint8_t key, std::uint8_t asc, std::uint8_t ascq);

    void begin_buffer_response(std::vector<std::uint8_t> data);
    void begin_media_read(std::uint64_t lba, std::uint64_t block_count);

    [[nodiscard]] std::vector<std::uint8_t> make_inquiry() const;
    [[nodiscard]] std::vector<std::uint8_t> make_request_sense();
    [[nodiscard]] std::vector<std::uint8_t> make_mode_sense_6() const;
    [[nodiscard]] std::vector<std::uint8_t> make_mode_sense_10() const;
    [[nodiscard]] std::vector<std::uint8_t> make_read_capacity_10() const;
    [[nodiscard]] std::vector<std::uint8_t> make_read_toc(bool msf) const;
    [[nodiscard]] std::vector<std::uint8_t> make_get_configuration() const;
    [[nodiscard]] std::vector<std::uint8_t> make_get_event_status() const;
    [[nodiscard]] std::vector<std::uint8_t> make_read_disc_information() const;

    void send_buffer_data(
        std::uint32_t seqnum,
        std::uint32_t transfer_buffer_length,
        usbipdcpp::TransferHandle transfer
    );
    void send_media_data(
        std::uint32_t seqnum,
        std::uint32_t transfer_buffer_length,
        usbipdcpp::TransferHandle transfer
    );
    void send_csw(
        std::uint32_t seqnum,
        usbipdcpp::TransferHandle transfer
    );
    void send_stall(std::uint32_t seqnum);

    static void store_msf_address(std::uint8_t* destination, std::uint64_t lba);

    std::shared_ptr<OpticalMediaSource> media_;
    State state_ = State::Idle;
    usbipdcpp::CBW current_cbw_{};
    std::vector<std::uint8_t> data_buffer_;
    std::size_t data_offset_ = 0;
    std::uint64_t media_offset_ = 0;
    std::uint64_t media_remaining_ = 0;
    std::uint32_t data_residue_ = 0;
    bool command_failed_ = false;
    bool prevent_removal_ = false;
    SenseState sense_{};
};

std::shared_ptr<usbipdcpp::UsbDevice> create_virtual_optical_device(
    usbipdcpp::StringPool& string_pool,
    const std::shared_ptr<OpticalMediaSource>& media
);

} // namespace android_usbip
