package com.yunx.app.service

import com.yunx.app.data.db.*
import com.yunx.app.data.network.*
import com.yunx.app.data.repository.*
import org.json.JSONObject

class ServiceAccounts(val db: AppDatabase, val quarkApi: QuarkApi = QuarkApi(),
    val clientProvider: () -> okhttp3.OkHttpClient = { HttpClients.apiClient() }) {
    val directory = AccountDirectory(db)
    val ucApi = UCApi(clientProvider)
    val xunleiApi = XunleiApi(clientProvider)
    val baiduApi = BaiduApi(clientProvider)
    val c139Api = C139Api(clientProvider)
    val pan123Api = Pan123Api(clientProvider)
    val pan115Api = Pan115Api(clientProvider)
    val guangyaApi = GuangYaApi(clientProvider)
    val ilanzouApi = ILanzouApi(clientProvider)
    val lanzouApi = LanzouApi(clientProvider)
    val quark = QuarkAccountRepository(db.quarkAccountDao(), quarkApi)
    val uc = UCAccountRepository(db.ucAccountDao(), ucApi)
    val xunlei = XunleiAccountRepository(db.xunleiAccountDao(), xunleiApi)
    val baidu = BaiduAccountRepository(db.baiduAccountDao(), baiduApi)
    val c139 = C139AccountRepository(db.c139AccountDao())
    val pan123 = Pan123AccountRepository(db.pan123AccountDao(), pan123Api)
    val pan115 = Pan115AccountRepository(db.pan115AccountDao(), pan115Api)
    val guangya = GuangYaAccountRepository(db.guangyaAccountDao(), guangyaApi)
    val ilanzou = ILanzouAccountRepository(db.ilanzouAccountDao(), ilanzouApi)
    val lanzou = LanzouAccountRepository(db.lanzouAccountDao(), lanzouApi)

    init {
        guangyaApi.deviceIdProvider = { guangya.cachedDeviceId() }
    }

    suspend fun list(): List<JSONObject> = platforms.flatMap { platform ->
        directory.list(platform).map { accountJson(platform, it) }
    }

    suspend fun find(platform: String, id: String): MultiAccountEntity? {
        if (id.isEmpty()) return null
        return directory.list(platform).firstOrNull { it.id == id }
            ?: throw ApiException(404, "ACCOUNT_NOT_FOUND", "Account not found for $platform")
    }

    suspend fun requireAccount(id: String): Pair<String, MultiAccountEntity> {
        for (platform in platforms) {
            directory.list(platform).firstOrNull { it.id == id }?.let { return platform to it }
        }
        throw ApiException(404, "ACCOUNT_NOT_FOUND", "Account not found")
    }

    internal fun xunleiApiFor(account: XunleiAccountEntity?): XunleiApi = XunleiApi(clientProvider).also { api ->
        api.cacheUserId(account?.accessToken.orEmpty())
        api.webCaptchaRefresher = { deviceId, action -> xunlei.refreshWebCaptcha(deviceId, action) }
        api.refreshTokenProvider = { deviceId ->
            val saved = xunlei.getAccounts().firstOrNull { it.id == account?.id && it.deviceId == deviceId }
            if (saved == null || saved.refreshToken.isBlank()) null
            else api.refreshToken(saved.refreshToken, deviceId, saved.authType)?.also { (token, refresh) ->
                db.xunleiAccountDao().upsert(saved.copy(accessToken = token, refreshToken = refresh))
            }
        }
    }

    suspend fun remove(id: String) {
        val platform = platforms.firstOrNull { p -> directory.list(p).any { it.id == id } }
            ?: throw ApiException(404, "ACCOUNT_NOT_FOUND", "Account not found")
        directory.remove(platform, id)
    }

    suspend fun credential(account: MultiAccountEntity?): String = when (account) {
        null -> ""
        is QuarkAccountEntity -> quark.cookieFor(account)
        is UCAccountEntity -> uc.cookieFor(account)
        is XunleiAccountEntity -> account.accessToken
        is BaiduAccountEntity -> account.cookie
        is C139AccountEntity -> account.cookie
        is Pan123AccountEntity -> account.accessToken
        is Pan115AccountEntity -> account.cookie
        is GuangYaAccountEntity -> if (account.isPrimary) guangya.ensureAccessToken().orEmpty() else account.accessToken
        is ILanzouAccountEntity -> account.appToken
        is LanzouAccountEntity -> account.cookie
        else -> error("Unknown account type")
    }

    companion object {
        val platforms = listOf("quark", "uc", "xunlei", "baidu", "c139", "pan123", "pan115", "guangya", "ilanzou", "lanzou")

        fun accountJson(platform: String, account: MultiAccountEntity): JSONObject = JSONObject()
            .put("id", account.id).put("platform", platform).put("nickname", account.nickname)
            .put("remark", account.remark).put("isPrimary", account.isPrimary)
    }
}
