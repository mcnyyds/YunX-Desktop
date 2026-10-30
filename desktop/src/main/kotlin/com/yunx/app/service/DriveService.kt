package com.yunx.app.service

import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.PlatformTaskPendingException
import com.yunx.app.service.protocol.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class DriveService(private val accounts: ServiceAccounts) {
    private val entries = mutableMapOf<Pair<String, String>, ShareFile>()

    suspend fun list(request: DriveListRequest): JSONObject {
        val drive = adapter(request.accountId)
        val directory = directory(drive, request.directoryId)
        val files = drive.list(directory)
        // Replace this directory's snapshot so removed entries cannot be used through stale metadata.
        entries.entries.removeAll { it.key.first == request.accountId && it.value.pdirFid == directory }
        files.forEach { entries[request.accountId to it.fid] = it.copy(pdirFid = directory) }
        return JSONObject().put("rootDirectoryId", drive.root).put("directoryId", directory).put("files", JSONArray(files.map {
            JSONObject().put("fileId", it.fid).put("name", it.fname).put("size", it.fsize)
                .put("isDirectory", it.isdir).put("parentId", directory)
        }))
    }

    suspend fun createFolder(request: CreateFolderRequest): JSONObject {
        validateName(request.name)
        val drive = adapter(request.accountId)
        val id = drive.createFolder(directory(drive, request.parentId), request.name)
        return JSONObject().put("directoryId", id).put("status", "completed")
    }

    suspend fun rename(request: RenameRequest): JSONObject {
        validateName(request.name)
        val drive = adapter(request.accountId)
        val file = entry(request.accountId, request.fileId)
        drive.rename(file, request.name)
        // Baidu paths and descendants change on rename; refresh rather than inventing new metadata.
        invalidate(request.accountId, file)
        return JSONObject().put("fileId", file.fid).put("status", "completed")
    }

    suspend fun move(request: MoveRequest): JSONObject {
        val drive = adapter(request.accountId)
        val target = directory(drive, request.targetDirectoryId)
        return mutate(drive, request.fileIds, target)
    }

    suspend fun delete(request: DeleteRequest): JSONObject = mutate(adapter(request.accountId), request.fileIds, null)

    suspend fun download(request: DriveDownloadRequest): JSONObject {
        val drive = adapter(request.accountId)
        val file = entry(request.accountId, request.fileId)
        if (file.isdir) throw ApiException(400, "IS_DIRECTORY", "Select an individual file")
        val link = drive.download(file)
        return JSONObject().put("url", link.downloadUrl).put("filename", file.fname).put("size", file.fsize)
            .put("headers", JSONObject(downloadHeaders(drive.platform, link, drive.credential))).put("isHls", link.isHls)
    }

    suspend fun createShare(request: CreateShareRequest): JSONObject {
        val drive = adapter(request.accountId)
        validateShare(drive.platform, request)
        val files = request.fileIds.map { entry(request.accountId, it) }
        val title = request.title.ifEmpty { files.first().fname }
        val days = request.expirationDays.takeIf { it >= 0 } ?: 0
        val password = if (drive.platform == "baidu" && request.password.isEmpty()) UUID.randomUUID().toString().take(4) else request.password
        val info = drive.createShare(files, title, days, password)
        val actualDays = if (info.warning != null || drive.platform == "lanzou") null else when (info.expiredType) {
            1, 101 -> 0; 2, 102 -> 1; 3, 104 -> 7; 4 -> 30; 103 -> 3; 105 -> 15
            else -> days
        }
        return JSONObject().put("shareUrl", info.shareUrl).put("password", info.passcode)
            .put("expirationDays", actualDays ?: JSONObject.NULL).put("warning", info.warning ?: "")
    }

    internal suspend fun adapter(accountId: String): DriveAdapter {
        val (platform, account) = accounts.requireAccount(accountId)
        val credential = accounts.credential(account)
        return DriveAdapter(platform, account, credential, accounts)
    }

    internal fun directory(drive: DriveAdapter, id: String): String {
        if (id.isEmpty()) return drive.root
        val known = entries[drive.account.id to id]
        if (known != null) {
            if (!known.isdir) throw ApiException(400, "NOT_DIRECTORY", "Target must be a directory")
            return if (drive.platform == "baidu") known.fidToken else known.fid
        }
        if (drive.platform == "baidu" && !id.startsWith('/')) {
            throw ApiException(404, "FILE_NOT_FOUND", "List the containing directory first")
        }
        return id
    }

    private fun entry(accountId: String, fileId: String): ShareFile = entries[accountId to fileId]
        ?: throw ApiException(404, "FILE_NOT_FOUND", "List the containing directory first; metadata is cleared after service restart or mutation")

    private suspend fun mutate(drive: DriveAdapter, fileIds: List<String>, target: String?): JSONObject {
        val files = fileIds.map { entry(drive.account.id, it) }
        val results = files.map { file ->
            try {
                val result = drive.mutate(file, target)
                invalidate(drive.account.id, file)
                result
            } catch (pending: PlatformTaskPendingException) {
                invalidate(drive.account.id, file)
                JSONObject().put("fileId", file.fid).put("status", "submitted").put("taskId", pending.taskId)
                    .put("message", pending.message.orEmpty())
            } catch (error: Exception) {
                JSONObject().put("fileId", file.fid).put("status", "failed").put("error", operationError(error))
            }
        }
        return JSONObject().put("results", JSONArray(results))
    }

    private fun invalidate(accountId: String, file: ShareFile) {
        entries.remove(accountId to file.fid)
        if (file.isdir) entries.entries.removeAll { it.key.first == accountId }
    }

    private fun validateName(name: String) {
        require(name != "." && name != ".." && '/' !in name && '\\' !in name) { "Name must be a single file or folder name" }
    }

    private fun validateShare(platform: String, request: CreateShareRequest) {
        if (platform == "ilanzou") throw ApiException(422, "UNSUPPORTED_OPERATION", "Creating shares is unavailable for ilanzou")
        if (platform == "lanzou" && request.fileIds.size != 1) throw ApiException(422, "UNSUPPORTED_OPERATION", "Lanzou shares one entry at a time")
        require(request.expirationDays >= -1) { "expirationDays must be -1 (platform default), 0 (permanent), or a supported number of days" }
        if (request.expirationDays >= 0 && request.expirationDays !in PlatformCapabilities.expirationDays(platform)) {
            throw ApiException(422, "UNSUPPORTED_OPERATION", "This expiration is unavailable for $platform")
        }
        if (request.password.isNotEmpty() && !PlatformCapabilities.customPassword(platform)) {
            throw ApiException(422, "UNSUPPORTED_OPERATION", "Custom share passwords are unavailable for $platform")
        }
        if (request.password.isNotEmpty() && platform in setOf("quark", "uc", "xunlei", "baidu")) {
            require(Regex("[A-Za-z0-9]{4}").matches(request.password)) { "password must contain 4 letters or digits" }
        }
    }
}

internal fun operationError(error: Exception): JSONObject = JSONObject()
    .put("code", (error as? ApiException)?.code ?: "UPSTREAM_ERROR")
    .put("message", error.message ?: error.javaClass.simpleName)
