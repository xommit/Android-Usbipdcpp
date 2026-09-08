#include "virtual_optical_drive.h"

#include <algorithm>
#include <array>
#include <cerrno>
#include <cstring>
#include <limits>
#include <string>
#include <utility>

#include <sys/stat.h>
#include <unistd.h>

#include <spdlog/spdlog.h>

#include <usbipdcpp/Session.h>
#include <usbipdcpp/constant.h>
#include <usbipdcpp/usbipdcpp_core.h>
#include <usbipdcpp/virtual_device/SimpleVirtualDeviceHandler.h>

namespace android_usbip {
namespace {

constexpr std::uint8_t kScsiReadToc = 0x43;
constexpr std::uint8_t kScsiGetConfiguration = 0x46;
constexpr std::uint8_t kScsiGetEventStatusNotification = 0x4A;
constexpr std::uint8_t kScsiReadDiscInformation = 0x51;
constexpr std::uint8_t kScsiRead12 = 0xA8;
constexpr std::uint8_t kScsiSetCdSpeed = 0xBB;

constexpr std::uint8_t kSenseNotReady = 0x02;
constexpr std::uint8_t kSenseMediumError = 0x03;
constexpr std::uint8_t kSenseIllegalRequest = 0x05;

constexpr std::uint8_t kAscMediumNotPresent = 0x3A;
constexpr std::uint8_t kAscUnrecoveredReadError = 0x11;
constexpr std::uint8_t kAscInvalidCommand = 0x20;
constexpr std::uint8_t kAscLbaOutOfRange = 0x21;
constexpr std::uint8_t kAscInvalidFieldInCdb = 0x24;
constexpr std::uint8_t kAscMediumRemovalPrevented = 0x53;

constexpr std::uint16_t kBdRomProfile = 0x0040;

constexpr std::uint64_t kVolumeDescriptorStartSector = 16;
constexpr std::uint64_t kVolumeDescriptorEndSector = 100;
constexpr std::uint64_t kUdfPrimaryAnchorSector = 256;
constexpr std::size_t kUdfDescriptorTagSize = 16;
constexpr std::uint16_t kUdfAnchorTagId = 2;

// Keep validation bounded to a small set of fixed sectors so its cost does not
// depend on the total ISO size.
enum class ProbeResult : std::uint8_t {
    Match,
    NoMatch,
    IoError,
};

enum class ReadResult : std::uint8_t {
    Success,
    OutOfRange,
    IoError,
};

std::uint16_t load_le16(const std::uint8_t* data) {
    return static_cast<std::uint16_t>(data[0]) |
           (static_cast<std::uint16_t>(data[1]) << 8);
}

std::uint16_t load_be16(const std::uint8_t* data) {
    return (static_cast<std::uint16_t>(data[0]) << 8) |
           static_cast<std::uint16_t>(data[1]);
}

std::uint32_t load_le32(const std::uint8_t* data) {
    return static_cast<std::uint32_t>(data[0]) |
           (static_cast<std::uint32_t>(data[1]) << 8) |
           (static_cast<std::uint32_t>(data[2]) << 16) |
           (static_cast<std::uint32_t>(data[3]) << 24);
}

std::uint32_t load_be32(const std::uint8_t* data) {
    return (static_cast<std::uint32_t>(data[0]) << 24) |
           (static_cast<std::uint32_t>(data[1]) << 16) |
           (static_cast<std::uint32_t>(data[2]) << 8) |
           static_cast<std::uint32_t>(data[3]);
}

bool get_fd_size(int fd, std::uint64_t* size) {
    if (fd < 0 || size == nullptr) {
        return false;
    }

    struct stat info {};
    if (::fstat(fd, &info) == 0 && info.st_size > 0) {
        *size = static_cast<std::uint64_t>(info.st_size);
        return true;
    }

    const off_t current = ::lseek(fd, 0, SEEK_CUR);
    const off_t end = ::lseek(fd, 0, SEEK_END);
    if (current >= 0) {
        (void) ::lseek(fd, current, SEEK_SET);
    }
    if (end <= 0) {
        return false;
    }

    *size = static_cast<std::uint64_t>(end);
    return true;
}

ReadResult read_exact_at(
    int fd,
    std::uint64_t file_size,
    std::uint64_t offset,
    std::uint8_t* destination,
    std::size_t length
) {
    if (destination == nullptr || length == 0) {
        return length == 0 ? ReadResult::Success : ReadResult::IoError;
    }
    if (offset > file_size || length > file_size - offset) {
        return ReadResult::OutOfRange;
    }
    if (offset > static_cast<std::uint64_t>(std::numeric_limits<off_t>::max())) {
        return ReadResult::OutOfRange;
    }

    std::size_t completed = 0;
    while (completed < length) {
        const std::uint64_t absolute_offset = offset + completed;
        if (absolute_offset > static_cast<std::uint64_t>(std::numeric_limits<off_t>::max())) {
            return ReadResult::OutOfRange;
        }

        const ssize_t result = ::pread(
            fd,
            destination + completed,
            length - completed,
            static_cast<off_t>(absolute_offset)
        );
        if (result < 0 && errno == EINTR) {
            continue;
        }
        if (result < 0) {
            return ReadResult::IoError;
        }
        if (result == 0) {
            return ReadResult::OutOfRange;
        }
        completed += static_cast<std::size_t>(result);
    }

    return ReadResult::Success;
}

bool load_both_endian_16(const std::uint8_t* data, std::uint16_t* value) {
    const std::uint16_t little = load_le16(data);
    const std::uint16_t big = load_be16(data + 2);
    if (little != big) {
        return false;
    }
    if (value != nullptr) {
        *value = little;
    }
    return true;
}

bool load_both_endian_32(const std::uint8_t* data, std::uint32_t* value) {
    const std::uint32_t little = load_le32(data);
    const std::uint32_t big = load_be32(data + 4);
    if (little != big) {
        return false;
    }
    if (value != nullptr) {
        *value = little;
    }
    return true;
}

bool checked_multiply(std::uint64_t left, std::uint64_t right, std::uint64_t* result) {
    if (result == nullptr) {
        return false;
    }
    if (left != 0 && right > std::numeric_limits<std::uint64_t>::max() / left) {
        return false;
    }
    *result = left * right;
    return true;
}

bool checked_add(std::uint64_t left, std::uint64_t right, std::uint64_t* result) {
    if (result == nullptr || right > std::numeric_limits<std::uint64_t>::max() - left) {
        return false;
    }
    *result = left + right;
    return true;
}

bool validate_primary_volume_descriptor(
    const std::array<std::uint8_t, kOpticalBlockSize>& descriptor,
    std::size_t volume_space_offset,
    std::size_t logical_block_offset,
    std::size_t root_record_offset,
    std::uint64_t file_size
) {
    std::uint32_t volume_space_size = 0;
    std::uint16_t logical_block_size = 0;
    if (!load_both_endian_32(descriptor.data() + volume_space_offset, &volume_space_size) ||
        !load_both_endian_16(descriptor.data() + logical_block_offset, &logical_block_size)) {
        return false;
    }

    if (volume_space_size == 0 || logical_block_size < 512 || logical_block_size > kOpticalBlockSize ||
        (logical_block_size & (logical_block_size - 1)) != 0) {
        return false;
    }

    std::uint64_t declared_volume_bytes = 0;
    if (!checked_multiply(volume_space_size, logical_block_size, &declared_volume_bytes) ||
        declared_volume_bytes == 0 || declared_volume_bytes > file_size) {
        return false;
    }

    const std::uint8_t root_record_length = descriptor[root_record_offset];
    if (root_record_length < 34 || root_record_offset + root_record_length > descriptor.size()) {
        return false;
    }

    std::uint32_t root_extent = 0;
    std::uint32_t root_data_length = 0;
    if (!load_both_endian_32(descriptor.data() + root_record_offset + 2, &root_extent) ||
        !load_both_endian_32(descriptor.data() + root_record_offset + 10, &root_data_length) ||
        root_data_length == 0) {
        return false;
    }

    // The root entry must describe a directory and use the special root identifier.
    if ((descriptor[root_record_offset + 25] & 0x02) == 0 ||
        descriptor[root_record_offset + 32] != 1 ||
        descriptor[root_record_offset + 33] != 0) {
        return false;
    }

    std::uint64_t root_offset = 0;
    std::uint64_t root_end = 0;
    if (!checked_multiply(root_extent, logical_block_size, &root_offset) ||
        !checked_add(root_offset, root_data_length, &root_end)) {
        return false;
    }

    return root_end <= declared_volume_bytes && root_end <= file_size;
}

ProbeResult probe_iso9660_or_high_sierra(int fd, std::uint64_t file_size) {
    std::array<std::uint8_t, kOpticalBlockSize> descriptor {};

    for (std::uint64_t sector = kVolumeDescriptorStartSector;
         sector < kVolumeDescriptorEndSector;
         ++sector) {
        const std::uint64_t offset = sector * static_cast<std::uint64_t>(kOpticalBlockSize);
        const ReadResult read = read_exact_at(
            fd,
            file_size,
            offset,
            descriptor.data(),
            descriptor.size()
        );
        if (read == ReadResult::OutOfRange) {
            return ProbeResult::NoMatch;
        }
        if (read == ReadResult::IoError) {
            return ProbeResult::IoError;
        }

        // ECMA-119 / ISO 9660. Joliet, Rock Ridge and El Torito keep this PVD.
        if (std::memcmp(descriptor.data() + 1, "CD001", 5) == 0) {
            if (descriptor[0] == 1 && descriptor[6] == 1 &&
                validate_primary_volume_descriptor(descriptor, 80, 128, 156, file_size)) {
                return ProbeResult::Match;
            }
            if (descriptor[0] == 0xFF) {
                return ProbeResult::NoMatch;
            }
            continue;
        }

        // High Sierra is the predecessor to ISO 9660 and is still recognized by Linux isofs.
        if (std::memcmp(descriptor.data() + 9, "CDROM", 5) == 0 &&
            descriptor[8] == 1 && descriptor[14] == 1 &&
            validate_primary_volume_descriptor(descriptor, 88, 136, 180, file_size)) {
            return ProbeResult::Match;
        }
    }

    return ProbeResult::NoMatch;
}

std::uint16_t udf_crc16(const std::uint8_t* data, std::size_t length) {
    std::uint16_t crc = 0;
    for (std::size_t index = 0; index < length; ++index) {
        crc ^= static_cast<std::uint16_t>(data[index]) << 8;
        for (int bit = 0; bit < 8; ++bit) {
            crc = (crc & 0x8000) != 0
                ? static_cast<std::uint16_t>((crc << 1) ^ 0x1021)
                : static_cast<std::uint16_t>(crc << 1);
        }
    }
    return crc;
}

bool validate_udf_descriptor_tag(
    const std::array<std::uint8_t, kOpticalBlockSize>& descriptor,
    std::uint16_t expected_tag_id,
    std::uint64_t expected_location
) {
    if (load_le16(descriptor.data()) != expected_tag_id) {
        return false;
    }

    const std::uint16_t descriptor_version = load_le16(descriptor.data() + 2);
    if ((descriptor_version != 2 && descriptor_version != 3) || descriptor[5] != 0) {
        return false;
    }

    std::uint8_t checksum = 0;
    for (std::size_t index = 0; index < kUdfDescriptorTagSize; ++index) {
        if (index != 4) {
            checksum = static_cast<std::uint8_t>(checksum + descriptor[index]);
        }
    }
    if (checksum != descriptor[4]) {
        return false;
    }

    if (load_le32(descriptor.data() + 12) != expected_location) {
        return false;
    }

    const std::uint16_t crc_length = load_le16(descriptor.data() + 10);
    const std::uint16_t expected_crc = load_le16(descriptor.data() + 8);
    if (crc_length == 0) {
        return expected_crc == 0;
    }
    if (crc_length > descriptor.size() - kUdfDescriptorTagSize) {
        return false;
    }

    return udf_crc16(descriptor.data() + kUdfDescriptorTagSize, crc_length) == expected_crc;
}

bool extent_fits_file(
    std::uint32_t start_block,
    std::uint32_t byte_length,
    std::uint64_t total_blocks
) {
    if (byte_length == 0 || start_block >= total_blocks) {
        return false;
    }

    const std::uint64_t extent_blocks =
        (static_cast<std::uint64_t>(byte_length) + kOpticalBlockSize - 1) /
        kOpticalBlockSize;
    return extent_blocks <= total_blocks - start_block;
}

ProbeResult probe_udf_volume_sequence(
    int fd,
    std::uint64_t file_size,
    std::uint32_t start_block,
    std::uint32_t byte_length
) {
    const std::uint64_t total_blocks = file_size / kOpticalBlockSize;
    if (!extent_fits_file(start_block, byte_length, total_blocks)) {
        return ProbeResult::NoMatch;
    }

    const std::uint64_t extent_blocks =
        (static_cast<std::uint64_t>(byte_length) + kOpticalBlockSize - 1) /
        kOpticalBlockSize;
    const std::uint64_t blocks_to_probe = std::min<std::uint64_t>(extent_blocks, 32);
    std::array<std::uint8_t, kOpticalBlockSize> descriptor {};

    for (std::uint64_t index = 0; index < blocks_to_probe; ++index) {
        const std::uint64_t block = static_cast<std::uint64_t>(start_block) + index;
        const ReadResult read = read_exact_at(
            fd,
            file_size,
            block * kOpticalBlockSize,
            descriptor.data(),
            descriptor.size()
        );
        if (read == ReadResult::IoError) {
            return ProbeResult::IoError;
        }
        if (read != ReadResult::Success) {
            return ProbeResult::NoMatch;
        }

        const std::uint16_t tag_id = load_le16(descriptor.data());
        if (tag_id < 1 || tag_id > 8) {
            continue;
        }
        if (validate_udf_descriptor_tag(descriptor, tag_id, block)) {
            return ProbeResult::Match;
        }
    }

    return ProbeResult::NoMatch;
}

ProbeResult probe_udf_anchor_at(
    int fd,
    std::uint64_t file_size,
    std::uint64_t anchor_block
) {
    const std::uint64_t total_blocks = file_size / kOpticalBlockSize;
    if (anchor_block >= total_blocks || anchor_block > std::numeric_limits<std::uint32_t>::max()) {
        return ProbeResult::NoMatch;
    }

    std::array<std::uint8_t, kOpticalBlockSize> descriptor {};
    const ReadResult read = read_exact_at(
        fd,
        file_size,
        anchor_block * kOpticalBlockSize,
        descriptor.data(),
        descriptor.size()
    );
    if (read == ReadResult::IoError) {
        return ProbeResult::IoError;
    }
    if (read != ReadResult::Success ||
        !validate_udf_descriptor_tag(descriptor, kUdfAnchorTagId, anchor_block)) {
        return ProbeResult::NoMatch;
    }

    const std::uint32_t main_length = load_le32(descriptor.data() + 16);
    const std::uint32_t main_location = load_le32(descriptor.data() + 20);
    const std::uint32_t reserve_length = load_le32(descriptor.data() + 24);
    const std::uint32_t reserve_location = load_le32(descriptor.data() + 28);

    const ProbeResult main = probe_udf_volume_sequence(
        fd,
        file_size,
        main_location,
        main_length
    );
    if (main != ProbeResult::NoMatch) {
        return main;
    }

    return probe_udf_volume_sequence(
        fd,
        file_size,
        reserve_location,
        reserve_length
    );
}

ProbeResult probe_udf(int fd, std::uint64_t file_size) {
    const std::uint64_t total_blocks = file_size / kOpticalBlockSize;
    if (total_blocks <= kUdfPrimaryAnchorSector) {
        return ProbeResult::NoMatch;
    }

    std::array<std::uint64_t, 3> candidates {
        kUdfPrimaryAnchorSector,
        total_blocks - 1,
        total_blocks > 256 ? total_blocks - 257 : 0,
    };

    for (std::size_t index = 0; index < candidates.size(); ++index) {
        const std::uint64_t candidate = candidates[index];
        bool duplicate = false;
        for (std::size_t previous = 0; previous < index; ++previous) {
            if (candidates[previous] == candidate) {
                duplicate = true;
                break;
            }
        }
        if (duplicate) {
            continue;
        }

        const ProbeResult result = probe_udf_anchor_at(fd, file_size, candidate);
        if (result != ProbeResult::NoMatch) {
            return result;
        }
    }

    return ProbeResult::NoMatch;
}

std::vector<std::uint8_t> limit_response(std::vector<std::uint8_t> data, std::uint32_t limit) {
    if (data.size() > limit) {
        data.resize(limit);
    }
    return data;
}

} // namespace

OpticalImageValidationResult validate_optical_image_fd(int fd) {
    if (fd < 0) {
        return OpticalImageValidationResult::IoError;
    }

    std::uint64_t file_size = 0;
    if (!get_fd_size(fd, &file_size) || file_size == 0) {
        return OpticalImageValidationResult::IoError;
    }

    const ProbeResult iso = probe_iso9660_or_high_sierra(fd, file_size);
    if (iso == ProbeResult::Match) {
        return OpticalImageValidationResult::Valid;
    }
    if (iso == ProbeResult::IoError) {
        return OpticalImageValidationResult::IoError;
    }

    const ProbeResult udf = probe_udf(fd, file_size);
    if (udf == ProbeResult::Match) {
        return OpticalImageValidationResult::Valid;
    }
    if (udf == ProbeResult::IoError) {
        return OpticalImageValidationResult::IoError;
    }

    return OpticalImageValidationResult::Invalid;
}

OpticalMediaSource::~OpticalMediaSource() {
    eject();
}

bool OpticalMediaSource::mount_from_fd(int fd) {
    if (fd < 0) {
        return false;
    }

    const int duplicate = ::dup(fd);
    if (duplicate < 0) {
        return false;
    }

    struct stat info {};
    std::uint64_t detected_size = 0;
    if (::fstat(duplicate, &info) == 0 && info.st_size > 0) {
        detected_size = static_cast<std::uint64_t>(info.st_size);
    }

    if (detected_size == 0) {
        const off_t end = ::lseek(duplicate, 0, SEEK_END);
        if (end > 0) {
            detected_size = static_cast<std::uint64_t>(end);
        }
    }

    if (detected_size == 0) {
        ::close(duplicate);
        return false;
    }

    std::lock_guard<std::mutex> lock(mutex_);
    if (fd_ >= 0) {
        ::close(fd_);
    }
    fd_ = duplicate;
    size_bytes_ = detected_size;
    return true;
}

void OpticalMediaSource::eject() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (fd_ >= 0) {
        ::close(fd_);
        fd_ = -1;
    }
    size_bytes_ = 0;
}

