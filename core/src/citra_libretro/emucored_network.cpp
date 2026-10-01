// Copyright 2026 EmuCoreD
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

// Azahar Room (online multiplayer) support for the libretro core. The Kotlin
// frontend drives hosting/joining through the exported C functions below and
// polls the event queue for state changes, member updates and chat messages.

#include <algorithm>
#include <chrono>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "common/logging/log.h"
#include "common/settings.h"
#include "core/core.h"
#include "core/hle/service/cfg/cfg.h"
#include "network/announce_multiplayer_session.h"
#include "network/network.h"
#include "network/room.h"
#include "network/room_member.h"

namespace {

std::mutex g_mutex;
std::deque<std::pair<int, std::string>> g_events;
std::shared_ptr<Network::AnnounceMultiplayerSession> g_announce_session;
bool g_initialized = false;

void PushEvent(int status, const std::string& message) {
    std::scoped_lock lock{g_mutex};
    g_events.emplace_back(status, message);
    // Keep the queue bounded; the UI polls at least once per frame.
    while (g_events.size() > 256) {
        g_events.pop_front();
    }
}

std::string ConsoleUsername() {
    auto& system = Core::System::GetInstance();
    std::string username = Service::CFG::GetUsername(system);
    return username.empty() ? "EmuCoreD" : username;
}

void BindCallbacks() {
    auto member = Network::GetRoomMember().lock();
    if (!member) {
        return;
    }

    member->BindOnStateChanged([](const Network::RoomMember::State& state) {
        if (state == Network::RoomMember::State::Joined) {
            PushEvent(3, {}); // ROOM_JOINED
        } else if (state == Network::RoomMember::State::Moderator) {
            PushEvent(4, {}); // ROOM_MODERATOR
        }
    });

    member->BindOnError([](const Network::RoomMember::Error& error) {
        int status = 15; // UNKNOWN_ERROR
        switch (error) {
        case Network::RoomMember::Error::LostConnection:
            status = 1;
            break;
        case Network::RoomMember::Error::NameCollision:
            status = 2;
            break;
        case Network::RoomMember::Error::MacCollision:
            status = 3;
            break;
        case Network::RoomMember::Error::ConsoleIdCollision:
            status = 4;
            break;
        case Network::RoomMember::Error::WrongVersion:
            status = 5;
            break;
        case Network::RoomMember::Error::WrongPassword:
            status = 6;
            break;
        case Network::RoomMember::Error::CouldNotConnect:
            status = 7;
            break;
        case Network::RoomMember::Error::RoomIsFull:
            status = 8;
            break;
        case Network::RoomMember::Error::HostBanned:
            status = 9;
            break;
        case Network::RoomMember::Error::PermissionDenied:
            status = 10;
            break;
        case Network::RoomMember::Error::NoSuchUser:
            status = 11;
            break;
        case Network::RoomMember::Error::HostKicked:
            status = 14;
            break;
        default:
            status = 15;
            break;
        }
        PushEvent(status, {});
    });

    member->BindOnStatusMessageReceived([](const Network::StatusMessageEntry& entry) {
        int status = 0;
        switch (entry.type) {
        case Network::IdMemberJoin:
            status = 17;
            break;
        case Network::IdMemberLeave:
            status = 18;
            break;
        case Network::IdMemberKicked:
            status = 19;
            break;
        case Network::IdMemberBanned:
            status = 20;
            break;
        case Network::IdAddressUnbanned:
            status = 21;
            break;
        default:
            return;
        }
        PushEvent(status, entry.nickname);
    });

    member->BindOnChatMessageRecieved([](const Network::ChatEntry& chat) {
        PushEvent(22, std::string(chat.nickname) + ": " + std::string(chat.message));
    });
}

bool EnsureInitialized() {
    if (g_initialized) {
        return true;
    }
    if (!Network::Init()) {
        LOG_ERROR(Network, "Unable to initialize the network module");
        return false;
    }
    g_announce_session =
        std::make_shared<Network::AnnounceMultiplayerSession>(ConsoleUsername());
    BindCallbacks();
    g_initialized = true;
    return true;
}

void AppendLine(std::string& output, const std::string& line) {
    if (!output.empty()) {
        output += '\n';
    }
    output += line;
}

void WriteBuffer(const std::string& value, char* out, int out_size) {
    if (out == nullptr || out_size <= 0) {
        return;
    }
    const int length = std::min(static_cast<int>(value.size()), out_size - 1);
    std::memcpy(out, value.data(), length);
    out[length] = '\0';
}

} // Anonymous namespace

