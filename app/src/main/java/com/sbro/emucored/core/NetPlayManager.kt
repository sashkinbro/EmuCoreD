// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.core

/**
 * The core's own Azahar Room API. All calls are blocking and must be invoked
 * off the main thread. Room state is announced by the core itself, so this is
 * a thin mapping onto the core's JNI.
 */
object NetPlayManager {
    fun init(): Boolean = EmuCoreNative.initMultiplayer().let { true }
    fun createRoom(
        ip: String,
        port: Int,
        username: String,
        gameName: String,
        gameId: Long,
        password: String,
        roomName: String,
        maxPlayers: Int,
    ): Int = netPlayCreateRoom(ip, port, username, gameName, gameId, password, roomName, maxPlayers)

    fun joinRoom(ip: String, port: Int, username: String, password: String): Int =
        netPlayJoinRoom(ip, port, username, password)

    fun leaveRoom() = netPlayLeaveRoom()
    fun isJoined(): Boolean = netPlayIsJoined()
    fun isModerator(): Boolean = netPlayIsModerator()
    fun isHosting(): Boolean = netPlayIsHostedRoom()
    fun sendChat(message: String) = netPlaySendMessage(message)
    fun kick(username: String) = netPlayKickUser(username)
    fun ban(username: String) = netPlayBanUser(username)
    fun unban(username: String) = netPlayUnbanUser(username)
    fun banList(): List<String> =
        netPlayGetBanList().orEmpty().filter { it.isNotBlank() }.toList()

    fun roomInfo(): RoomInfo? {
        val lines = netPlayRoomInfo().orEmpty().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return null
        val header = lines.first().split('|')
        val name = header.getOrNull(0).orEmpty()
        val slots = header.getOrNull(1)?.toIntOrNull() ?: 0
        return RoomInfo(name, slots, lines.drop(1))
    }

    fun publicRooms(): List<PublicRoom> {
        val rooms = mutableListOf<PublicRoom>()
        netPlayGetPublicRooms().orEmpty().forEach { line ->
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

    private external fun netPlayCreateRoom(
        ip: String,
        port: Int,
        username: String,
        preferedGameName: String,
        preferedGameId: Long,
        password: String,
        roomName: String,
        maxPlayers: Int,
    ): Int

    private external fun netPlayJoinRoom(
        ip: String,
        port: Int,
        username: String,
        password: String,
    ): Int

    private external fun netPlayLeaveRoom()
    private external fun netPlayIsJoined(): Boolean
    private external fun netPlayIsHostedRoom(): Boolean
    private external fun netPlayIsModerator(): Boolean
    private external fun netPlaySendMessage(msg: String)
    private external fun netPlayKickUser(username: String)
    private external fun netPlayBanUser(username: String)
    private external fun netPlayUnbanUser(username: String)
    private external fun netPlayGetBanList(): Array<String>?
    private external fun netPlayGetPublicRooms(): Array<String>?
    private external fun netPlayRoomInfo(): Array<String>?
}
