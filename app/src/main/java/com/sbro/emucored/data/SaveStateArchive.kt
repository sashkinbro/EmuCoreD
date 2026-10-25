package com.sbro.emucored.data

import com.sbro.emucored.core.platform.session.ConsoleSessionStorage
import com.sbro.emucored.core.platform.session.ConsoleStateSlotTransaction
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** A versioned, checksummed archive. The manifest identifies the core and original game, never a destination path. */
internal class SaveStateArchive(private val filesDirectory: File, private val legacyDirectory: File) {
    private data class Record(val spec: JSONObject) {
        val platform get() = GamePlatform.valueOf(spec.getString("platform"))
        val path get() = spec.optString("gamePath").takeIf(String::isNotBlank)
        val slot get() = spec.getInt("slot")
        val source get() = spec.getString("state")
        val checksum get() = spec.getString("sha256")
        val metadata get() = spec.optJSONObject("metadata") ?: JSONObject()
        val preview get() = spec.optString("preview").takeIf(String::isNotBlank)
    }

    fun write(entries: List<SaveStateEntryInfo>, output: OutputStream) {
        require(entries.size in 1..500)
        val manifest = JSONArray()
        ZipOutputStream(output).use { zip ->
            entries.distinctBy { it.absolutePath }.forEachIndexed { index, entry ->
                val file = File(entry.absolutePath).canonicalFile
                val platform = entry.platform ?: when (file.extension.lowercase()) {
                    "cst" -> GamePlatform.N3DS
                    "dss" -> GamePlatform.NDS
                    else -> error("Missing state console")
                }
                require(owned(file) && SaveStateFileIntegrity.validate(file, platform))
                if (platform == GamePlatform.N3DS) require(
                    parseExternalSaveStateName(file.name)?.serial == SaveStateFileIntegrity.azaharTitleId(file))
                val sidecar = File("${file.path}.json")
                val metadata = if (sidecar.isFile && sidecar.length() in 1..65_536)
                    JSONObject(sidecar.readText()) else JSONObject()
                val spec = JSONObject().put("platform", platform.name).put("gamePath", entry.gamePath)
                    .put("slot", entry.slot).put("title", entry.gameTitle).put("serial", entry.serial).put("savedAt", entry.lastModified)
                    .put("fileName", file.name).put("metadata", metadata)
                if (platform == GamePlatform.NDS) spec.put("dsDirectory", file.parentFile!!.name)
                if (platform !in setOf(GamePlatform.N3DS, GamePlatform.NDS)) {
                    val record = requireNotNull(ConsoleSaveStateIndex(filesDirectory).read(file))
                    require(record.platform == platform && record.gamePath == entry.gamePath && record.slot == entry.slot)
                }
                val name = "slots/$index-${file.name}"
                val digest = SaveStateFileIntegrity.sha256(file)
                metadata.optString("sha256").takeIf(String::isNotBlank)?.let { require(it == digest) }
                spec.put("state", name).put("size", file.length()).put("sha256", digest)
                zip.putNextEntry(ZipEntry(name))
                verifiedCopy(file, zip, SaveStateFileIntegrity.MAX_STATE_BYTES, spec.getLong("size"), digest)
                zip.closeEntry()
                val preview = File("${file.path}.png")
                if (validPng(preview)) {
                    val previewName = "previews/$index.png"
                    val previewDigest = SaveStateFileIntegrity.sha256(preview)
                    val previewSize = preview.length()
                    spec.put("preview", previewName).put("previewSha256", previewDigest)
                    zip.putNextEntry(ZipEntry(previewName))
                    verifiedCopy(preview, zip, MAX_PREVIEW, previewSize, previewDigest)
                    zip.closeEntry()
                }
                manifest.put(spec)
            }
            zip.putNextEntry(ZipEntry("manifest.json"))
            val bytes = JSONObject().put("format", FORMAT).put("version", 2).put("entries", manifest).toString().toByteArray()
            require(bytes.size <= MAX_MANIFEST)
            zip.write(bytes)
            zip.closeEntry()
        }
    }

    fun recognizes(zip: ZipFile): Boolean = runCatching {
        val entry = zip.getEntry("manifest.json") ?: return@runCatching false
        require(entry.size in 1..MAX_MANIFEST)
        JSONObject(readManifest(zip, entry)).optString("format") == FORMAT
    }.getOrDefault(false)