bool OpticalMediaSource::media_present() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return fd_ >= 0 && size_bytes_ > 0;
}

std::uint64_t OpticalMediaSource::size_bytes() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return size_bytes_;
}

std::uint64_t OpticalMediaSource::block_count() const {
    std::lock_guard<std::mutex> lock(mutex_);
    if (fd_ < 0 || size_bytes_ == 0) {
        return 0;
    }
    return (size_bytes_ + kOpticalBlockSize - 1) / kOpticalBlockSize;
}

bool OpticalMediaSource::read_bytes(
    std::uint64_t offset,
    std::uint8_t* destination,
    std::size_t length
) const {
    if (destination == nullptr && length != 0) {
        return false;
    }

    std::lock_guard<std::mutex> lock(mutex_);
    if (fd_ < 0 || size_bytes_ == 0) {
        return false;
    }

    if (length == 0) {
        return true;
    }

    std::memset(destination, 0, length);
    if (offset >= size_bytes_) {
        return true;
    }

    const std::uint64_t available_u64 = size_bytes_ - offset;
    const std::size_t wanted = static_cast<std::size_t>(
        std::min<std::uint64_t>(available_u64, length)
    );

    std::size_t completed = 0;
    while (completed < wanted) {
        const std::uint64_t absolute_offset = offset + completed;
        if (absolute_offset > static_cast<std::uint64_t>(std::numeric_limits<off_t>::max())) {
            return false;
        }

        const ssize_t result = ::pread(
            fd_,
            destination + completed,
            wanted - completed,
            static_cast<off_t>(absolute_offset)
        );

        if (result < 0 && errno == EINTR) {
            continue;
        }
        if (result <= 0) {
            return false;
        }
        completed += static_cast<std::size_t>(result);
    }

    return true;
}

