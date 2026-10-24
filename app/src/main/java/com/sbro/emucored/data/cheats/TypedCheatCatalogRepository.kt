package com.sbro.emucored.data.cheats

import android.content.Context
import android.util.AtomicFile
import com.sbro.emucored.data.GamePlatform
import com.sbro.emucored.data.RemoteCatalogResult
import kotlinx.serialization.json.*
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Lazy per-console catalog; verifies manifest/index/payload bytes before use. */
class TypedCheatCatalogRepository(context:Context) {
    companion object { private val cacheLock=Any() }
    private val directory=File(context.filesDir,"remote-content/typed-cheats").apply{mkdirs()}
    private val bases=listOf("https://raw.githubusercontent.com/sashkinbro/EmuCoreD-Cheat/main/",
        "https://cdn.jsdelivr.net/gh/sashkinbro/EmuCoreD-Cheat@main/")
    private val json=Json{ignoreUnknownKeys=true}
    private val ttl=24*60*60*1000L
    fun load(platform:GamePlatform,forceRefresh:Boolean=false):RemoteCatalogResult<TypedCheatPack> {
        require(platform!=GamePlatform.N3DS) // Legacy 3DS link-only format keeps its existing loader.
        val key=TypedCheatCatalog.platformKey(platform)
        val cachedIndex=File(directory,"$key.json")
        val manifestFile=File(directory,"manifest-$key.json")
        var error:Throwable?=null
        repeat(2) { attempt ->
            try {
                val snapshot=synchronized(cacheLock){Triple(runCatching{read(manifestFile)}.getOrNull(),
                    runCatching{read(cachedIndex)}.getOrNull(),manifestFile.lastModified())}
                val manifest=if(!forceRefresh && attempt==0 && snapshot.first!=null && System.currentTimeMillis()-snapshot.third<ttl)
                    snapshot.first!! else fetch("platforms.json",256*1024L)
                val root=json.parseToJsonElement(manifest.decodeToString()).jsonObject
                require(root["schemaVersion"]?.jsonPrimitive?.int==2)
                val item=root.getValue("platforms").jsonArray.map{it.jsonObject}.single{it["id"]?.jsonPrimitive?.content==key}
                val path=item.getValue("path").jsonPrimitive.content;require(path=="platforms/$key/index.json")
                val hash=item.getValue("sha256").jsonPrimitive.content
                val size=item.getValue("sizeBytes").jsonPrimitive.long;require(size in 1..8L*1024*1024)
                val cached=snapshot.second
                val hit=!forceRefresh && cached!=null && valid(cached,hash,size)
                val bytes=if(hit)cached!! else fetch(path,8L*1024*1024).also {
                    require(valid(it,hash,size)){"Cheat index integrity mismatch"}
                    TypedCheatCatalog.parseIndex(it.decodeToString(),platform)
                }
                val entries=TypedCheatCatalog.parseIndex(bytes.decodeToString(),platform)
                synchronized(cacheLock){if(!hit)write(cachedIndex,bytes);write(manifestFile,manifest)}
                return RemoteCatalogResult(entries,false,cacheHit=hit)
            }catch(failure:Throwable){error=failure}
        }
        // An offline fallback is accepted only with a complete matching cached manifest.
        val fallback=runCatching { synchronized(cacheLock) {
            val root=json.parseToJsonElement(read(manifestFile).decodeToString()).jsonObject
            val item=root.getValue("platforms").jsonArray.map{it.jsonObject}.single{it["id"]?.jsonPrimitive?.content==key}
            val bytes=read(cachedIndex)
            require(valid(bytes,item.getValue("sha256").jsonPrimitive.content,item.getValue("sizeBytes").jsonPrimitive.long))
            TypedCheatCatalog.parseIndex(bytes.decodeToString(),platform)
        } }.getOrDefault(emptyList())
        return RemoteCatalogResult(fallback,true,error,cacheHit=fallback.isNotEmpty())
    }
    fun download(pack:TypedCheatPack):String {
        require(pack.id.matches(Regex("[a-z0-9-]+")) && pack.sha256.matches(Regex("[0-9a-f]{64}")))
        require(TypedCheatCatalog.safePackPath(pack.path,pack.platform))
        require(pack.sizeBytes in 1..4L*1024*1024)
        val cache=File(directory,"${pack.id}-${pack.sha256.take(16)}.json")
        val bytes=runCatching{read(cache).takeIf{valid(it,pack.sha256,pack.sizeBytes)}}.getOrNull()?:
            fetch(pack.path,4L*1024*1024).also {
                require(valid(it,pack.sha256,pack.sizeBytes)){"Cheat pack integrity mismatch"}
                TypedCheatCatalog.parsePack(it.decodeToString(),pack);write(cache,it)
            }
        return bytes.decodeToString().also{TypedCheatCatalog.parsePack(it,pack)}
    }
    private fun valid(bytes:ByteArray,hash:String,size:Long)=bytes.size.toLong()==size &&
        hash.matches(Regex("[0-9a-f]{64}")) && TypedCheatCatalog.sha256(bytes)==hash
    private fun read(file:File):ByteArray=synchronized(cacheLock){AtomicFile(file).openRead().use { input ->
        require(file.length()<=8L*1024*1024);input.readBytes()
    } }
    private fun write(file:File,bytes:ByteArray) = synchronized(cacheLock) {
        val atomic=AtomicFile(file);val output=atomic.startWrite()
        try{output.write(bytes);atomic.finishWrite(output)}catch(error:Throwable){atomic.failWrite(output);throw error}
    }
    private fun fetch(path:String,limit:Long):ByteArray {
        require(path=="platforms.json" || path.matches(Regex("platforms/[a-z0-9]+/(index\\.json|packs/[a-z0-9-]+\\.json)")))
        var failure:Throwable=IOException("Cheat source unavailable")
        for(base in bases) try {
            val connection=URL(base+path).openConnection() as HttpURLConnection
            connection.connectTimeout=15000;connection.readTimeout=20000;connection.instanceFollowRedirects=false
            try {
                require(connection.responseCode==200){"Cheat source HTTP ${connection.responseCode}"}
                require(connection.contentLengthLong<=limit){"Cheat source exceeds size limit"}
                return connection.inputStream.use { input ->
                    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(16*1024)
                    while(true){val count=input.read(buffer);if(count<0)break;require(output.size().toLong()+count<=limit);output.write(buffer,0,count)}
                    output.toByteArray()
                }
            }finally{connection.disconnect()}
        }catch(error:Throwable){failure=error}
        throw IOException("Cheat catalog download failed",failure)
    }
}
