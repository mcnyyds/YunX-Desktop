package com.yunx.app.service

import org.json.JSONArray
import org.json.JSONObject

internal object PlatformCapabilities {
    fun expirationDays(platform: String): List<Int> = when (platform) {
        "pan115" -> listOf(0, 1, 3, 5, 7, 15)
        "lanzou", "ilanzou" -> emptyList()
        else -> listOf(0, 1, 7, 30)
    }

    fun customPassword(platform: String) = platform !in setOf("c139", "pan115", "lanzou", "ilanzou")

    fun json(platform: String): JSONObject = JSONObject().put("id", platform).put("login", true).put("share", true)
        .put("guestDownload", platform in ShareService.guestDownloadPlatforms)
        .put("listFiles", true).put("createFolder", true).put("rename", true).put("move", true).put("delete", true)
        .put("personalDownload", true).put("createShare", platform != "ilanzou")
        .put("transferShare", platform != "lanzou").put("batchShare", platform !in setOf("lanzou", "ilanzou"))
        .put("moveDirectory", platform != "lanzou").put("customSharePassword", customPassword(platform))
        .put("allowedExpirationDays", JSONArray(expirationDays(platform)))
}
