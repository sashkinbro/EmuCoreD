package com.sbro.emucored.core

/**
 * Thin Kotlin wrapper over the core's Azahar Room API. All calls are blocking
 * and must be invoked off the main thread.
 */
object MultiplayerController {
    data class NetworkEvent(val status: Int, val message: String)

    data class PublicRoom(
        val name: String,
        val hasPassword: Boolean,
        val maxPlayers: Int,
        val ip: String,
        val port: Int,
        val description: String,
        val owner: String,
        val gameId: Long,
        val gameName: String,
    )

    data class RoomInfo(val name: String, val memberSlots: Int, val members: List<String>)

    private val bridge by lazy { NativeCoreBridge() }

    fun init(): Boolean = bridge.nativeNetInit() == 0

    fun createRoom(
        ip: String,
        port: Int,
        username: String,
        gameName: String,
        gameId: Long,
        password: String,
        roomName: String,
        maxPlayers: Int,
    ): Int = bridge.nativeNetCreateRoom(ip, port, username, gameName, gameId, password, roomName, maxPlayers)

    fun joinRoom(ip: String, port: Int, username: String, password: String): Int =
        bridge.nativeNetJoinRoom(ip, port, username, password)

    fun leaveRoom() = bridge.nativeNetLeaveRoom()

    fun isJoined(): Boolean = bridge.nativeNetIsJoined()

    fun isModerator(): Boolean = bridge.nativeNetIsModerator()

    fun isHosting(): Boolean = bridge.nativeNetIsHosting()

    fun sendChat(message: String) = bridge.nativeNetSendChat(message)

    fun kick(username: String) = bridge.nativeNetKick(username)

    fun ban(username: String) = bridge.nativeNetBan(username)

    fun unban(username: String) = bridge.nativeNetUnban(username)

    fun shutdown() = bridge.nativeNetShutdown()

    fun roomInfo(): RoomInfo? {
        val lines = bridge.nativeNetRoomInfo().orEmpty().lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return null
        val header = lines.first().split('|')
        val name = header.getOrNull(0).orEmpty()
        val slots = header.getOrNull(1)?.toIntOrNull() ?: 0
        return RoomInfo(name, slots, lines.drop(1))
    }

    fun publicRooms(): List<PublicRoom> {
        val rooms = mutableListOf<PublicRoom>()
        bridge.nativeNetPublicRooms().orEmpty().lineSequence().forEach { line ->
            if (line.isBlank() || line.startsWith("MEMBER|")) return@forEach
            val parts = line.split('|')
            if (parts.size < 9) return@forEach
            rooms += PublicRoom(
                name = parts[0],
                hasPassword = parts[1] == "1",
                maxPlayers = parts[2].toIntOrNull() ?: 0,
                ip = parts[3],
                port = parts[4].toIntOrNull() ?: 0,
                description = parts[5],
                owner = parts[6],
                gameId = parts[7].toLongOrNull() ?: 0L,
                gameName = parts[8],
            )
        }
        return rooms
    }

    fun banList(): List<String> =
        bridge.nativeNetBanList().orEmpty().lineSequence().filter { it.isNotBlank() }.toList()

    fun pollEvent(): NetworkEvent? {
        val raw = bridge.nativeNetPollEvent() ?: return null
        val separator = raw.indexOf('|')
        if (separator <= 0) return null
        val status = raw.substring(0, separator).toIntOrNull() ?: return null
        return NetworkEvent(status, raw.substring(separator + 1))
    }
}