VirtualOpticalDriveHandler::VirtualOpticalDriveHandler(
    usbipdcpp::UsbInterface& handle_interface,
    usbipdcpp::StringPool& string_pool,
    std::shared_ptr<OpticalMediaSource> media
) : usbipdcpp::VirtualInterfaceHandler(handle_interface, string_pool),
    media_(std::move(media)) {
    change_string_interface(L"Virtual Optical Drive");
}

void VirtualOpticalDriveHandler::on_new_connection(
    usbipdcpp::Session& current_session,
    usbipdcpp::error_code& ec
) {
    usbipdcpp::VirtualInterfaceHandler::on_new_connection(current_session, ec);
    reset_transport();
}

void VirtualOpticalDriveHandler::on_disconnection(usbipdcpp::error_code& ec) {
    reset_transport();
    usbipdcpp::VirtualInterfaceHandler::on_disconnection(ec);
}

void VirtualOpticalDriveHandler::reset_transport() {
    state_ = State::Idle;
    current_cbw_ = {};
    data_buffer_.clear();
    data_offset_ = 0;
    media_offset_ = 0;
    media_remaining_ = 0;
    data_residue_ = 0;
    command_failed_ = false;
    prevent_removal_ = false;
}

void VirtualOpticalDriveHandler::set_sense(std::uint8_t key, std::uint8_t asc, std::uint8_t ascq) {
    sense_ = SenseState{key, asc, ascq};
}

