package com.sbro.emucored.data

import com.sbro.emucored.data.cheats.*
import org.junit.Assert.*
import org.junit.Test

class TypedCheatCatalogTest {
    @Test fun storedTypedBlocksRetainSourceAndFormatsWithoutTrustingEnabledFlags() {
        val block=CheatBlock("source_1","Lives",listOf("010315DA"),true,format="gb-gameshark",
            sourceUrl="https://example.test/source",sourcePackId="source",license="CC-BY-SA-4.0",manualAssociation=true)
        val restored=TypedCheatCatalog.parseStored(TypedCheatCatalog.serialize(listOf(block))).single()
        assertEquals(block.copy(enabled=false),restored)
    }
    @Test(expected=IllegalArgumentException::class)
    fun futureStoredSchemaCannotBeSilentlyInterpretedAsCurrentCodes() {
        TypedCheatCatalog.parseStored("""{"schemaVersion":99,"blocks":[]}""")
    }
    @Test fun gatewayImportsRemainParseableAfterMergeSerialization() {
        val blocks=listOf(CheatBlock("lives_1","Lives",listOf("02000000 00000063"),false),
            CheatBlock("jump_2","Jump",listOf("02000004 00000001"),false))
        assertEquals(blocks.map{it.title to it.lines},parseCheatBlocks(serializeImportedCheatBlocks(blocks)).map{it.title to it.lines})
    }
    @Test fun identityMatchesRequirePlatformAndExactDigestWithoutTitleFallback() {
        val pack = TypedCheatPack("contra", GamePlatform.NES, "Contra", "platforms/nes/packs/contra.json",
            "a".repeat(64), 100, 1, setOf("nes-game-genie"),
            listOf(TypedCheatIdentity(sha1="B".repeat(40), sizeBytes=131088)))
        assertTrue(pack.matches(CheatGameIdentity(GamePlatform.NES, sha1="B".repeat(40), sizeBytes=131088)))
        assertFalse(pack.matches(CheatGameIdentity(GamePlatform.NES, sha1="C".repeat(40), sizeBytes=131088)))
        assertFalse(pack.matches(CheatGameIdentity(GamePlatform.SNES, sha1="B".repeat(40), sizeBytes=131088)))
        assertFalse(pack.copy(identities=emptyList()).matches(CheatGameIdentity(GamePlatform.NES, sha1="B".repeat(40))))
    }
    @Test fun differentDiscRevisionsAndRegionsNeverMatchAnExactRevisionPack() {
        val pack = TypedCheatPack("gc",GamePlatform.GAMECUBE,"Game","platforms/gamecube/packs/gc.json","a".repeat(64),100,1,
            setOf("dolphin-gecko"),listOf(TypedCheatIdentity(gameId="GMSE01",revision=1)))
        assertTrue(pack.matches(CheatGameIdentity(GamePlatform.GAMECUBE,gameId="GMSE01",revision=1)))
        assertFalse(pack.matches(CheatGameIdentity(GamePlatform.GAMECUBE,gameId="GMSP01",revision=1)))
        assertFalse(pack.matches(CheatGameIdentity(GamePlatform.GAMECUBE,gameId="GMSE01",revision=0)))
        assertFalse(pack.matches(CheatGameIdentity(GamePlatform.GAMECUBE,gameId="GMSE01")))
    }
    @Test fun downloadPathsCannotEscapeThePlatformCatalog() {
        assertTrue(TypedCheatCatalog.safePackPath("platforms/nes/packs/abc-123.json",GamePlatform.NES))
        for(path in listOf("../secrets", "platforms/nes/packs/../../file.json", "https://evil.test/file", "platforms/snes/packs/abc.json"))
            assertFalse(TypedCheatCatalog.safePackPath(path,GamePlatform.NES))
    }
}
