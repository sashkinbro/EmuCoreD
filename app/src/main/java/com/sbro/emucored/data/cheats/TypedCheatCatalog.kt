package com.sbro.emucored.data.cheats

import com.sbro.emucored.data.GamePlatform
import com.sbro.emucored.data.CheatBlock
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.Locale

data class CheatGameIdentity(val platform: GamePlatform, val sha1:String?=null,val md5:String?=null,
    val crc32:String?=null,val sizeBytes:Long?=null,val gameId:String?=null,val titleId:String?=null,val revision:Int?=null)
data class TypedCheatIdentity(val sha1:String?=null,val md5:String?=null,val crc32:String?=null,
    val sizeBytes:Long?=null,val gameId:String?=null,val titleId:String?=null,val revision:Int?=null,
    val revisionScope:String?=null)
data class TypedCheatPack(val id:String,val platform:GamePlatform,val title:String,val path:String,
    val sha256:String,val sizeBytes:Long,val blockCount:Int,val formats:Set<String>,val identities:List<TypedCheatIdentity>,
    val sourceUrl:String="",val license:String="") {
    fun matches(game:CheatGameIdentity):Boolean {
        if(game.platform!=platform)return false
        return identities.any { identity ->
            when {
                !identity.sha1.isNullOrBlank() -> game.sha1.equals(identity.sha1,true) &&
                    (identity.sizeBytes==null || game.sizeBytes==identity.sizeBytes)
                !identity.md5.isNullOrBlank() -> game.md5.equals(identity.md5,true) &&
                    (identity.sizeBytes==null || game.sizeBytes==identity.sizeBytes)
                !identity.gameId.isNullOrBlank() -> game.gameId.equals(identity.gameId,true) &&
                    (identity.revision?.let { it==game.revision } ?: (identity.revisionScope=="unspecified-upstream"))
                !identity.titleId.isNullOrBlank() -> game.titleId.equals(identity.titleId,true)
                else -> false // CRC and loose serial/title alone cannot disambiguate a revision.
            }
        }
    }
}

object TypedCheatCatalog {
    private val json=Json { ignoreUnknownKeys=true }
    fun platformKey(platform:GamePlatform)=if(platform==GamePlatform.N3DS)"n3ds" else platform.name.lowercase(Locale.ROOT)
    fun safePackPath(path:String,platform:GamePlatform):Boolean = path.matches(
        Regex("platforms/${platformKey(platform)}/packs/[a-z0-9-]+\\.json"))
    fun sha256(bytes:ByteArray):String=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
    fun gameKey(platform:GamePlatform,path:String):String="${platform.name}_${sha256(path.toByteArray()).take(32)}"
    fun parseIndex(raw:String,platform:GamePlatform):List<TypedCheatPack> {
        val root=json.parseToJsonElement(raw).jsonObject
        require(root["schemaVersion"]?.jsonPrimitive?.int==2)
        require(root.string("platform")==platformKey(platform))
        return root.getValue("entries").jsonArray.map { element ->
            val item=element.jsonObject
            val id=item.string("id");val path=item.string("path")
            require(id.matches(Regex("[a-z0-9-]+")) && safePackPath(path,platform))
            val hash=item.string("sha256");require(hash.matches(Regex("[0-9a-f]{64}")))
            val size=item["sizeBytes"]?.jsonPrimitive?.long?:0;require(size in 1..4L*1024*1024)
            val identities=parseIdentities(item)
            val count=item["blockCount"]?.jsonPrimitive?.int?:0;require(count>0)
            TypedCheatPack(id,platform,item.string("title"),path,hash,size,count,
                item["formats"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }.toSet(),identities,
                sourceUrl="https://github.com/sashkinbro/EmuCoreD-Cheat/blob/main/$path")
        }.also { packs -> require(packs.map{it.id}.toSet().size==packs.size) }
    }
    fun parsePack(raw:String,expected:TypedCheatPack):List<CheatBlock> {
        val root=json.parseToJsonElement(raw).jsonObject
        require(root["schemaVersion"]?.jsonPrimitive?.int==2 && root.string("id")==expected.id)
        require(root.string("platform")==platformKey(expected.platform))
        require(parseIdentities(root)==expected.identities){"Cheat identity metadata mismatch"}
        return parseBlocks(root).map{it.copy(id="${expected.id}_${it.id}",sourceUrl=root.nullableString("sourceUrl"),
            sourcePackId=expected.id,license=root.nullableString("license"))}.also {require(it.size==expected.blockCount)}
    }
    fun parseStored(raw:String):List<CheatBlock> {
        val root=json.parseToJsonElement(raw).jsonObject;require(root["schemaVersion"]?.jsonPrimitive?.int==2)
        return parseBlocks(root)
    }
    private fun parseIdentities(root:JsonObject):List<TypedCheatIdentity> = root["identities"]?.jsonArray.orEmpty().map { entry ->
        val value=entry.jsonObject
        TypedCheatIdentity(value.nullableString("sha1"),value.nullableString("md5"),value.nullableString("crc32"),
            value["sizeBytes"]?.jsonPrimitive?.longOrNull,value.nullableString("gameId"),value.nullableString("titleId"),
            value["revision"]?.jsonPrimitive?.intOrNull,value.nullableString("revisionScope"))
    }
    private fun parseBlocks(root:JsonObject):List<CheatBlock> = root.getValue("blocks").jsonArray.map { value ->
        val block=value.jsonObject
        CheatBlock(block.string("id"),block.string("title"),block["lines"]?.jsonArray.orEmpty().map{it.jsonPrimitive.content},
            false,block.nullableString("author"),block.string("format"),block.nullableString("sourceUrl"),
            block.nullableString("sourcePackId"),block.nullableString("license"),
            block["manualAssociation"]?.jsonPrimitive?.booleanOrNull?:false)
    }.also {blocks ->require(blocks.map{it.id}.toSet().size==blocks.size)}
    fun serialize(blocks:List<CheatBlock>):String=buildJsonObject {
        put("schemaVersion",2);putJsonArray("blocks") { blocks.forEach { block ->add(buildJsonObject {
            put("id",block.id);put("title",block.title);put("format",block.format);block.author?.let{put("author",it)}
            block.sourceUrl?.let{put("sourceUrl",it)};block.sourcePackId?.let{put("sourcePackId",it)}
            block.license?.let{put("license",it)};put("manualAssociation",block.manualAssociation)
            putJsonArray("lines"){block.lines.forEach{add(it)}};put("enabled",false)
        }) } }
    }.toString()
}
private fun JsonObject.string(key:String)=get(key)?.jsonPrimitive?.contentOrNull.orEmpty()
private fun JsonObject.nullableString(key:String)=get(key)?.jsonPrimitive?.contentOrNull?.takeIf{it.isNotBlank()}
