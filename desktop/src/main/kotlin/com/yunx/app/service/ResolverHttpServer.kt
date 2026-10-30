package com.yunx.app.service

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.yunx.app.APP_VERSION
import com.yunx.app.data.network.QuarkApiException
import com.yunx.app.service.protocol.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.concurrent.Executors

class ApiException(val status: Int, val code: String, message: String) : RuntimeException(message)

class ResolverHttpServer(
    port: Int,
    private val token: String,
    private val accounts: ServiceAccounts,
    private val logins: LoginTasks
) : AutoCloseable {
    private val shares = ShareService(accounts)
    private val drives = DriveService(accounts)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    // Platform repositories keep mutable share state; process resolver requests in order.
    private val executor = Executors.newSingleThreadExecutor()
    val port: Int get() = server.address.port

    init {
        require(token.isNotBlank() && token.none { it == '\r' || it == '\n' }) { "A nonblank HTTP token is required" }
        server.executor = executor
        server.createContext("/") { exchange -> handle(exchange) }
    }

    fun start() = server.start()

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun handle(exchange: HttpExchange) {
        val result = try {
            if (exchange.requestHeaders.getFirst("Authorization") != "Bearer $token") {
                throw ApiException(401, "UNAUTHORIZED", "A valid service token is required")
            }
            200 to runBlocking { dispatch(exchange) }
        } catch (error: ApiException) {
            error.status to errorJson(error.code, error.message.orEmpty())
        } catch (error: QuarkApiException) {
            if (error.code == 31001) 401 to errorJson("LOGIN_EXPIRED", error.message.orEmpty())
            else 502 to errorJson("UPSTREAM_ERROR", error.message.orEmpty())
        } catch (error: JSONException) {
            400 to errorJson("INVALID_REQUEST", error.message.orEmpty())
        } catch (error: IllegalArgumentException) {
            400 to errorJson("INVALID_REQUEST", error.message.orEmpty())
        } catch (error: UnsupportedOperationException) {
            422 to errorJson("UNSUPPORTED_OPERATION", error.message.orEmpty())
        } catch (error: Exception) {
            502 to errorJson("UPSTREAM_ERROR", error.message ?: error.javaClass.simpleName)
        }
        exchange.use {
            val bytes = result.second.toString().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
            exchange.responseHeaders.set("Cache-Control", "no-store")
            exchange.sendResponseHeaders(result.first, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        }
    }

    private suspend fun dispatch(exchange: HttpExchange): JSONObject {
        val path = exchange.requestURI.path
        val parts = path.split('/')
        val id = parts.getOrNull(4).orEmpty()
        val routePath = when {
            path.startsWith("/api/v1/auth/logins/") && parts.size == 6 -> "/api/v1/auth/logins/{id}"
            path.startsWith("/api/v1/accounts/") && parts.size == 5 -> "/api/v1/accounts/{id}"
            path.startsWith("/api/v1/shares/") && parts.size == 6 -> "/api/v1/shares/{id}/${parts[5]}"
            else -> path
        }
        val route = "${exchange.requestMethod} $routePath"
        return when (route) {
            Routes.HEALTH -> JSONObject().put("status", "ok").put("version", APP_VERSION).put("apiVersion", 1)
            Routes.PLATFORMS -> JSONObject().put("platforms", JSONArray(ServiceAccounts.platforms.map { platform ->
                PlatformCapabilities.json(platform)
            }))
            Routes.START_LOGIN -> LoginRequest.read(body(exchange)).let { logins.start(it.platform, it.newAccount).json() }
            Routes.GET_LOGIN -> logins.get(parts[5]).json()
            Routes.CANCEL_LOGIN -> logins.finish(parts[5], "cancelled").json()
            Routes.ACCOUNTS -> JSONObject().put("accounts", JSONArray(accounts.list()))
            Routes.REMOVE_ACCOUNT -> { accounts.remove(id); JSONObject().put("removed", true) }
            Routes.OPEN_SHARE -> shares.open(OpenShareRequest.read(body(exchange)))
            Routes.LIST_FILES -> ListRequest.read(body(exchange)).let { shares.list(id, it.directoryId) }
            Routes.GET_DOWNLOAD_LINK -> DownloadRequest.read(body(exchange)).let { shares.download(id, it.fileId, it.preferNoSave) }
            Routes.DRIVE_LIST -> drives.list(DriveListRequest.read(body(exchange)))
            Routes.CREATE_FOLDER -> drives.createFolder(CreateFolderRequest.read(body(exchange)))
            Routes.RENAME -> drives.rename(RenameRequest.read(body(exchange)))
            Routes.MOVE -> drives.move(MoveRequest.read(body(exchange)))
            Routes.DELETE_FILES -> drives.delete(DeleteRequest.read(body(exchange)))
            Routes.DRIVE_DOWNLOAD_LINK -> drives.download(DriveDownloadRequest.read(body(exchange)))
            Routes.CREATE_SHARE -> drives.createShare(CreateShareRequest.read(body(exchange)))
            Routes.TRANSFER_SHARE -> TransferRequest.read(body(exchange)).let { shares.transfer(id, it, drives) }
            Routes.CLEANUP -> CleanupRequest.read(body(exchange)).let {
                shares.cleanup(id, it.cleanupId)
                JSONObject().put("cleaned", true)
            }
            else -> throw ApiException(404, "NOT_FOUND", "Unknown API method or path")
        }
    }

    private fun body(exchange: HttpExchange): JSONObject = exchange.requestBody.bufferedReader(Charsets.UTF_8).use {
        JSONObject(it.readText())
    }

    private fun errorJson(code: String, message: String): JSONObject =
        JSONObject().put("error", JSONObject().put("code", code).put("message", message))
}
