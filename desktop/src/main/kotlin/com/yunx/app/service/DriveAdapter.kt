package com.yunx.app.service

import com.yunx.app.data.db.*
import com.yunx.app.data.network.*
import com.yunx.app.data.network.model.*
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

internal class DriveAdapter(
    val platform: String, val account: MultiAccountEntity, val credential: String, private val accounts: ServiceAccounts
) {
    val root: String get() = when (platform) {
        "baidu" -> "/"
        "xunlei", "guangya" -> ""
        "lanzou" -> LanzouConstants.ROOT_FOLDER_ID
        else -> "0"
    }
    private val baidu = BaiduApi(accounts.clientProvider)
    private val xunlei = accounts.xunleiApiFor(account as? XunleiAccountEntity)
    private val xl get() = account as XunleiAccountEntity
    private val il get() = account as ILanzouAccountEntity
    private val guangya = GuangYaApi(accounts.clientProvider).also { api ->
        api.deviceIdProvider = { (account as? GuangYaAccountEntity)?.deviceId }
    }

    suspend fun list(directory: String): List<ShareFile> = when (platform) {
        "quark" -> required(accounts.quarkApi.listAllCloudFiles(directory, credential))
        "uc" -> required(accounts.ucApi.listAllCloudFiles(directory, credential))
        "baidu" -> buildList {
            var page = 1
            do {
                val batch = baidu.listCloudFiles(directory, credential, page++)
                addAll(batch)
            } while (batch.size == 100)
        }
        "xunlei" -> buildList {
            var cursor = ""
            do {
                val page = required(xunlei.getFilesPage(directory, credential, xl.deviceId, xl.captchaToken, cursor))
                addAll(page.first)
                cursor = page.second.orEmpty()
            } while (cursor.isNotEmpty())
        }
        "c139" -> buildList {
            var cursor: String? = null
            do {
                val page = accounts.c139Api.listCloudFiles(directory, credential, cursor)
                addAll(page.first)
                cursor = page.second
            } while (cursor != null)
        }
        "pan123" -> buildList {
            var cursor = "0"
            do {
                val page = accounts.pan123Api.listCloudFiles(directory, credential, cursor)
                addAll(page.first)
                cursor = page.second.orEmpty()
            } while (cursor.isNotEmpty())
        }
        "pan115" -> buildList {
            var offset = 0
            do {
                val page = accounts.pan115Api.listFiles(directory, credential, offset)
                addAll(page.files)
                offset += page.files.size
            } while (page.files.isNotEmpty() && offset < page.total)
        }
        "guangya" -> guangya.listCloudFiles(credential, directory)
        "ilanzou" -> accounts.ilanzouApi.listFiles(credential, il.uuid, numeric(directory))
        "lanzou" -> lanzou().let { accounts.lanzouApi.listCloudFiles(credential, it.uid, it.vei, numeric(directory)) }
        else -> error("Unknown platform")
    }

    suspend fun createFolder(parent: String, name: String): String = when (platform) {
        "quark" -> required(accounts.quarkApi.createFolder(name, parent, credential))
        "uc" -> required(accounts.ucApi.createFolder(name, parent, credential))
        "xunlei" -> required(xunlei.createFolder(name, parent, credential, xl.deviceId, xl.captchaToken))
        "baidu" -> (parent.trimEnd('/') + "/" + name).also { confirmed(baidu.createDir(it, credential)) }
        "c139" -> accounts.c139Api.createDir(parent, name, credential)
        "pan123" -> accounts.pan123Api.createDir(parent, name, credential)
        "pan115" -> required(accounts.pan115Api.createDir(parent, name, credential))
        "guangya" -> guangya.createDir(credential, parent, name)
        "ilanzou" -> "d:" + accounts.ilanzouApi.createDir(credential, il.uuid, numeric(parent), name)
        "lanzou" -> "d:" + lanzou().let { accounts.lanzouApi.createFolder(credential, it.uid, it.vei, numeric(parent), name) }
        else -> error("Unknown platform")
    }

    suspend fun rename(file: ShareFile, name: String) {
        when (platform) {
            "quark" -> confirmed(accounts.quarkApi.renameFile(file.fid, name, credential))
            "uc" -> confirmed(accounts.ucApi.renameFile(file.fid, name, credential))
            "xunlei" -> confirmed(xunlei.renameFile(file.fid, name, credential, xl.deviceId, xl.captchaToken))
            "baidu" -> confirmed(baidu.renameFile(file.fidToken, name, credential))
            "c139" -> confirmed(accounts.c139Api.renameFile(file.fid, name, credential))
            "pan123" -> accounts.pan123Api.renameFile(file.fid, name, credential)
            "pan115" -> accounts.pan115Api.rename(file.fid, name, credential)
            "guangya" -> guangya.renameFile(credential, file.fid, name)
            "ilanzou" -> if (file.isdir) accounts.ilanzouApi.renameFolder(credential, il.uuid, numeric(file.fid), name)
                else accounts.ilanzouApi.renameFile(credential, il.uuid, numeric(file.fid), name)
            "lanzou" -> lanzou().let {
                if (file.isdir) accounts.lanzouApi.renameFolder(credential, it.uid, it.vei, numeric(file.fid), name)
                else accounts.lanzouApi.renameFile(credential, it.uid, it.vei, numeric(file.fid), name)
            }
        }
    }

    suspend fun mutate(file: ShareFile, target: String?): JSONObject {
        val task = when (platform) {
            "quark" -> required(if (target == null) accounts.quarkApi.deleteFile(file.fid, credential)
                else accounts.quarkApi.moveFile(file.fid, target, credential))
            "uc" -> required(if (target == null) accounts.ucApi.deleteFile(file.fid, credential)
                else accounts.ucApi.moveFile(file.fid, target, credential))
            "c139" -> if (target == null) accounts.c139Api.deleteFiles(listOf(file.fid), credential)
                else accounts.c139Api.moveFiles(listOf(file.fid), target, credential)
            "xunlei" -> if (target == null) {
                confirmed(xunlei.deleteFiles(listOf(file.fid), credential, xl.deviceId, xl.captchaToken)); ""
            } else required(xunlei.moveFile(listOf(file.fid), target, credential, xl.deviceId, xl.captchaToken))
            "baidu" -> {
                confirmed(if (target == null) baidu.deleteFiles(listOf(file.fidToken), credential)
                    else baidu.moveFiles(listOf(file.fidToken), target, credential)); null
            }
            "pan123" -> {
                if (target == null) accounts.pan123Api.deleteFiles(listOf(file), credential)
                else accounts.pan123Api.moveFiles(listOf(file.fid), target, credential)
                null
            }
            "pan115" -> {
                if (target == null) accounts.pan115Api.delete(listOf(file.fid), file.pdirFid, credential)
                else accounts.pan115Api.move(listOf(file.fid), target, credential)
                null
            }
            "guangya" -> {
                if (target == null) guangya.deleteFiles(credential, listOf(file.fid))
                else guangya.moveFiles(credential, listOf(file.fid), target)
                null
            }
            "ilanzou" -> {
                val folders = if (file.isdir) listOf(numeric(file.fid)) else emptyList()
                val files = if (file.isdir) emptyList() else listOf(numeric(file.fid))
                if (target == null) accounts.ilanzouApi.deleteEntries(credential, il.uuid, folders, files)
                else accounts.ilanzouApi.moveEntries(credential, il.uuid, folders, files, numeric(target))
                null
            }
            "lanzou" -> {
                val params = lanzou()
                if (target != null) {
                    if (file.isdir) throw ApiException(422, "UNSUPPORTED_OPERATION", "Lanzou cannot move folders")
                    accounts.lanzouApi.moveFile(credential, params.uid, params.vei, numeric(target), numeric(file.fid))
                } else if (file.isdir) accounts.lanzouApi.deleteFolder(credential, params.uid, params.vei, numeric(file.fid))
                else accounts.lanzouApi.deleteFile(credential, params.uid, params.vei, numeric(file.fid))
                null
            }
            else -> error("Unknown platform")
        }
        return JSONObject().put("fileId", file.fid).put("status", if (task != null || platform == "c139") "submitted" else "completed")
            .put("taskId", task ?: JSONObject.NULL)
    }

    suspend fun download(file: ShareFile): DownloadLink = required(when (platform) {
        "quark" -> accounts.quarkApi.getDownloadLink(file.fid, credential)
        "uc" -> accounts.ucApi.getDownloadLink(file.fid, credential) ?: accounts.ucApi.cloudGetDownloadLink(file.fid, credential)
        "xunlei" -> xunlei.getFileDetail(file.fid, credential, xl.deviceId, xl.captchaToken)
        "baidu" -> DownloadLink(file.fid, file.fname, baidu.locateDownload(file.fidToken, credential), file.fsize)
        "c139" -> accounts.c139Api.getDownloadUrl(file.fid, credential)
        "pan123" -> accounts.pan123Api.getDownloadLink(file, credential)
        "pan115" -> accounts.pan115Api.getDownloadLink(file, credential)
        "guangya" -> guangya.getDownloadLink(credential, file)
        "ilanzou" -> accounts.ilanzouApi.getDownloadLink(credential, il.uuid, numeric(file.fid), il.userId)
        "lanzou" -> lanzou().let { accounts.lanzouApi.getPersonalDownloadLink(credential, it.uid, it.vei, numeric(file.fid), file.fname) }
        else -> error("Unknown platform")
    })

    suspend fun createShare(files: List<ShareFile>, title: String, days: Int, password: String): ShareInfo = when (platform) {
        "quark" -> {
            val id = required(accounts.quarkApi.createShare(files.map { it.fid }, title, if (password.isEmpty()) 1 else 2, password, expireType(days), credential))
            required(accounts.quarkApi.getShareInfo(id, credential))
        }
        "uc" -> {
            val id = required(accounts.ucApi.createShare(files.map { it.fid }, title, if (password.isEmpty()) 1 else 2, password, expireType(days), credential))
            required(accounts.ucApi.getShareInfo(id, credential))
        }
        "xunlei" -> required(xunlei.createShare(files.map { it.fid }, title, if (days == 0) "-1" else days.toString(), credential, xl.deviceId, xl.captchaToken, password))
        "baidu" -> baidu.createShare(files.map { it.fid }, days, password, credential).let {
            val expiration = it.expiredType ?: days
            ShareInfo(it.link, it.pwd, it.shareId, title, expireType(expiration),
                warning = if (expiration in PlatformCapabilities.expirationDays(platform)) null else "Share created, but the returned expiration is unknown")
        }
        "c139" -> accounts.c139Api.createShare(files.filterNot { it.isdir }.map { it.fid }, files.filter { it.isdir }.map { it.fid }, days.takeIf { it > 0 }, title, credential)
        "pan123" -> {
            val expiration = if (days == 0) Pan123Constants.EXPIRATION_FOREVER else OffsetDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(days.toLong()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"))
            accounts.pan123Api.createShare(files.map { it.fid }, title, expiration, password.ifEmpty { null }, credential)
        }
        "pan115" -> accounts.pan115Api.createShare(files.map { it.fid }, if (days == 0) "-1" else days.toString(), credential)
        "guangya" -> guangya.createShare(credential, files.map { it.fid }, title, days.takeIf { it > 0 }, password.ifEmpty { null })
        "lanzou" -> lanzou().let {
            val file = files.single()
            if (file.isdir) accounts.lanzouApi.createFolderShare(credential, it.uid, it.vei, numeric(file.fid))
            else accounts.lanzouApi.createFileShare(credential, it.uid, it.vei, numeric(file.fid))
        }
        else -> throw ApiException(422, "UNSUPPORTED_OPERATION", "Creating shares is unavailable for this platform")
    }

    private suspend fun lanzou(): LanzouLoginParams = accounts.lanzouApi.fetchLoginParams(credential)
        ?: throw ApiException(401, "LOGIN_EXPIRED", "Please log in to Lanzou again")

    private fun numeric(id: String): String = id.removePrefix("d:").removePrefix("f:")

    private fun expireType(days: Int): Int = when (days) { 0 -> 1; 1 -> 2; 7 -> 3; 30 -> 4; else -> ShareExpire.UNKNOWN }

    private fun confirmed(success: Boolean) {
        if (!success) throw ApiException(502, "UPSTREAM_ERROR", "Platform did not confirm the operation")
    }

    private fun <T> required(value: T?): T = value ?: throw ApiException(502, "UPSTREAM_ERROR", "Platform returned no result")
}
