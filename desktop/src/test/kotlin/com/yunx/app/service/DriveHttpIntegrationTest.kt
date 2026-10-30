package com.yunx.app.service

import com.yunx.app.AppContext
import com.yunx.app.data.db.*
import com.yunx.app.data.network.QuarkApi
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CopyOnWriteArrayList

class DriveHttpIntegrationTest {
    @Test
    fun `Quark personal operations preserve metadata and report asynchronous and partial results`() = runBlocking {
        val writes = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        DriveFixture { req ->
            val path = req.requestUrl!!.encodedPath
            val body = if (req.method == "POST") JSONObject(req.body.readUtf8()) else JSONObject()
            if (req.method == "POST") writes += path to body
            when {
                path == "/account/info" -> response("""{"success":true,"data":{"nickname":"fixture"}}""")
                path.endsWith("/file/sort") -> quark("""{"list":[{"fid":"file-1","file_name":"文档.txt","size":42,"dir":false,"pdir_fid":"0"},{"fid":"file-2","file_name":"other.txt","size":10,"dir":false,"pdir_fid":"0"},{"fid":"folder-1","file_name":"目录","dir":true,"pdir_fid":"0"}]}""")
                path.endsWith("/file/rename") -> if (body.getString("file_name") == "denied") response("""{"status":403,"code":99,"message":"denied"}""") else quark("{}")
                path.endsWith("/file/move") -> if (body.getJSONArray("filelist").getString(0) == "file-2") response("""{"status":403,"code":99,"message":"denied"}""") else quark("""{"task_id":"move-job"}""")
                path.endsWith("/file/delete") -> quark("""{"task_id":"delete-job"}""")
                path.endsWith("/file/download") -> response("""{"status":200,"code":0,"data":[{"fid":"file-1","file_name":"文档.txt","size":42,"download_url":"https://cdn.example/personal"}]}""")
                path.endsWith("/file") -> quark("""{"fid":"new-dir"}""")
                path.endsWith("/share") -> quark("""{"task_id":"share-job"}""")
                path.endsWith("/task") -> quark("""{"status":2,"share_id":"created-share"}""")
                path.endsWith("/share/password") -> quark("""{"share_url":"https://pan.quark.cn/s/result","passcode":"abcd","expired_type":3}""")
                else -> MockResponse().setResponseCode(404)
            }
        }.use { f ->
            assertTrue(f.accounts.quark.saveQuarkAccount("__pus=account; __puus=fresh"))
            f.track("quark", "quark")
            val list = f.post("/drives/list", """{"accountId":"quark"}""")
            assertEquals("0", list.second.getString("rootDirectoryId"))
            assertEquals("文档.txt", list.second.getJSONArray("files").getJSONObject(0).getString("name"))
            val download = f.post("/drives/download-link", """{"accountId":"quark","fileId":"file-1"}""").second
            assertEquals("__pus=account; __puus=fresh", download.getJSONObject("headers").getString("Cookie"))
            assertEquals("https://cdn.example/personal", download.getString("url"))
            assertEquals(400, f.post("/drives/download-link", """{"accountId":"quark","fileId":"folder-1"}""").first)
            val created = f.post("/drives/folders", """{"accountId":"quark","name":"新目录"}""")
            assertEquals(200, created.first)
            assertEquals("new-dir", created.second.getString("directoryId"))
            val shared = f.post("/drives/create-share", """{"accountId":"quark","fileIds":["file-1","file-2"],"expirationDays":7,"password":"abcd"}""")
            assertEquals(200, shared.first)
            assertEquals("https://pan.quark.cn/s/result", shared.second.getString("shareUrl"))
            val shareBody = writes.first { it.first.endsWith("/share") }.second
            assertEquals(3, shareBody.getInt("expired_type"))
            assertEquals(2, shareBody.getJSONArray("fid_list").length())
            assertEquals(502, f.post("/drives/rename", """{"accountId":"quark","fileId":"file-1","name":"denied"}""").first)
            val moved = f.post("/drives/move", """{"accountId":"quark","fileIds":["file-1","file-2"],"targetDirectoryId":"folder-1"}""").second.getJSONArray("results")
            assertEquals("submitted", moved.getJSONObject(0).getString("status"))
            assertEquals("move-job", moved.getJSONObject(0).getString("taskId"))
            assertEquals("failed", moved.getJSONObject(1).getString("status"))
            assertEquals(404, f.post("/drives/download-link", """{"accountId":"quark","fileId":"file-1"}""").first)
            val removed = f.post("/drives/delete", """{"accountId":"quark","fileIds":["file-2"]}""").second.getJSONArray("results")
            assertEquals("submitted", removed.getJSONObject(0).getString("status"))
        }
    }

