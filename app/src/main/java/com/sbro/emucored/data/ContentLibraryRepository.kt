package com.sbro.emucored.data

import android.content.Context
import com.sbro.emucored.core.EmulatorBridge
import kotlinx.coroutines.flow.first
import java.util.Locale
import android.net.Uri
import java.io.File
import com.sbro.emucored.core.DocumentPathResolver
import com.sbro.emucored.core.dolphin.DolphinSessionEngine
import com.sbro.emucored.data.cheats.CheatGameIdentity
import java.security.MessageDigest
import java.util.zip.CRC32

data class SelectedGameIdentity(
    val serial: String?,
    val crc: String?,
    val typed:CheatGameIdentity?=null
)

class ContentLibraryRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = AppPreferences(appContext)
    private val cache = GameLibraryCacheRepository(appContext)

    suspend fun loadGames(): List<GameItem> {
        val paths = preferences.gamePaths.first()
        if (paths.isEmpty()) return emptyList()
        val preferEnglish = preferences.preferEnglishGameTitles.first()
        return cache.loadSnapshot(GameLibraryCacheRepository.libraryKey(paths), preferEnglish)
            .games
            .sortedBy { it.title.lowercase(Locale.getDefault()) }
    }

    fun resolveIdentity(game: GameItem): SelectedGameIdentity {
        val metadata = runCatching { EmulatorBridge.getGameMetadata(game.path) }.getOrNull()
        val serial = metadata?.serial?.normalizeNintendoGameSerial(game.platform) ?: game.serial?.normalizeNintendoGameSerial(game.platform)
        val crc = metadata?.serialWithCrc
            ?.let(CRC_PATTERN::find)
            ?.value
            ?.uppercase(Locale.US)
        val typed=runCatching {
            if(game.platform==GamePlatform.GAMECUBE || game.platform==GamePlatform.WII) {
                fun probe(path:String)=DolphinSessionEngine.probeGameIdentity(path)?.let {
                    CheatGameIdentity(game.platform,gameId=it.gameId,titleId=it.titleId,revision=it.revision)
                }
                val local=DocumentPathResolver.resolveFilePath(appContext,game.path)?.removePrefix("!")
                if(local!=null && File(local).canRead())probe(local) else {
                    val uri=if(game.path.startsWith("content://"))Uri.parse(game.path)
                        else DocumentPathResolver.findAccessibleTreeUriForRawPath(appContext,game.path)
                    uri?.let{appContext.contentResolver.openFileDescriptor(it,"r")?.use{pfd->probe("/proc/self/fd/${pfd.fd}")}}
                }
            } else if(game.platform==GamePlatform.N3DS) CheatGameIdentity(game.platform,titleId=serial)
            else {
                val local=game.path.removePrefix("!").removePrefix("file://")
                val input=if(game.path.startsWith("content://"))appContext.contentResolver.openInputStream(Uri.parse(game.path))
                    else if(File(local).canRead())File(local).inputStream() else
                        DocumentPathResolver.findAccessibleTreeUriForRawPath(appContext,local)?.let{appContext.contentResolver.openInputStream(it)}
                input?.use { stream ->
                    val sha=MessageDigest.getInstance("SHA-1");val md5=MessageDigest.getInstance("MD5");val checksum=CRC32()
                    val buffer=ByteArray(128*1024);var size=0L
                    while(true){val n=stream.read(buffer);if(n<0)break;size+=n;require(size<=512L*1024*1024)
                        sha.update(buffer,0,n);md5.update(buffer,0,n);checksum.update(buffer,0,n)}
                    fun ByteArray.hex()=joinToString(""){"%02X".format(it.toInt() and 255)}
                    CheatGameIdentity(game.platform,sha.digest().hex(),md5.digest().hex(),"%08X".format(checksum.value),size)
                }
            }
        }.getOrNull()
        return SelectedGameIdentity(serial = typed?.gameId ?: typed?.titleId ?: serial, crc = crc,typed=typed)
    }

    private companion object {
        val CRC_PATTERN = Regex("(?i)(?<![0-9A-F])[0-9A-F]{8}(?![0-9A-F])")
    }
}

internal fun String.normalizeNintendoGameSerial(platform:GamePlatform): String? {
    val compact = trim().uppercase(Locale.US).replace(Regex("[-_ ]"), "")
    return when(platform) {
        GamePlatform.N3DS -> compact.takeIf{it.matches(Regex("[0-9A-F]{16}|(?:CTR|KTR)[PN]?[A-Z0-9]{4}"))}
        GamePlatform.NDS -> compact.takeIf{it.matches(Regex("(?:(?:NTR|TWL)P?)?[A-Z0-9]{4}"))}?.takeLast(4)
        GamePlatform.GAMECUBE,GamePlatform.WII -> compact.takeIf{it.matches(Regex("[A-Z0-9]{6}|[0-9A-F]{16}"))}
        else -> compact.takeIf{it.matches(Regex("[A-Z0-9]{4}"))}
    }
}