extern "C" {

int emucored_net_init() {
    std::scoped_lock lock{g_mutex};
    return EnsureInitialized() ? 0 : -1;
}

int emucored_net_create_room(const char* ip, int port, const char* username, const char* game_name,
                             unsigned long long game_id, const char* password,
                             const char* room_name, int max_players) {
    std::scoped_lock lock{g_mutex};
    if (!EnsureInitialized()) {
        return 1; // NETWORK_ERROR
    }
    auto member = Network::GetRoomMember().lock();
    auto room = Network::GetRoom().lock();
    if (!member || !room) {
        return 1;
    }
    if (member->GetState() == Network::RoomMember::State::Joining || member->IsConnected()) {
        return 12; // ALREADY_IN_ROOM
    }

    const std::string room_name_string = room_name != nullptr ? room_name : "";
    if (room_name_string.size() < 3 || room_name_string.size() > 20) {
        return 13; // CREATE_ROOM_ERROR
    }

    const std::string ip_string = ip != nullptr ? ip : "0.0.0.0";
    const std::string username_string = username != nullptr ? username : "EmuCoreD";
    const std::string password_string = password != nullptr ? password : "";
    const std::string game_name_string = game_name != nullptr ? game_name : "";

    if (!room->Create(room_name_string, "", ip_string, port, password_string,
                      std::min(max_players, 16), username_string, game_name_string, game_id,
                      std::make_unique<Network::VerifyUser::NullBackend>(), {})) {
        return 13;
    }

    std::this_thread::sleep_for(std::chrono::milliseconds(100));
    member->Join(username_string, Service::CFG::GetConsoleIdHash(Core::System::GetInstance()),
                 ip_string.c_str(), port, 0, Network::NoPreferredMac, password_string);

    for (int i = 0; i < 5; i++) {
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
        if (member->GetState() == Network::RoomMember::State::Joined ||
            member->GetState() == Network::RoomMember::State::Moderator) {
            return 0;
        }
    }

    room->Destroy();
    return 13;
}

int emucored_net_join_room(const char* ip, int port, const char* username, const char* password) {
    std::scoped_lock lock{g_mutex};
    if (!EnsureInitialized()) {
        return 1;
    }
    auto member = Network::GetRoomMember().lock();
    if (!member) {
        return 1;
    }
    if (member->GetState() == Network::RoomMember::State::Joining || member->IsConnected()) {
        return 12;
    }

    const std::string ip_string = ip != nullptr ? ip : "";
    const std::string username_string = username != nullptr ? username : "EmuCoreD";
    const std::string password_string = password != nullptr ? password : "";

    member->Join(username_string, Service::CFG::GetConsoleIdHash(Core::System::GetInstance()),
                 ip_string.c_str(), port, 0, Network::NoPreferredMac, password_string);

    std::this_thread::sleep_for(std::chrono::milliseconds(500));
    if (member->GetState() == Network::RoomMember::State::Joined ||
        member->GetState() == Network::RoomMember::State::Moderator) {
        return 0;
    }
    if (!member->IsConnected()) {
        return 7; // COULD_NOT_CONNECT
    }
    return 6; // WRONG_PASSWORD
}

void emucored_net_leave_room() {
    std::scoped_lock lock{g_mutex};
    if (auto member = Network::GetRoomMember().lock()) {
        member->Leave();
    }
    if (auto room = Network::GetRoom().lock()) {
        if (room->GetState() == Network::Room::State::Open) {
            room->Destroy();
        }
    }
}

int emucored_net_is_joined() {
    std::scoped_lock lock{g_mutex};
    auto member = Network::GetRoomMember().lock();
    if (!member) {
        return 0;
    }
    return (member->GetState() == Network::RoomMember::State::Joined ||
            member->GetState() == Network::RoomMember::State::Moderator)
               ? 1
               : 0;
}

int emucored_net_is_moderator() {
    std::scoped_lock lock{g_mutex};
    auto member = Network::GetRoomMember().lock();
    if (!member) {
        return 0;
    }
    return member->GetState() == Network::RoomMember::State::Moderator ? 1 : 0;
}

int emucored_net_is_hosting() {
    std::scoped_lock lock{g_mutex};
    auto room = Network::GetRoom().lock();
    if (!room) {
        return 0;
    }
    return room->GetState() == Network::Room::State::Open ? 1 : 0;
}

void emucored_net_send_chat(const char* message) {
    std::scoped_lock lock{g_mutex};
    auto member = Network::GetRoomMember().lock();
    if (!member || message == nullptr) {
        return;
    }
    if (member->GetState() != Network::RoomMember::State::Joined &&
        member->GetState() != Network::RoomMember::State::Moderator) {
        return;
    }
    member->SendChatMessage(message);
}

void emucored_net_kick(const char* username) {
    std::scoped_lock lock{g_mutex};
    auto member = Network::GetRoomMember().lock();
    if (member && username != nullptr) {
        member->SendModerationRequest(Network::RoomMessageTypes::IdModKick, username);
    }
}

void emucored_net_ban(const char* username) {
    std::scoped_lock lock{g_mutex};
    auto member = Network::GetRoomMember().lock();
    if (member && username != nullptr) {
        member->SendModerationRequest(Network::RoomMessageTypes::IdModBan, username);
    }
}

void emucored_net_unban(const char* username) {
    std::scoped_lock lock{g_mutex};
    auto member = Network::GetRoomMember().lock();
    if (member && username != nullptr) {
        member->SendModerationRequest(Network::RoomMessageTypes::IdModUnban, username);
    }
}

int emucored_net_room_info(char* out, int out_size) {
    std::scoped_lock lock{g_mutex};
    std::string result;
    auto member = Network::GetRoomMember().lock();
    if (member) {
        auto members = member->GetMemberInformation();
        if (!members.empty()) {
            const auto room_info = member->GetRoomInformation();
            AppendLine(result, room_info.name + "|" + std::to_string(room_info.member_slots));
            for (const auto& info : members) {
                AppendLine(result, info.nickname);
            }
        }
    }
    WriteBuffer(result, out, out_size);
    return static_cast<int>(result.size());
}

int emucored_net_public_rooms(char* out, int out_size) {
    std::scoped_lock lock{g_mutex};
    std::string result;
    if (!EnsureInitialized()) {
        WriteBuffer(result, out, out_size);
        return 0;
    }
    if (g_announce_session) {
        const auto rooms = g_announce_session->GetRoomList();
        for (const auto& room : rooms) {
            AppendLine(result, room.name + "|" + (room.has_password ? "1" : "0") + "|" +
                                   std::to_string(room.max_player) + "|" + room.ip + "|" +
                                   std::to_string(room.port) + "|" + room.description + "|" +
                                   room.owner + "|" + std::to_string(room.preferred_game_id) + "|" +
                                   room.preferred_game);
            for (const auto& member : room.members) {
                AppendLine(result, "MEMBER|" + room.name + "|" + member.username + "|" +
                                       member.nickname + "|" + std::to_string(member.game_id) + "|" +
                                       member.game_name);
            }
        }
    }
    WriteBuffer(result, out, out_size);
    return static_cast<int>(result.size());
}

int emucored_net_ban_list(char* out, int out_size) {
    std::scoped_lock lock{g_mutex};
    std::string result;
    auto room = Network::GetRoom().lock();
    if (room) {
        auto [username_bans, ip_bans] = room->GetBanList();
        for (const auto& username : username_bans) {
            AppendLine(result, username);
        }
        for (const auto& ip : ip_bans) {
            AppendLine(result, ip);
        }
    }
    WriteBuffer(result, out, out_size);
    return static_cast<int>(result.size());
}

int emucored_net_poll_event(int* out_status, char* out_message, int out_size) {
    std::scoped_lock lock{g_mutex};
    if (g_events.empty()) {
        return 0;
    }
    const auto [status, message] = g_events.front();
    g_events.pop_front();
    if (out_status != nullptr) {
        *out_status = status;
    }
    WriteBuffer(message, out_message, out_size);
    return 1;
}

void emucored_net_shutdown() {
    std::scoped_lock lock{g_mutex};
    if (!g_initialized) {
        return;
    }
    if (auto member = Network::GetRoomMember().lock()) {
        member->Leave();
    }
    if (auto room = Network::GetRoom().lock()) {
        if (room->GetState() == Network::Room::State::Open) {
            room->Destroy();
        }
    }
    g_announce_session.reset();
    Network::Shutdown();
    g_initialized = false;
    g_events.clear();
}

} // extern "C"