    @Test
    fun `Baidu uses fs id for shares and stored path for file operations and directories`() = runBlocking {
        val observed = CopyOnWriteArrayList<String>()
        DriveFixture { req ->
            val path = req.requestUrl!!.encodedPath
            val body = req.body.readUtf8()
            observed += (req.path.orEmpty() + " " + body)
            when {
                path == "/api/gettemplatevariable" -> response("""{"errno":0,"result":{"bdstoken":"fixture-token"}}""")
                path == "/api/list" -> response("""{"errno":0,"list":[{"fs_id":"123","server_filename":"file.txt","size":9,"isdir":0,"path":"/资料/file.txt"},{"fs_id":"456","server_filename":"子目录","isdir":1,"path":"/资料/子目录"}]}""")
                path == "/api/filemanager" -> response("""{"errno":0}""")
                path == "/share/set" -> response("""{"errno":0,"shorturl":"https://pan.baidu.com/s/result","shareid":"share","expiredType":7}""")
                else -> response("""{"errno":0,"bdstoken":"fixture-token"}""")
            }
        }.use { f ->
            f.accounts.db.baiduAccountDao().upsert(BaiduAccountEntity(id = "drive-baidu", cookie = "BDUSS=fixture"))
            f.track("baidu", "drive-baidu")
            assertEquals(200, f.post("/drives/list", """{"accountId":"drive-baidu"}""").first)
            assertEquals(200, f.post("/drives/list", """{"accountId":"drive-baidu","directoryId":"456"}""").first)
            assertTrue(observed.any { it.contains("dir=%2F%E8%B5%84%E6%96%99%2F%E5%AD%90%E7%9B%AE%E5%BD%95") })
            val shared = f.post("/drives/create-share", """{"accountId":"drive-baidu","fileIds":["123"],"expirationDays":0,"password":"abcd"}""")
            assertEquals(200, shared.first)
            assertEquals(7, shared.second.getInt("expirationDays"))
            val shareRequest = observed.first { it.startsWith("/share/set") }
            assertTrue(shareRequest.contains("fid_list=%5B123%5D"))
            assertTrue(shareRequest.contains("period=0"))
            assertEquals(200, f.post("/drives/rename", """{"accountId":"drive-baidu","fileId":"123","name":"new.txt"}""").first)
            assertTrue(observed.any { it.startsWith("/api/filemanager") && it.contains("%E8%B5%84%E6%96%99") && it.contains("new.txt") })
        }
    }

    @Test
    fun `Baidu tokens and cached file metadata stay within the selected account`() = runBlocking {
        val tokens = CopyOnWriteArrayList<String>()
        DriveFixture { req ->
            val owner = if (req.getHeader("Cookie") == "BDUSS=first") "first" else "second"
            when (req.requestUrl!!.encodedPath) {
                "/api/gettemplatevariable" -> response("""{"errno":0,"result":{"bdstoken":"token-$owner"}}""")
                "/api/list" -> {
                    tokens += req.requestUrl!!.queryParameter("bdstoken").orEmpty()
                    response("""{"errno":0,"list":[{"fs_id":"123","server_filename":"$owner.txt","isdir":0,"path":"/$owner.txt"}]}""")
                }
                else -> MockResponse().setResponseCode(500)
            }
        }.use { f ->
            f.accounts.db.baiduAccountDao().upsert(BaiduAccountEntity(id = "drive-first", cookie = "BDUSS=first"))
            f.accounts.db.baiduAccountDao().upsert(BaiduAccountEntity(id = "drive-second", cookie = "BDUSS=second", isPrimary = false))
            f.track("baidu", "drive-first"); f.track("baidu", "drive-second")
            assertEquals(200, f.post("/drives/list", """{"accountId":"drive-first"}""").first)
            assertEquals(404, f.post("/drives/rename", """{"accountId":"drive-second","fileId":"123","name":"new.txt"}""").first)
            assertEquals(200, f.post("/drives/list", """{"accountId":"drive-second"}""").first)
            assertEquals(listOf("token-first", "token-second"), tokens.toList())
        }
    }