void VirtualOpticalDriveHandler::clear_sense() {
    sense_ = SenseState{};
}

void VirtualOpticalDriveHandler::fail_command(std::uint8_t key, std::uint8_t asc, std::uint8_t ascq) {
    set_sense(key, asc, ascq);
    command_failed_ = true;
    data_buffer_.clear();
    data_offset_ = 0;
    media_offset_ = 0;
    media_remaining_ = 0;
    state_ = State::Status;
}

void VirtualOpticalDriveHandler::begin_buffer_response(std::vector<std::uint8_t> data) {
    const std::size_t allowed = std::min<std::size_t>(data.size(), current_cbw_.dCBWDataTransferLength);
    data.resize(allowed);
    data_buffer_ = std::move(data);
    data_offset_ = 0;
    state_ = data_buffer_.empty() ? State::Status : State::DataInBuffer;
}

void VirtualOpticalDriveHandler::begin_media_read(std::uint64_t lba, std::uint64_t block_count) {
    const std::uint64_t requested_bytes = block_count * static_cast<std::uint64_t>(kOpticalBlockSize);
    const std::uint64_t allowed_bytes = std::min<std::uint64_t>(
        requested_bytes,
        current_cbw_.dCBWDataTransferLength
    );
    media_offset_ = lba * static_cast<std::uint64_t>(kOpticalBlockSize);
    media_remaining_ = allowed_bytes;
    state_ = media_remaining_ == 0 ? State::Status : State::DataInMedia;
}