    fun preview(staged: File, displayName: String, gamePath: String?, expectedPlatform: GamePlatform?, gameSerial: String?): SaveStateImportPreview {
        val candidates = mutableListOf<SaveStateImportCandidate>()
        var skipped = 0
        val allocated = mutableSetOf<String>()
        ZipFile(staged).use { zip ->
            records(zip).forEach { record ->
                val foreign = !gamePath.isNullOrBlank() && (
                    (expectedPlatform != null && expectedPlatform != record.platform) ||
                        (record.platform != GamePlatform.N3DS && record.path != gamePath) ||
                        (record.platform == GamePlatform.N3DS &&
                            (if (!gameSerial.isNullOrBlank()) !record.spec.optString("serial").equals(gameSerial, true)
                            else record.path != gamePath)))
                if (foreign) { skipped++; return@forEach }
                val slot = (listOf(record.slot) + (1..10)).distinct().firstOrNull { candidate ->
                    val target = target(record, candidate)
                    !target.exists() && target.canonicalPath !in allocated
                }
                if (slot == null) { skipped++; return@forEach }
                val target = target(record, slot)
                allocated += target.canonicalPath
                candidates += SaveStateImportCandidate(record.source, record.spec.getString("fileName"), target.name, slot,
                    record.spec.optString("serial"), SaveStateImportFormat.CURRENT, record.spec.getLong("size"),
                    record.platform, record.path, record.metadata.toString(), record.checksum, record.preview,
                    record.spec.optString("previewSha256").takeIf(String::isNotBlank),
                    if (record.platform == GamePlatform.N3DS) null else target.relativeTo(filesDirectory).invariantSeparatorsPath)
            }
        }
        return SaveStateImportPreview(staged.path, displayName, candidates, skipped, 0)
    }

    fun importStates(staged: File, candidates: List<SaveStateImportCandidate>, onFailure: (Throwable) -> Unit = {}): SaveStateImportResult {
        var imported = 0
        var failed = 0
        ZipFile(staged).use { zip ->
            val records = records(zip).associateBy { it.source }
            candidates.forEach { candidate ->
                val temporaryFiles = mutableListOf<File>()
                val success = runCatching {
                    val record = requireNotNull(records[candidate.sourceEntryName])
                    require(record.platform == candidate.platform && record.checksum == candidate.checksum && record.path == candidate.gamePath)
                    val target = target(record, candidate.targetSlot)
                    require(target.name == candidate.targetFileName && !target.exists())
                    target.parentFile!!.mkdirs()
                    fun temporary(suffix: String) = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.$suffix").also(temporaryFiles::add)
                    val state = temporary("importing")
                    zip.getInputStream(zip.getEntry(record.source)).use { input -> state.outputStream().use { boundedCopy(input, it, SaveStateFileIntegrity.MAX_STATE_BYTES) } }
                    require(SaveStateFileIntegrity.sha256(state) == record.checksum && SaveStateFileIntegrity.validate(state, record.platform))
                    if (record.platform == GamePlatform.N3DS) require(
                        parseExternalSaveStateName(record.spec.getString("fileName"))?.serial == SaveStateFileIntegrity.azaharTitleId(state))
                    record.spec.optLong("savedAt").takeIf { it > 0 }?.let(state::setLastModified)
                    val preview = record.preview?.let { name ->
                        temporary("png").also { file ->
                            zip.getInputStream(zip.getEntry(name)).use { input -> file.outputStream().use { boundedCopy(input, it, MAX_PREVIEW) } }
                            require(validPng(file) && SaveStateFileIntegrity.sha256(file) == record.spec.getString("previewSha256"))
                        }
                    }
                    val metadata = JSONObject(record.metadata.toString()).put("slot", candidate.targetSlot)
                        .put("sha256", record.checksum).put("platform", record.platform.name)
                        .put("gamePath", record.path).put("title", record.spec.optString("title"))
                    // The transaction reserves an empty slot under the same lock as Quick Save/Load.
                    require(runBlocking {
                        ConsoleStateSlotTransaction.saveNew(target, preview,
                            writeState = { destination -> Files.move(state.toPath(), destination.toPath()); true },
                            onFailure = onFailure,
                            metadata = { metadata })
                    })
                }.onFailure(onFailure).isSuccess
                temporaryFiles.forEach(File::delete)
                if (success) imported++ else failed++
            }
        }
        return SaveStateImportResult(imported, failed)
    }