    @Test
    fun `request arrays integers platform capabilities and account isolation are enforced before writes`() = runBlocking {
        val upstreamWrites = CopyOnWriteArrayList<String>()
        DriveFixture { req -> upstreamWrites += req.path.orEmpty(); MockResponse().setResponseCode(500) }.use { f ->
            f.accounts.db.ilanzouAccountDao().upsert(ILanzouAccountEntity(id = "drive-il", appToken = "fixture", uuid = "fixture-uuid"))
            f.track("ilanzou", "drive-il")
            f.accounts.db.pan115AccountDao().upsert(Pan115AccountEntity(id = "drive-115", cookie = "fixture"))
            f.track("pan115", "drive-115")
            val badBodies = listOf(
                """{"accountId":"drive-il","fileIds":[]}""",
                """{"accountId":"drive-il","fileIds":["a","a"]}""",
                """{"accountId":"drive-il","fileIds":[3]}""",
                """{"accountId":"drive-il","fileIds":["a"],"expirationDays":1.5}"""
            )
            badBodies.forEach { assertEquals(400, f.post("/drives/create-share", it).first) }
            assertEquals(422, f.post("/drives/create-share", """{"accountId":"drive-il","fileIds":["a"]}""").first)
            assertEquals(422, f.post("/drives/create-share", """{"accountId":"drive-115","fileIds":["a"],"expirationDays":30}""").first)
            assertEquals(422, f.post("/drives/create-share", """{"accountId":"drive-115","fileIds":["a"],"password":"abcd"}""").first)
            assertEquals(404, f.post("/drives/delete", """{"accountId":"drive-il","fileIds":["a"]}""").first)
            assertEquals(400, f.post("/drives/folders", """{"accountId":"drive-il","name":"../bad"}""").first)
            assertTrue(upstreamWrites.isEmpty())
            val platforms = f.get("/platforms").getJSONArray("platforms")
            val il = (0 until platforms.length()).map { platforms.getJSONObject(it) }.first { it.getString("id") == "ilanzou" }
            assertFalse(il.getBoolean("createShare"))
            assertTrue(il.getBoolean("transferShare"))
        }
    }

