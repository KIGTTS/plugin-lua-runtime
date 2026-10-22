package com.kigtts.engines.lua

import com.kigtts.plugin.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LuaStickerBrokerTest {
    @get:Rule val temp=TemporaryFolder()
    private val requests=mutableListOf<StickerCommand>()
    private fun environment()=object:PluginEnvironment {
        override val storage=PluginStorage(temp.newFolder())
        override val assets=storage.directory("bundled")
        override val stickerBroker=object:StickerBroker {
            override suspend fun request(command:StickerCommand):StickerReply {requests+=command;return when(command) {
                is StickerCommand.ReadImage->StickerReply.Resource(PluginResource(command.destination,"runtime.lua"),8)
                else->StickerReply.Snapshot(StickerSnapshot(7))}}
            override fun changes(afterRevision:Long)=emptyFlow<StickerChange>()
        }
        override val appBroker = object : AppBroker {
            override suspend fun request(command: AppCommand): AppReply {
                val operation = command as AppCommand.ManagedCall
                require(operation.projectId == "example" && operation.channel == "stickers")
                val reply = stickerBroker.request(PluginJson.decodeFromJsonElement<StickerCommand>(operation.command))
                return AppReply.Data(buildJsonObject { put("reply", PluginJson.encodeToJsonElement<StickerReply>(reply)) })
            }
        }
        override suspend fun download(url:String,relativePath:String,sha256:String?):File=error("Unavailable")
    }
    private fun project(env:PluginEnvironment,body:String,permissions:List<String>):Pair<LuaSourceStore,LuaProject> {
        val directory=env.storage.directory("imports/example");directory.resolve("assets").mkdirs();directory.resolve("assets/photo.png").writeBytes(byteArrayOf(1,2))
        directory.resolve("main.lua").writeText(body)
        directory.resolve("manifest.json").writeText(buildJsonObject {put("id","example");put("name","图库工具");put("version","1");put("permissions",JsonArray(permissions.map(::JsonPrimitive)))}.toString())
        val store=LuaSourceStore(env.storage);return store to store.confirm(store.stage(directory))
    }
    @Test fun approvedReadPermissionQueriesTypedHostAndExportStaysWithinProjectData()=runBlocking {
        val env=environment();val (store,project)=project(env,"return {execute=function(a,c) return host.stickers({type='read-image',imageId='image',destination='exports/photo.png'}) end}",listOf("stickers-read"))
        val value=LuaExecutor(env,store).call(project,"execute",PluginContext("",""),"read").jsonObject
        val command=requests.single() as StickerCommand.ReadImage
        assertEquals("projects/example/data/exports/photo.png",command.destination)
        assertEquals(command.destination,value["source"]!!.jsonObject["path"]!!.jsonPrimitive.content)
    }
    @Test fun missingPermissionAndUiRefreshCannotWriteOrCopyResources()=runBlocking {
        val env=environment();val (store,project)=project(env,"return {execute=function(a,c) return host.stickers({type='query'}) end,pages=function(c) return host.stickers({type='export-card',cardId='card',destination='exports/c.png'}) end}",emptyList())
        assertTrue(runCatching {LuaExecutor(env,store).call(project,"execute",PluginContext("",""),"query")}.isFailure);assertTrue(requests.isEmpty())
        val read=project.copy(manifest=project.manifest.copy(permissions=setOf("stickers-read")))
        assertTrue(runCatching {LuaExecutor(env,store).call(read,"pages",PluginContext("",""))}.isFailure);assertTrue(requests.isEmpty())
    }
    @Test fun foreignAssetsAndQuickTextConversionCannotBypassModulePermission()=runBlocking {
        val env=environment();val (store,project)=project(env,"return {execute=function(a,c) if a=='quick' then return host.stickers({type='publish-quick-media',imageId='image'}) end return host.stickers({type='publish-image',source={path='assets/photo.png',pluginId='other.plugin'}}) end}",listOf("stickers-read","stickers-write"))
        assertTrue(runCatching {LuaExecutor(env,store).call(project,"execute",PluginContext("",""),"quick")}.isFailure)
        assertTrue(runCatching {LuaExecutor(env,store).call(project,"execute",PluginContext("",""),"foreign")}.isFailure)
        assertTrue(requests.isEmpty())
    }
    @Test fun readOnlyPageRefreshCanQueryAlbumsAndOrderedPhotosWithoutWriteCapability()=runBlocking {
        val env=environment();val (store,project)=project(env,"return {pages=function(c) host.stickers({type='query-albums'});return host.stickers({type='query-album-cards',albumId='user.polaroids',limit=10}) end}",listOf("stickers-read"))
        LuaExecutor(env,store).call(project,"pages",PluginContext("",""))
        assertTrue(requests[0] is StickerCommand.QueryAlbums)
        val photos=requests[1] as StickerCommand.QueryAlbumCards
        assertEquals(DEFAULT_POLAROID_ALBUM_ID,photos.albumId);assertEquals(10,photos.limit)
    }
}