    private fun records(zip: ZipFile): List<Record> {
        val names = zip.entries().asSequence().take(1002).map { it.name }.toList()
        require(names.size <= 1001 && names.toSet().size == names.size && names.all(::safeName))
        val manifest = requireNotNull(zip.getEntry("manifest.json"))
        require(manifest.size in 1..MAX_MANIFEST)
        val json = JSONObject(readManifest(zip, manifest))
        require(json.getString("format") == FORMAT && json.getInt("version") == 2)
        val entries = json.getJSONArray("entries")
        require(entries.length() in 1..500)
        val referenced = mutableSetOf("manifest.json")
        var total = 0L
        val records = (0 until entries.length()).map { index ->
            val record = Record(entries.getJSONObject(index))
            require(record.slot in 0..10 && record.source.startsWith("slots/") && referenced.add(record.source))
            require(record.checksum.matches(Regex("[0-9a-f]{64}")))
            require(record.metadata.toString().toByteArray().size <= 65_536 && record.path.orEmpty().length <= 8192)
            require(record.spec.optString("title").length <= 4096 && record.spec.optString("serial").length <= 128)
            require(record.spec.getString("fileName") == File(record.spec.getString("fileName")).name)
            record.metadata.optString("platform").takeIf(String::isNotBlank)?.let { require(it == record.platform.name) }
            record.metadata.optString("core").takeIf(String::isNotBlank)?.let { require(it == record.platform.console.core.name) }
            record.metadata.optString("gamePath").takeIf(String::isNotBlank)?.let { require(it == record.path) }
            record.metadata.optString("sha256").takeIf(String::isNotBlank)?.let { require(it == record.checksum) }
            if (record.platform != GamePlatform.N3DS) require(!record.path.isNullOrBlank())
            val state = requireNotNull(zip.getEntry(record.source))
            require(state.size in 17..SaveStateFileIntegrity.MAX_STATE_BYTES && state.size == record.spec.getLong("size"))
            total += state.size
            record.preview?.let { name ->
                require(name.startsWith("previews/") && referenced.add(name))
                val preview = requireNotNull(zip.getEntry(name))
                require(preview.size in 1..MAX_PREVIEW && record.spec.getString("previewSha256").matches(Regex("[0-9a-f]{64}")))
                total += preview.size
            }
            require(total <= MAX_ARCHIVE)
            target(record, record.slot) // Validate every identity/destination before offering any import.
            record
        }
        require(referenced == names.toSet())
        return records
    }

    private fun target(record: Record, slot: Int): File {
        require(slot in 0..10)
        val target = when (record.platform) {
            GamePlatform.N3DS -> {
                val parsed = requireNotNull(parseExternalSaveStateName(record.spec.getString("fileName")))
                File(legacyDirectory, "${parsed.serial}.${slot.toString().padStart(2, '0')}.cst")
            }
            GamePlatform.NDS -> {
                val directory = record.spec.getString("dsDirectory")
                require(directory.matches(Regex("[A-Za-z0-9._-]{1,120}")) && directory !in setOf(".", ".."))
                File(filesDirectory, "ds/states/$directory/slot$slot.dss")
            }
            else -> ConsoleSessionStorage.stateFile(filesDirectory, record.platform.console, requireNotNull(record.path), slot)
        }.canonicalFile
        require(owned(target))
        return target
    }

    private fun owned(file: File): Boolean = listOf(legacyDirectory, File(filesDirectory, "ds/states"),
        ConsoleSessionStorage.stateRoot(filesDirectory)).any { file.canonicalPath.startsWith(it.canonicalPath + File.separator) }

    companion object {
        const val FORMAT = "EmuCoreD-SaveStates"
        private const val MAX_MANIFEST = 16_777_216L
        private const val MAX_PREVIEW = 8_388_608L
        private const val MAX_ARCHIVE = 4_294_967_296L
        private fun safeName(name: String): Boolean = name.isNotBlank() && !name.startsWith('/') &&
            '\\' !in name && ':' !in name && name.split('/').none { it.isBlank() || it == "." || it == ".." }

        private fun readManifest(zip: ZipFile, entry: ZipEntry): String {
            require(entry.size in 1..MAX_MANIFEST)
            val output = ByteArrayOutputStream()
            zip.getInputStream(entry).use { require(boundedCopy(it, output, MAX_MANIFEST) == entry.size) }
            return output.toByteArray().decodeToString()
        }

        private fun verifiedCopy(file: File, output: OutputStream, maximum: Long, expectedSize: Long, expectedSha: String) {
            val digest = MessageDigest.getInstance("SHA-256")
            val size = DigestInputStream(file.inputStream(), digest).use { boundedCopy(it, output, maximum) }
            require(size == expectedSize && digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == expectedSha)
        }

        private fun boundedCopy(input: InputStream, output: OutputStream, maximum: Long): Long {
            val buffer = ByteArray(128 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= maximum)
                output.write(buffer, 0, count)
            }
            return total
        }

        internal fun validPng(file: File): Boolean = runCatching {
            require(file.isFile && file.length() in 33..MAX_PREVIEW)
            val bytes = file.readBytes()
            require(bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)))
            val header = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)
            require(header.getInt(12) == 0x49484452)
            val width = header.getInt(16); val height = header.getInt(20)
            require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 20_000_000)
            var offset = 8
            var ended = false
            while (offset + 12 <= bytes.size) {
                val length = header.getInt(offset)
                require(length >= 0 && length.toLong() + offset + 12 <= bytes.size)
                val crc = java.util.zip.CRC32().apply { update(bytes, offset + 4, length + 4) }.value
                require(crc == (header.getInt(offset + 8 + length).toLong() and 0xFFFFFFFFL))
                val iend = header.getInt(offset + 4) == 0x49454E44
                offset += length + 12
                if (iend) { require(length == 0 && offset == bytes.size); ended = true; break }
            }
            ended
        }.getOrDefault(false)
    }
}