std::vector<std::uint8_t> VirtualOpticalDriveHandler::make_inquiry() const {
    std::vector<std::uint8_t> data(36, 0);
    data[0] = 0x05;
    data[1] = 0x80;
    data[2] = 0x05;
    data[3] = 0x02;
    data[4] = 31;

    constexpr std::array<char, 8> vendor = {'A', 'N', 'D', 'R', 'O', 'I', 'D', ' '};
    constexpr std::array<char, 16> product = {
        'U', 'S', 'B', '/', 'I', 'P', ' ', 'B', 'D', '-', 'R', 'O', 'M', ' ', ' ', ' '
    };
    constexpr std::array<char, 4> revision = {'1', '.', '0', '0'};

    std::copy(vendor.begin(), vendor.end(), data.begin() + 8);
    std::copy(product.begin(), product.end(), data.begin() + 16);
    std::copy(revision.begin(), revision.end(), data.begin() + 32);
    return data;
}

std::vector<std::uint8_t> VirtualOpticalDriveHandler::make_request_sense() {
    std::vector<std::uint8_t> data(18, 0);
    data[0] = 0x70;
    data[2] = sense_.key;
    data[7] = 10;
    data[12] = sense_.asc;
    data[13] = sense_.ascq;
    clear_sense();
    return data;
}

std::vector<std::uint8_t> VirtualOpticalDriveHandler::make_mode_sense_6() const {
    std::vector<std::uint8_t> data(4, 0);
    data[0] = 3;
    data[2] = 0x80;
    return data;
}

std::vector<std::uint8_t> VirtualOpticalDriveHandler::make_mode_sense_10() const {
    std::vector<std::uint8_t> data(8, 0);
    usbipdcpp::put_be16(data.data(), 6);
    data[3] = 0x80;
    return data;
}

std::vector<std::uint8_t> VirtualOpticalDriveHandler::make_read_capacity_10() const {
    std::vector<std::uint8_t> data(8, 0);
    const std::uint64_t blocks = media_->block_count();
    const std::uint64_t last_lba = blocks == 0 ? 0 : blocks - 1;
    usbipdcpp::put_be32(
        data.data(),
        static_cast<std::uint32_t>(std::min<std::uint64_t>(last_lba, 0xFFFFFFFFULL))
    );
    usbipdcpp::put_be32(data.data() + 4, kOpticalBlockSize);
    return data;
}

void VirtualOpticalDriveHandler::store_msf_address(std::uint8_t* destination, std::uint64_t lba) {
    const std::uint64_t frames = lba + 150;
    destination[0] = 0;
    destination[1] = static_cast<std::uint8_t>(frames / (75 * 60));
    destination[2] = static_cast<std::uint8_t>((frames / 75) % 60);
    destination[3] = static_cast<std::uint8_t>(frames % 75);
}

std::vector<std::uint8_t> VirtualOpticalDriveHandler::make_read_toc(bool msf) const {
    std::vector<std::uint8_t> data(20, 0);
    usbipdcpp::put_be16(data.data(), 18);
    data[2] = 1;
    data[3] = 1;

    data[5] = 0x14;
    data[6] = 1;
    if (msf) {
        store_msf_address(data.data() + 8, 0);
    } else {
        usbipdcpp::put_be32(data.data() + 8, 0);
    }

    data[13] = 0x14;
    data[14] = 0xAA;
    if (msf) {
        store_msf_address(data.data() + 16, media_->block_count());
    } else {
        usbipdcpp::put_be32(
            data.data() + 16,
            static_cast<std::uint32_t>(std::min<std::uint64_t>(media_->block_count(), 0xFFFFFFFFULL))
        );
    }
    return data;
}

std::vector<std::uint8_t> VirtualOpticalDriveHandler::make_get_configuration() const {
    std::vector<std::uint8_t> data(16, 0);
    usbipdcpp::put_be32(data.data(), 12);
    usbipdcpp::put_be16(data.data() + 6, media_->media_present() ? kBdRomProfile : 0);

    usbipdcpp::put_be16(data.data() + 8, 0x0000);
    data[10] = 0x03;
    data[11] = 4;
    usbipdcpp::put_be16(data.data() + 12, kBdRomProfile);
    data[14] = media_->media_present() ? 0x01 : 0x00;
    return data;
}

std::vector<std::uint8_t> VirtualOpticalDriveHandler::make_get_event_status() const {
    std::vector<std::uint8_t> data(8, 0);
    usbipdcpp::put_be16(data.data(), 6);
    data[2] = 0x04;
    data[3] = 0x10;
    data[4] = 0x00;
    data[5] = media_->media_present() ? 0x02 : 0x00;
    return data;
}

std::vector<std::uint8_t> VirtualOpticalDriveHandler::make_read_disc_information() const {
    std::vector<std::uint8_t> data(34, 0);
    usbipdcpp::put_be16(data.data(), 32);
    data[2] = 0x0E;
    data[3] = 1;
    data[4] = 1;
    data[5] = 1;
    data[6] = 1;
    return data;
}

void VirtualOpticalDriveHandler::process_cbw(const usbipdcpp::CBW& cbw) {
    current_cbw_ = cbw;
    data_buffer_.clear();
    data_offset_ = 0;
    media_offset_ = 0;
    media_remaining_ = 0;
    data_residue_ = current_cbw_.dCBWDataTransferLength;
    command_failed_ = false;
    prepare_command();
}

