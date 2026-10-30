package com.yunx.app.service

import com.yunx.app.data.network.*
import com.yunx.app.data.network.model.DownloadLink

/** The credential must be the same account/session used to obtain this link. */
internal fun downloadHeaders(platform: String, link: DownloadLink, credential: String): Map<String, String> = when (platform) {
    "xunlei" -> mapOf("User-Agent" to XunleiConstants.APP_UA)
    "baidu" -> mapOf("Cookie" to credential, "User-Agent" to BaiduConstants.UA_NETDISK)
    "c139" -> mapOf("User-Agent" to C139Constants.PC_UA)
    "pan115" -> mapOf("Cookie" to Pan115Constants.mergeCookies(credential, link.guestCookie),
        "User-Agent" to Pan115Constants.CLIENT_UA, "Referer" to Pan115Constants.DOWNLOAD_REFERER)
    "pan123" -> mapOf("User-Agent" to Pan123Constants.WEB_UA, "Referer" to Pan123Constants.DOWNLOAD_REFERER)
    "guangya" -> mapOf("User-Agent" to GuangYaConstants.WEB_UA, "Referer" to GuangYaConstants.DOWNLOAD_REFERER)
    "ilanzou" -> mapOf("User-Agent" to ILanzouConstants.WEB_UA, "Referer" to ILanzouConstants.DOWNLOAD_REFERER,
        "Origin" to ILanzouConstants.DOWNLOAD_ORIGIN)
    "lanzou" -> {
        val uri = java.net.URI(link.downloadUrl)
        mapOf("User-Agent" to LanzouConstants.WEB_UA, "Referer" to "${uri.scheme}://${uri.authority}/",
            "Cookie" to LanzouConstants.DOWN_IP_COOKIE)
    }
    "uc" -> if (link.guestCookie.isNotBlank()) mapOf("Cookie" to link.guestCookie,
        "User-Agent" to UCConstants.GUEST_UA, "Sec-Ch-Ua" to UCConstants.GUEST_SEC_CH_UA,
        "Referer" to UCConstants.DOWNLOAD_REFERER, "Origin" to UCConstants.WEB_ORIGIN)
        else mapOf("Cookie" to credential, "User-Agent" to UCConstants.USER_AGENT,
            "Referer" to UCConstants.DOWNLOAD_REFERER, "Origin" to UCConstants.WEB_ORIGIN)
    "quark" -> mapOf("Cookie" to link.guestCookie.ifBlank { credential },
        "User-Agent" to QuarkConstants.API_USER_AGENT, "Referer" to QuarkConstants.DOWNLOAD_REFERER)
    else -> error("Unknown platform")
}
