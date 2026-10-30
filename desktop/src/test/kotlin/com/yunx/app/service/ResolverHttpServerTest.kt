package com.yunx.app.service

import com.yunx.app.AppContext
import com.yunx.app.data.db.AppDatabase
import com.yunx.app.data.db.QuarkAccountEntity
import com.yunx.app.data.network.QuarkApi
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class ResolverHttpServerTest {
    private val client = HttpClient.newHttpClient()

    @Test
    fun `real HTTP server authenticates validates and manages login tasks`() {
        AppContext.init()
        val accounts = ServiceAccounts(AppDatabase.get())
        val logins = LoginTasks()
        ResolverHttpServer(0, "test-token", accounts, logins).use { server ->
            server.start()
            assertEquals(401, call(server, "GET", "/health", token = "wrong").first)
            assertEquals("ok", call(server, "GET", "/health").second.getString("status"))
            assertEquals(10, call(server, "GET", "/platforms").second.getJSONArray("platforms").length())
            val malformed = call(server, "POST", "/shares", """{"link":123}""")
            assertEquals(400, malformed.first)
            assertEquals("INVALID_REQUEST", malformed.second.getJSONObject("error").getString("code"))
            assertEquals(400, call(server, "POST", "/shares", "not-json").first)
            assertEquals(400, call(server, "POST", "/auth/logins", """{"platform":"unknown"}""").first)
            val login = call(server, "POST", "/auth/logins", """{"platform":"quark"}""").second
            val id = login.getString("id")
            assertEquals("pending", call(server, "GET", "/auth/logins/$id").second.getString("status"))
            assertEquals(409, call(server, "POST", "/auth/logins", """{"platform":"uc"}""").first)
            assertEquals("cancelled", call(server, "DELETE", "/auth/logins/$id").second.getString("status"))
            assertNull(logins.active.value)
            assertEquals("cancelled", logins.finish(id, "succeeded").status)
            val next = call(server, "POST", "/auth/logins", """{"platform":"uc","newAccount":true}""").second.getString("id")
            logins.finish(next, "succeeded", JSONObject().put("id", "saved-account"))
            assertEquals("saved-account", call(server, "GET", "/auth/logins/$next").second.getJSONObject("account").getString("id"))
            assertEquals(404, call(server, "POST", "/shares/missing/list", "{}").first)
            assertEquals(404, call(server, "DELETE", "/accounts/missing").first)
            assertEquals(404, call(server, "GET", "/health/extra").first)
        }
    }

    @Test
    fun `real Quark parser lists paginated files and preserves guest download headers`() {
        AppContext.init()
        MockWebServer().use { upstream ->
            upstream.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = request.requestUrl!!
                    val data = when {
                        url.encodedPath.endsWith("/sharepage/token") -> {
                            if (JSONObject(request.body.readUtf8()).optString("passcode") == "expired") {
                                return MockResponse().setBody("""{"status":401,"code":31001,"message":"expired"}""")
                            }
                            """{"stoken":"share-token","title":"测试分享","first_fid":"0"}"""
                        }
                        url.encodedPath.endsWith("/sharepage/detail") -> if (url.queryParameter("_page") == "1")
                            """{"list":[{"fid":"file-1","file_name":"测试.txt","size":42,"dir":false,"pdir_fid":"0","share_fid_token":"file-token"}]}"""
                            else """{"list":[]}"""
                        url.encodedPath.endsWith("/file/download") -> {
                            val body = JSONObject(request.body.readUtf8())
                            assertEquals("share-token", body.getString("stoken"))
                            assertEquals("file-token", body.getJSONArray("fids_token").getString(0))
                            return MockResponse().addHeader("Set-Cookie", "__pugs=guest-session; Path=/")
                                .setBody("""{"status":200,"code":0,"data":[{"fid":"file-1","file_name":"测试.txt","size":42,"download_url":"https://cdn.example/test"}]}""")
                        }
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setBody("""{"status":200,"code":0,"data":$data}""")
                }
            }
            upstream.start()
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val original = chain.request()
                val local = upstream.url(original.url.encodedPath).newBuilder().encodedQuery(original.url.encodedQuery).build()
                chain.proceed(original.newBuilder().url(local).build())
            }.build()
            val accounts = ServiceAccounts(AppDatabase.get(), QuarkApi { http })
            ResolverHttpServer(0, "test-token", accounts, LoginTasks()).use { server ->
                server.start()
                val share = call(server, "POST", "/shares", """{"link":"https://pan.quark.cn/s/abc123"}""").second
                assertEquals("测试分享", share.getString("title"))
                val id = share.getString("id")
                assertEquals("测试.txt", share.getJSONArray("files").getJSONObject(0).getString("name"))
                assertEquals(1, call(server, "POST", "/shares/$id/list", """{"directoryId":"0"}""").second.getJSONArray("files").length())
                val link = call(server, "POST", "/shares/$id/download-link", """{"fileId":"file-1"}""").second
                assertEquals("https://cdn.example/test", link.getString("url"))
                assertEquals("__pugs=guest-session", link.getJSONObject("headers").getString("Cookie"))
                assertEquals("https://pan.quark.cn/", link.getJSONObject("headers").getString("Referer"))
                assertEquals(42L, link.getLong("size"))
                assertEquals("", link.getString("cleanupId"))
                assertEquals(404, call(server, "POST", "/shares/$id/download-link", """{"fileId":"missing"}""").first)
                assertEquals(404, call(server, "POST", "/shares/$id/cleanup", """{"cleanupId":"missing"}""").first)
                val expired = call(server, "POST", "/shares", """{"link":"https://pan.quark.cn/s/abc123","password":"expired"}""")
                assertEquals(401, expired.first)
                assertEquals("LOGIN_EXPIRED", expired.second.getJSONObject("error").getString("code"))
            }
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun `account API returns metadata without stored credentials`() = runBlocking {
        AppContext.init()
        val db = AppDatabase.get()
        val id = "http-test-account"
        db.quarkAccountDao().upsert(QuarkAccountEntity(id = id, cookie = "__pus=secret; __puus=secret", nickname = "测试账号"))
        ResolverHttpServer(0, "test-token", ServiceAccounts(db), LoginTasks()).use { server ->
            server.start()
            val response = call(server, "GET", "/accounts").second.toString()
            assertTrue(response.contains(id))
            assertFalse(response.contains("secret"))
            assertEquals(200, call(server, "DELETE", "/accounts/$id").first)
            assertFalse(call(server, "GET", "/accounts").second.toString().contains(id))
        }
    }

    private fun call(server: ResolverHttpServer, method: String, path: String, body: String = "", token: String = "test-token"): Pair<Int, JSONObject> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}/api/v1$path"))
            .header("Authorization", "Bearer $token").header("Content-Type", "application/json")
            .method(method, if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to JSONObject(response.body())
    }
}