void VirtualOpticalDriveHandler::prepare_command() {
    const std::uint8_t opcode = current_cbw_.CBWCB[0];
    const bool data_in = (current_cbw_.bmCBWFlags & 0x80) != 0;

    auto require_media = [this]() -> bool {
        if (media_->media_present()) {
            return true;
        }
        fail_command(kSenseNotReady, kAscMediumNotPresent, 0x00);
        return false;
    };

    auto require_data_in = [this, data_in]() -> bool {
        if (data_in || current_cbw_.dCBWDataTransferLength == 0) {
            return true;
        }
        fail_command(kSenseIllegalRequest, kAscInvalidFieldInCdb, 0x00);
        return false;
    };

    switch (opcode) {
        case usbipdcpp::ScsiCmd::TestUnitReady:
            if (require_media()) {
                state_ = State::Status;
            }
            break;

        case usbipdcpp::ScsiCmd::RequestSense: {
            if (!require_data_in()) {
                break;
            }
            const std::uint8_t allocation_length = current_cbw_.CBWCB[4];
            begin_buffer_response(limit_response(make_request_sense(), allocation_length));
            break;
        }

        case usbipdcpp::ScsiCmd::Inquiry: {
            if (!require_data_in()) {
                break;
            }
            const bool evpd = (current_cbw_.CBWCB[1] & 0x01) != 0;
            const std::uint8_t page = current_cbw_.CBWCB[2];
            const std::uint16_t allocation_length = current_cbw_.CBWCB[4];
            if (!evpd) {
                begin_buffer_response(limit_response(make_inquiry(), allocation_length));
            } else if (page == 0x00) {
                std::vector<std::uint8_t> data = {0x05, 0x00, 0x00, 0x02, 0x00, 0x80};
                begin_buffer_response(limit_response(std::move(data), allocation_length));
            } else if (page == 0x80) {
                constexpr std::array<char, 10> serial = {'V','B','D','R','O','M','0','0','0','1'};
                std::vector<std::uint8_t> data(4 + serial.size(), 0);
                data[0] = 0x05;
                data[1] = 0x80;
                usbipdcpp::put_be16(data.data() + 2, static_cast<std::uint16_t>(serial.size()));
                std::copy(serial.begin(), serial.end(), data.begin() + 4);
                begin_buffer_response(limit_response(std::move(data), allocation_length));
            } else {
                fail_command(kSenseIllegalRequest, kAscInvalidFieldInCdb, 0x00);
            }
            break;
        }

        case usbipdcpp::ScsiCmd::ModeSense6: {
            if (!require_data_in()) {
                break;
            }
            const std::uint8_t allocation_length = current_cbw_.CBWCB[4];
            begin_buffer_response(limit_response(make_mode_sense_6(), allocation_length));
            break;
        }

        case usbipdcpp::ScsiCmd::ModeSense10: {
            if (!require_data_in()) {
                break;
            }
            const std::uint16_t allocation_length = usbipdcpp::get_be16(current_cbw_.CBWCB + 7);
            begin_buffer_response(limit_response(make_mode_sense_10(), allocation_length));
            break;
        }

        case usbipdcpp::ScsiCmd::StartStopUnit: {
            const bool loej = (current_cbw_.CBWCB[4] & 0x02) != 0;
            const bool start = (current_cbw_.CBWCB[4] & 0x01) != 0;
            if (loej && !start) {
                if (prevent_removal_) {
                    fail_command(kSenseIllegalRequest, kAscMediumRemovalPrevented, 0x02);
                } else {
                    media_->eject();
                    clear_sense();
                    state_ = State::Status;
                }
            } else {
                state_ = State::Status;
            }
            break;
        }

        case usbipdcpp::ScsiCmd::PreventAllowMediumRemoval:
            prevent_removal_ = (current_cbw_.CBWCB[4] & 0x01) != 0;
            state_ = State::Status;
            break;

        case usbipdcpp::ScsiCmd::ReadCapacity10:
            if (!require_data_in() || !require_media()) {
                break;
            }
            begin_buffer_response(make_read_capacity_10());
            break;

        case usbipdcpp::ScsiCmd::Read10: {
            if (!require_data_in() || !require_media()) {
                break;
            }
            const std::uint64_t lba = usbipdcpp::get_be32(current_cbw_.CBWCB + 2);
            const std::uint64_t count = usbipdcpp::get_be16(current_cbw_.CBWCB + 7);
            const std::uint64_t blocks = media_->block_count();
            if (lba > blocks || count > blocks - lba) {
                fail_command(kSenseIllegalRequest, kAscLbaOutOfRange, 0x00);
                break;
            }
            begin_media_read(lba, count);
            break;
        }

        case kScsiRead12: {
            if (!require_data_in() || !require_media()) {
                break;
            }
            const std::uint64_t lba = usbipdcpp::get_be32(current_cbw_.CBWCB + 2);
            const std::uint64_t count = usbipdcpp::get_be32(current_cbw_.CBWCB + 6);
            const std::uint64_t blocks = media_->block_count();
            if (lba > blocks || count > blocks - lba) {
                fail_command(kSenseIllegalRequest, kAscLbaOutOfRange, 0x00);
                break;
            }
            begin_media_read(lba, count);
            break;
        }

        case kScsiReadToc: {
            if (!require_data_in() || !require_media()) {
                break;
            }
            const std::uint8_t format = current_cbw_.CBWCB[2] & 0x0F;
            const bool msf = (current_cbw_.CBWCB[1] & 0x02) != 0;
            const std::uint16_t allocation_length = usbipdcpp::get_be16(current_cbw_.CBWCB + 7);
            if (format != 0) {
                fail_command(kSenseIllegalRequest, kAscInvalidFieldInCdb, 0x00);
                break;
            }
            begin_buffer_response(limit_response(make_read_toc(msf), allocation_length));
            break;
        }

        case kScsiGetConfiguration: {
            if (!require_data_in()) {
                break;
            }
            const std::uint16_t allocation_length = usbipdcpp::get_be16(current_cbw_.CBWCB + 7);
            begin_buffer_response(limit_response(make_get_configuration(), allocation_length));
            break;
        }

        case kScsiGetEventStatusNotification: {
            if (!require_data_in()) {
                break;
            }
            const bool immediate = (current_cbw_.CBWCB[1] & 0x01) != 0;
            const std::uint16_t allocation_length = usbipdcpp::get_be16(current_cbw_.CBWCB + 7);
            if (!immediate) {
                fail_command(kSenseIllegalRequest, kAscInvalidFieldInCdb, 0x00);
                break;
            }
            begin_buffer_response(limit_response(make_get_event_status(), allocation_length));
            break;
        }

        case kScsiReadDiscInformation: {
            if (!require_data_in() || !require_media()) {
                break;
            }
            const std::uint16_t allocation_length = usbipdcpp::get_be16(current_cbw_.CBWCB + 7);
            begin_buffer_response(limit_response(make_read_disc_information(), allocation_length));
            break;
        }

        case kScsiSetCdSpeed:
        case usbipdcpp::ScsiCmd::Verify10:
            state_ = State::Status;
            break;

        default:
            SPDLOG_WARN("Virtual optical drive: unsupported SCSI opcode 0x{:02X}", opcode);
            fail_command(kSenseIllegalRequest, kAscInvalidCommand, 0x00);
            break;
    }
}