    @Test
    fun `123 directory cursor is followed and permanent expiration uses platform sentinel`() = runBlocking {
        val cursors = CopyOnWriteArrayList<String>()
        val shares = CopyOnWriteArrayList<JSONObject>()
        DriveFixture { req ->
            when {
                req.requestUrl!!.encodedPath.endsWith("/file/list/new") -> {
                    val cursor = req.requestUrl!!.queryParameter("next").orEmpty(); cursors += cursor
                    response(if (cursor == "0") """{"code":0,"data":{"InfoList":[{"FileId":1,"FileName":"one","Type":0,"Size":5}],"Next":"page-two"}}"""
                        else """{"code":0,"data":{"InfoList":[{"FileId":2,"FileName":"two","Type":0,"Size":5}],"Next":"-1"}}""")
                }
                req.requestUrl!!.encodedPath.endsWith("/share/create") -> {
                    shares += JSONObject(req.body.readUtf8())
                    response("""{"code":0,"data":{"ShareKey":"created"}}""")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }.use { f ->
            f.accounts.db.pan123AccountDao().upsert(Pan123AccountEntity(id = "drive-123", accessToken = "fixture"))
            f.track("pan123", "drive-123")
            val listed = f.post("/drives/list", """{"accountId":"drive-123"}""")
            assertEquals(200, listed.first)
            assertEquals(2, listed.second.getJSONArray("files").length())
            assertEquals(listOf("0", "page-two"), cursors.toList())
            val share = f.post("/drives/create-share", """{"accountId":"drive-123","fileIds":["1","2"],"expirationDays":0}""")
            assertEquals(200, share.first)
            assertEquals("2099-12-12T08:00:00+08:00", shares.single().getString("expiration"))
            assertEquals(2, shares.single().getJSONArray("fileIdList").length())
            assertEquals(0, share.second.getInt("expirationDays"))
        }
    }

    @Test
    fun `Xunlei directory follows next page token using selected account identity`() = runBlocking {
        val identities = CopyOnWriteArrayList<String>()
        val cursors = CopyOnWriteArrayList<String>()
        DriveFixture { req ->
            identities += req.getHeader("Authorization").orEmpty() + "/" + req.getHeader("X-Device-Id").orEmpty()
            val cursor = req.requestUrl!!.queryParameter("page_token").orEmpty(); cursors += cursor
            response(if (cursor.isEmpty()) """{"files":[{"id":"one","name":"one","kind":"drive#file","parent_id":""}],"next_page_token":"second"}"""
                else """{"files":[{"id":"two","name":"two","kind":"drive#file","parent_id":""}],"next_page_token":""}""")
        }.use { f ->
            f.accounts.db.xunleiAccountDao().upsert(XunleiAccountEntity(id = "drive-xl", accessToken = "selected-token", deviceId = "selected-device", captchaToken = "captcha"))
            f.track("xunlei", "drive-xl")
            val result = f.post("/drives/list", """{"accountId":"drive-xl"}""")
            assertEquals(200, result.first)
            assertEquals(2, result.second.getJSONArray("files").length())
            assertEquals(listOf("", "second"), cursors.toList())
            assertTrue(identities.all { it == "Bearer selected-token/selected-device" })
        }
    }

    @Test
    fun `ilanzou directory prefixes are converted for folder creation and rename`() = runBlocking {
        val writes = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        DriveFixture { req ->
            val path = req.requestUrl!!.encodedPath
            if (req.method == "POST") writes += path to JSONObject(req.body.readUtf8())
            when {
                path.endsWith("/file/list") -> response("""{"code":200,"list":[{"fileType":2,"folderId":12,"folderName":"目录"},{"fileType":1,"fileId":13,"fileName":"文件","fileSize":1}]}""")
                path.endsWith("/folder/save") -> response("""{"code":200,"list":[{"id":14}]}""")
                else -> response("""{"code":200}""")
            }
        }.use { f ->
            f.accounts.db.ilanzouAccountDao().upsert(ILanzouAccountEntity(id = "drive-il-prefix", appToken = "fixture", uuid = "fixture-uuid"))
            f.track("ilanzou", "drive-il-prefix")
            val listed = f.post("/drives/list", """{"accountId":"drive-il-prefix"}""")
            assertEquals(200, listed.first)
            assertEquals("d:12", listed.second.getJSONArray("files").getJSONObject(0).getString("fileId"))
            val created = f.post("/drives/folders", """{"accountId":"drive-il-prefix","parentId":"d:12","name":"new"}""")
            assertEquals(200, created.first)
            assertEquals("d:14", created.second.getString("directoryId"))
            assertEquals("12", writes.first { it.first.endsWith("/folder/save") }.second.getString("folderId"))
            assertEquals(200, f.post("/drives/rename", """{"accountId":"drive-il-prefix","fileId":"d:12","name":"renamed"}""").first)
            assertEquals("12", writes.first { it.first.endsWith("/folder/edit") }.second.get("folderId").toString())
        }
    }

    @Test
    fun `Quark transfer saves selected share entries into destination and reports item failure`() = runBlocking {
        val saves = CopyOnWriteArrayList<JSONObject>()
        DriveFixture { req ->
            val path = req.requestUrl!!.encodedPath
            when {
                path == "/account/info" -> response("""{"success":true,"data":{"nickname":"fixture"}}""")
                path.endsWith("/sharepage/token") -> quark("""{"stoken":"target-token","title":"share","first_fid":"0"}""")
                path.endsWith("/sharepage/detail") -> quark(if (req.requestUrl!!.queryParameter("_page") == "1")
                    """{"list":[{"fid":"shared-1","file_name":"one","size":5,"pdir_fid":"0","share_fid_token":"one-token"},{"fid":"shared-2","file_name":"two","size":5,"pdir_fid":"0","share_fid_token":"two-token"}]}""" else """{"list":[]}""")
                path.endsWith("/member") -> quark("""{"total_capacity":100000,"use_capacity":0}""")
                path.endsWith("/sharepage/save") -> {
                    val body = JSONObject(req.body.readUtf8()); saves += body
                    if (body.getJSONArray("fid_list").getString(0) == "shared-2") response("""{"status":403,"code":99,"message":"denied"}""")
                    else if (body.getString("to_pdir_fid") == "pending-destination") quark("""{"task_id":"pending-job"}""")
                    else quark("""{"task_id":"transfer-job"}""")
                }
                path.endsWith("/task") -> if (req.requestUrl!!.queryParameter("task_id") == "pending-job") quark("""{"status":1}""")
                    else quark("""{"status":2,"save_as":{"save_as_top_fids":["saved-1"]}}""")
                else -> MockResponse().setResponseCode(404)
            }
        }.use { f ->
            assertTrue(f.accounts.quark.saveQuarkAccount("__pus=target; __puus=fresh"))
            f.track("quark", "quark")
            val share = f.post("/shares", """{"link":"https://pan.quark.cn/s/abc123"}""").second.getString("id")
            val transfer = f.post("/shares/$share/transfer", """{"accountId":"quark","fileIds":["shared-1","shared-2"],"targetDirectoryId":"destination"}""")
            assertEquals(200, transfer.first)
            val results = transfer.second.getJSONArray("results")
            assertEquals("completed", results.getJSONObject(0).getString("status"))
            assertEquals("saved-1", results.getJSONObject(0).getString("savedFileId"))
            assertEquals("failed", results.getJSONObject(1).getString("status"))
            assertEquals("destination", saves.first().getString("to_pdir_fid"))
            val pending = f.post("/shares/$share/transfer", """{"accountId":"quark","fileIds":["shared-1"],"targetDirectoryId":"pending-destination"}""")
                .second.getJSONArray("results").getJSONObject(0)
            assertEquals("submitted", pending.getString("status"))
            assertEquals("pending-job", pending.getString("taskId"))
            assertTrue(pending.isNull("savedFileId"))
        }
    }
}

private class DriveFixture(respond: (RecordedRequest) -> MockResponse) : AutoCloseable {
    private val upstream = MockWebServer()
    private val http: OkHttpClient
    val accounts: ServiceAccounts
    private val server: ResolverHttpServer
    private val savedAccounts = mutableListOf<Pair<String, String>>()
    private val client = HttpClient.newHttpClient()

    init {
        AppContext.init()
        upstream.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = respond(request) }
        upstream.start()
        http = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            val url = upstream.url(req.url.encodedPath).newBuilder().encodedQuery(req.url.encodedQuery).build()
            chain.proceed(req.newBuilder().url(url).build())
        }.build()
        accounts = ServiceAccounts(AppDatabase.get(), QuarkApi { http }) { http }
        server = ResolverHttpServer(0, "drive-test", accounts, LoginTasks())
        server.start()
    }

    fun track(platform: String, id: String) { savedAccounts += platform to id }

    fun post(path: String, body: String): Pair<Int, JSONObject> = call("POST", path, body)
    fun get(path: String): JSONObject = call("GET", path, "").second

    private fun call(method: String, path: String, body: String): Pair<Int, JSONObject> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}/api/v1$path"))
            .header("Authorization", "Bearer drive-test").header("Content-Type", "application/json")
            .method(method, if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)).build()
        val result = client.send(request, HttpResponse.BodyHandlers.ofString())
        return result.statusCode() to JSONObject(result.body())
    }

    override fun close() {
        server.close()
        runBlocking { savedAccounts.forEach { (platform, id) -> accounts.directory.remove(platform, id) } }
        upstream.close()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }
}

private fun response(body: String) = MockResponse().setBody(body)
private fun quark(data: String) = response("""{"status":200,"code":0,"data":$data}""")
