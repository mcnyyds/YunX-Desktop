package com.yunx.app.service

import com.yunx.app.data.db.*
import com.yunx.app.data.network.*
import com.yunx.app.data.network.model.*
import com.yunx.app.data.repository.*
import com.yunx.app.service.protocol.OpenShareRequest
import com.yunx.app.service.protocol.TransferRequest
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ShareService(private val accounts: ServiceAccounts) {
    private data class Share(
        val platform: String, val accountId: String, val repo: ShareResolveRepository, val session: ShareSession,
        val link: String, val password: String?,
        val files: MutableMap<String, ShareFile> = linkedMapOf(),
        val cleanups: MutableMap<String, Pair<String, String>> = linkedMapOf()
    )
    private val shares = linkedMapOf<String, Share>()

    suspend fun open(request: OpenShareRequest): JSONObject {
        val parsed = ShareLinkParser.parse(request.link)
            ?: throw ApiException(400, "INVALID_LINK", "Cannot identify a supported cloud share link")
        val platform = parsed.platform.name.lowercase()
        if (platform !in ServiceAccounts.platforms) throw ApiException(400, "UNSUPPORTED_PLATFORM", "Cloud shares only")
        val account = accounts.find(platform, request.accountId)
        val repo = repository(platform, request.accountId, account)
        val credential = accounts.credential(account)
        val session = repo.createSession(request.link, request.password.ifEmpty { null }, credential).getOrThrow()
        val share = Share(platform, request.accountId, repo, session, request.link, request.password.ifEmpty { parsed.pwd })
        val root = when (platform) {
            "quark" -> QuarkConstants.DEFAULT_PDIR_FID
            "uc" -> UCConstants.DEFAULT_PDIR_FID
            "pan115" -> Pan115Constants.ROOT_CID
            "xunlei", "c139", "pan123" -> "0"
            else -> ""
        }
        val files = repo.listFiles(session, root, credential).getOrThrow()
        files.forEach { share.files[it.fid] = it }
        val id = UUID.randomUUID().toString()
        shares[id] = share
        return JSONObject().put("id", id).put("platform", platform).put("title", session.title)
            .put("rootDirectoryId", root).put("files", filesJson(files))
    }

    suspend fun list(id: String, directoryId: String): JSONObject {
        val share = get(id)
        val credential = accounts.credential(accounts.find(share.platform, share.accountId))
        val files = share.repo.listFiles(share.session, directoryId, credential).getOrThrow()
        files.forEach { share.files[it.fid] = it }
        return JSONObject().put("files", filesJson(files))
    }

    suspend fun download(id: String, fileId: String, preferNoSave: Boolean): JSONObject {
        val share = get(id)
        val file = share.files[fileId] ?: throw ApiException(404, "FILE_NOT_FOUND", "List the containing directory first")
        if (file.isdir) throw ApiException(400, "IS_DIRECTORY", "Select an individual file")
        val credential = accounts.credential(accounts.find(share.platform, share.accountId))
        if (credential.isEmpty() && share.platform !in guestDownloadPlatforms) {
            throw ApiException(401, "LOGIN_REQUIRED", "Select a logged-in account for this platform")
        }
        val link = if (credential.isEmpty()) share.repo.getGuestShareDownloadLink(share.session, file).getOrThrow()
        else if (preferNoSave) share.repo.getShareDownloadLinkWithoutSave(share.session, file, credential)
            .recoverCatching { share.repo.getShareDownloadLink(share.session, file, credential).getOrThrow() }.getOrThrow()
        else share.repo.getShareDownloadLink(share.session, file, credential).getOrThrow()
        // Refreshing here would invalidate the identity tied to the freshly signed URL.
        val headers = downloadHeaders(share.platform, link, credential)
        val cleanupId = link.cleanupDirFid?.let { dir ->
            UUID.randomUUID().toString().also { share.cleanups[it] = dir to credential }
        }.orEmpty()
        return JSONObject().put("url", link.downloadUrl).put("headers", JSONObject(headers))
            .put("filename", link.filename).put("size", link.size).put("isHls", link.isHls).put("cleanupId", cleanupId)
    }

    suspend fun cleanup(id: String, cleanupId: String) {
        val share = get(id)
        val cleanup = share.cleanups[cleanupId] ?: throw ApiException(404, "CLEANUP_NOT_FOUND", "Cleanup task not found")
        share.repo.cleanupTempDir(cleanup.first, cleanup.second)
        share.cleanups.remove(cleanupId)
    }

    suspend fun transfer(id: String, request: TransferRequest, drives: DriveService): JSONObject {
        val share = get(id)
        val target = drives.adapter(request.accountId)
        if (target.platform != share.platform) throw ApiException(400, "PLATFORM_MISMATCH", "Share and destination account must use the same platform")
        if (share.platform == "lanzou") throw ApiException(422, "UNSUPPORTED_OPERATION", "Lanzou share transfer is unavailable")
        val selected = request.fileIds.map { fileId -> share.files[fileId]
            ?: throw ApiException(404, "FILE_NOT_FOUND", "List the containing share directory first") }
        val directory = drives.directory(target, request.targetDirectoryId)
        val repo = repository(target.platform, request.accountId, target.account)
        // Share tokens and platform repository state are recreated for the destination account.
        val session = repo.createSession(share.link, share.password, target.credential).getOrThrow()
        val refreshed = selected.groupBy { it.pdirFid }.values.flatMap { group ->
            repo.listFiles(session, group.first().pdirFid, target.credential).getOrThrow()
        }.associateBy { it.fid }
        val files = selected.map { refreshed[it.fid] ?: throw ApiException(404, "FILE_NOT_FOUND", "Shared entry no longer exists") }
        val checked = repo.ensureBatchSpace(files.map { it.fsize }, target.credential)
        val results = files.map { file ->
            val item = JSONObject().put("sourceFileId", file.fid).put("savedFileId", JSONObject.NULL)
            try {
                val savedId = repo.transferFile(session, file, directory, target.credential, checked).getOrThrow()
                item.put("status", "completed")
                if (share.platform !in setOf("guangya", "ilanzou", "pan115")) item.put("savedFileId", savedId)
            } catch (pending: TransferPendingException) {
                item.put("status", "submitted").put("taskId", pending.taskId.ifEmpty { null } ?: JSONObject.NULL)
                    .put("message", pending.message.orEmpty())
            } catch (pending: PlatformTaskPendingException) {
                item.put("status", "submitted").put("taskId", pending.taskId).put("message", pending.message.orEmpty())
            } catch (error: Exception) {
                item.put("status", "failed").put("error", operationError(error))
            }
            item
        }
        return JSONObject().put("targetDirectoryId", directory).put("results", JSONArray(results))
    }

    private fun get(id: String): Share = shares[id] ?: throw ApiException(404, "SHARE_NOT_FOUND", "Share session not found; reopen after service restart")

    private fun repository(platform: String, accountId: String, account: MultiAccountEntity?): ShareResolveRepository = when (platform) {
        "quark" -> QuarkResolveRepository(accounts.quarkApi)
        "uc" -> UCResolveRepository(accounts.ucApi)
        "baidu" -> BaiduResolveRepository(BaiduApi(accounts.clientProvider))
        "c139" -> C139ResolveRepository(accounts.c139Api)
        "pan115" -> Pan115ResolveRepository(accounts.pan115Api)
        "pan123" -> Pan123ResolveRepository(accounts.pan123Api) { null }
        "guangya" -> {
            val api = GuangYaApi(accounts.clientProvider)
            // Each share keeps its own device identity instead of borrowing the primary account's.
            api.deviceIdProvider = { (account as? GuangYaAccountEntity)?.deviceId }
            GuangYaResolveRepository(api)
        }
        "ilanzou" -> ILanzouResolveRepository(accounts.ilanzouApi, accounts.ilanzou) {
            accounts.find("ilanzou", accountId) as? ILanzouAccountEntity
        }
        "lanzou" -> LanzouResolveRepository(accounts.lanzouApi)
        "xunlei" -> {
            val api = accounts.xunleiApiFor(account as? XunleiAccountEntity)
            XunleiResolveRepository(api,
            accountProvider = { xunleiAccount(accountId)?.accessToken },
            deviceIdProvider = { xunleiAccount(accountId)?.deviceId },
            captchaProvider = { xunleiAccount(accountId)?.captchaToken },
            refreshProvider = {
                val account = xunleiAccount(accountId)
                if (account == null || account.refreshToken.isBlank()) null
                else api.refreshToken(account.refreshToken, account.deviceId, account.authType)?.also { (token, refresh) ->
                    accounts.db.xunleiAccountDao().upsert(account.copy(accessToken = token, refreshToken = refresh))
                }
            }).also { repo -> repo.accountByTokenProvider = { token -> accounts.xunlei.getAccounts().firstOrNull { it.accessToken == token } } }
        }
        else -> error("Unknown platform")
    }

    private suspend fun xunleiAccount(id: String): XunleiAccountEntity? = accounts.find("xunlei", id) as? XunleiAccountEntity

    private fun filesJson(files: List<ShareFile>): JSONArray = JSONArray(files.map { file ->
        JSONObject().put("id", file.fid).put("name", file.fname).put("size", file.fsize)
            .put("isDirectory", file.isdir).put("parentId", file.pdirFid)
    })

    companion object {
        val guestDownloadPlatforms = setOf("quark", "uc", "guangya", "ilanzou", "lanzou")
    }
}