void VirtualOpticalDriveHandler::send_buffer_data(
    std::uint32_t seqnum,
    std::uint32_t transfer_buffer_length,
    usbipdcpp::TransferHandle transfer
) {
    const std::size_t remaining = data_buffer_.size() - data_offset_;
    const std::size_t length = std::min<std::size_t>(transfer_buffer_length, remaining);

    auto* generic = usbipdcpp::GenericTransfer::from_handle(transfer.get());
    generic->data.resize(length);
    if (length > 0) {
        std::memcpy(generic->data.data(), data_buffer_.data() + data_offset_, length);
    }
    generic->actual_length = length;

    data_offset_ += length;
    data_residue_ -= static_cast<std::uint32_t>(
        std::min<std::uint64_t>(data_residue_, length)
    );

    session->submit_ret_submit(
        usbipdcpp::UsbIpResponse::UsbIpRetSubmit::create_ret_submit_ok_with_no_iso(
            seqnum,
            static_cast<std::uint32_t>(length),
            std::move(transfer)
        )
    );

    if (data_offset_ >= data_buffer_.size()) {
        state_ = State::Status;
    }
}

void VirtualOpticalDriveHandler::send_media_data(
    std::uint32_t seqnum,
    std::uint32_t transfer_buffer_length,
    usbipdcpp::TransferHandle transfer
) {
    const std::size_t length = static_cast<std::size_t>(
        std::min<std::uint64_t>(transfer_buffer_length, media_remaining_)
    );

    auto* generic = usbipdcpp::GenericTransfer::from_handle(transfer.get());
    generic->data.resize(length);

    if (!media_->read_bytes(media_offset_, generic->data.data(), length)) {
        generic->data.clear();
        generic->actual_length = 0;
        fail_command(kSenseMediumError, kAscUnrecoveredReadError, 0x00);
        session->submit_ret_submit(
            usbipdcpp::UsbIpResponse::UsbIpRetSubmit::create_ret_submit_ok_with_no_iso(
                seqnum,
                0,
                std::move(transfer)
            )
        );
        return;
    }

    generic->actual_length = length;
    media_offset_ += length;
    media_remaining_ -= length;
    data_residue_ -= static_cast<std::uint32_t>(
        std::min<std::uint64_t>(data_residue_, length)
    );

    session->submit_ret_submit(
        usbipdcpp::UsbIpResponse::UsbIpRetSubmit::create_ret_submit_ok_with_no_iso(
            seqnum,
            static_cast<std::uint32_t>(length),
            std::move(transfer)
        )
    );

    if (media_remaining_ == 0) {
        state_ = State::Status;
    }
}

void VirtualOpticalDriveHandler::send_csw(
    std::uint32_t seqnum,
    usbipdcpp::TransferHandle transfer
) {
    usbipdcpp::CSW csw{};
    csw.dCSWSignature = usbipdcpp::CSW_SIGNATURE;
    csw.dCSWTag = current_cbw_.dCBWTag;
    csw.dCSWDataResidue = data_residue_;
    csw.bCSWStatus = command_failed_ ? 1 : 0;

    auto* generic = usbipdcpp::GenericTransfer::from_handle(transfer.get());
    generic->data.resize(sizeof(csw));
    std::memcpy(generic->data.data(), &csw, sizeof(csw));
    generic->actual_length = sizeof(csw);

    session->submit_ret_submit(
        usbipdcpp::UsbIpResponse::UsbIpRetSubmit::create_ret_submit_ok_with_no_iso(
            seqnum,
            sizeof(csw),
            std::move(transfer)
        )
    );

    state_ = State::Idle;
    data_buffer_.clear();
    data_offset_ = 0;
    media_offset_ = 0;
    media_remaining_ = 0;
    data_residue_ = 0;
    command_failed_ = false;
}

void VirtualOpticalDriveHandler::handle_bulk_transfer(
    std::uint32_t seqnum,
    const usbipdcpp::UsbEndpoint& ep,
    std::uint32_t transfer_flags,
    std::uint32_t transfer_buffer_length,
    usbipdcpp::TransferHandle transfer,
    std::error_code& ec
) {
    (void) transfer_flags;
    (void) ec;

    if (ep.is_in()) {
        switch (state_) {
            case State::DataInBuffer:
                send_buffer_data(seqnum, transfer_buffer_length, std::move(transfer));
                return;
            case State::DataInMedia:
                send_media_data(seqnum, transfer_buffer_length, std::move(transfer));
                return;
            case State::Status:
                send_csw(seqnum, std::move(transfer));
                return;
            case State::Idle:
            default:
                send_stall(seqnum);
                return;
        }
    }

    auto* generic = usbipdcpp::GenericTransfer::from_handle(transfer.get());
    if (state_ != State::Idle || generic == nullptr || generic->data.size() < sizeof(usbipdcpp::CBW)) {
        send_stall(seqnum);
        return;
    }

    usbipdcpp::CBW cbw{};
    std::memcpy(&cbw, generic->data.data(), sizeof(cbw));
    if (
        cbw.dCBWSignature != usbipdcpp::CBW_SIGNATURE ||
        cbw.bCBWCBLength == 0 ||
        cbw.bCBWCBLength > sizeof(cbw.CBWCB) ||
        cbw.bCBWLUN != 0
    ) {
        send_stall(seqnum);
        return;
    }

    process_cbw(cbw);
    session->submit_ret_submit(
        usbipdcpp::UsbIpResponse::UsbIpRetSubmit::create_ret_submit_with_status_and_no_data(
            seqnum,
            static_cast<std::uint32_t>(usbipdcpp::UrbStatusType::StatusOK),
            transfer_buffer_length
        )
    );
}

void VirtualOpticalDriveHandler::handle_non_standard_request_type_control_urb(
    std::uint32_t seqnum,
    const usbipdcpp::UsbEndpoint& ep,
    std::uint32_t transfer_flags,
    std::uint32_t transfer_buffer_length,
    const usbipdcpp::SetupPacket& setup_packet,
    usbipdcpp::TransferHandle transfer,
    std::error_code& ec
) {
    (void) ep;
    (void) transfer_flags;
    (void) transfer_buffer_length;
    (void) ec;

    if (setup_packet.request_type == 0xA1 && setup_packet.request == 0xFE && setup_packet.length == 1) {
        auto* generic = usbipdcpp::GenericTransfer::from_handle(transfer.get());
        generic->data.assign(1, 0);
        generic->actual_length = 1;
        session->submit_ret_submit(
            usbipdcpp::UsbIpResponse::UsbIpRetSubmit::create_ret_submit_ok_with_no_iso(
                seqnum,
                1,
                std::move(transfer)
            )
        );
        return;
    }

    if (setup_packet.request_type == 0x21 && setup_packet.request == 0xFF && setup_packet.length == 0) {
        reset_transport();
        session->submit_ret_submit(
            usbipdcpp::UsbIpResponse::UsbIpRetSubmit::create_ret_submit_ok_without_data(seqnum, 0)
        );
        return;
    }

    send_stall(seqnum);
}

void VirtualOpticalDriveHandler::send_stall(std::uint32_t seqnum) {
    session->submit_ret_submit(
        usbipdcpp::UsbIpResponse::UsbIpRetSubmit::create_ret_submit_epipe_without_data(seqnum, 0)
    );
}

void VirtualOpticalDriveHandler::request_clear_feature(
    std::uint16_t feature_selector,
    std::uint32_t* status
) {
    (void) feature_selector;
    *status = 0;
}

void VirtualOpticalDriveHandler::request_endpoint_clear_feature(
    std::uint16_t feature_selector,
    std::uint8_t ep_address,
    std::uint32_t* status
) {
    (void) feature_selector;
    (void) ep_address;
    *status = 0;
}

std::uint8_t VirtualOpticalDriveHandler::request_get_interface(std::uint32_t* status) {
    *status = 0;
    return 0;
}

void VirtualOpticalDriveHandler::request_set_interface(
    std::uint16_t alternate_setting,
    std::uint32_t* status
) {
    *status = alternate_setting == 0
        ? 0
        : static_cast<std::uint32_t>(usbipdcpp::UrbStatusType::StatusEPIPE);
}

std::uint16_t VirtualOpticalDriveHandler::request_get_status(std::uint32_t* status) {
    *status = 0;
    return 0;
}

std::uint16_t VirtualOpticalDriveHandler::request_endpoint_get_status(
    std::uint8_t ep_address,
    std::uint32_t* status
) {
    (void) ep_address;
    *status = 0;
    return 0;
}

void VirtualOpticalDriveHandler::request_set_feature(
    std::uint16_t feature_selector,
    std::uint32_t* status
) {
    (void) feature_selector;
    *status = static_cast<std::uint32_t>(usbipdcpp::UrbStatusType::StatusEPIPE);
}

void VirtualOpticalDriveHandler::request_endpoint_set_feature(
    std::uint16_t feature_selector,
    std::uint8_t ep_address,
    std::uint32_t* status
) {
    (void) feature_selector;
    (void) ep_address;
    *status = static_cast<std::uint32_t>(usbipdcpp::UrbStatusType::StatusEPIPE);
}

usbipdcpp::data_type VirtualOpticalDriveHandler::get_class_specific_descriptor() {
    return {};
}

std::shared_ptr<usbipdcpp::UsbDevice> create_virtual_optical_device(
    usbipdcpp::StringPool& string_pool,
    const std::shared_ptr<OpticalMediaSource>& media
) {
    std::vector<usbipdcpp::UsbInterface> interfaces = {
        usbipdcpp::UsbInterface{
            .interface_class = 0x08,
            .interface_subclass = 0x06,
            .interface_protocol = 0x50,
            .endpoints = {{
                usbipdcpp::UsbEndpoint{
                    .address = 0x81,
                    .attributes = 0x02,
                    .max_packet_size = 512,
                    .interval = 0,
                },
                usbipdcpp::UsbEndpoint{
                    .address = 0x02,
                    .attributes = 0x02,
                    .max_packet_size = 512,
                    .interval = 0,
                },
            }},
        },
    };

    interfaces[0].with_handler<VirtualOpticalDriveHandler>(string_pool, media);

    auto device = std::make_shared<usbipdcpp::UsbDevice>(usbipdcpp::UsbDevice{
        .path = "/usbipdcpp/virtual_bdrom",
        .busid = kVirtualOpticalBusId,
        .bus_num = 99,
        .dev_num = 1,
        .speed = static_cast<std::uint32_t>(usbipdcpp::UsbSpeed::High),
        .vendor_id = 0x1209,
        .product_id = 0xBD01,
        .device_bcd = 0x0100,
        .device_class = 0x00,
        .device_subclass = 0x00,
        .device_protocol = 0x00,
        .configuration_value = 1,
        .num_configurations = 1,
        .interfaces = interfaces,
        .ep0_in = usbipdcpp::UsbEndpoint::get_ep0_in(usbipdcpp::UsbSpeed::Full),
        .ep0_out = usbipdcpp::UsbEndpoint::get_ep0_out(usbipdcpp::UsbSpeed::Full),
    });

    auto device_handler = device->with_handler<usbipdcpp::SimpleVirtualDeviceHandler>(string_pool);
    device_handler->change_string_manufacturer(L"Android USB/IP");
    device_handler->change_string_product(L"Virtual BD-ROM");
    device_handler->change_string_serial(L"VBDROM0001");
    device_handler->setup_interface_handlers();

    return device;
}

} // namespace android_usbip
