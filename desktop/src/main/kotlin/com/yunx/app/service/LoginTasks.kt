package com.yunx.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class LoginTask(
    val id: String,
    val platform: String,
    val newAccount: Boolean,
    val status: String = "pending",
    val account: JSONObject? = null,
    val message: String = ""
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("platform", platform).put("status", status)
        .put("account", account ?: JSONObject.NULL).put("message", message)
}

class LoginTasks {
    private val tasks = ConcurrentHashMap<String, LoginTask>()
    private val current = MutableStateFlow<LoginTask?>(null)
    val active = current.asStateFlow()

    @Synchronized
    fun start(platform: String, newAccount: Boolean): LoginTask {
        if (platform !in ServiceAccounts.platforms) throw ApiException(400, "UNSUPPORTED_PLATFORM", "Unsupported platform")
        if (current.value != null) throw ApiException(409, "LOGIN_IN_PROGRESS", "Finish or cancel the current login first")
        val task = LoginTask(UUID.randomUUID().toString(), platform, newAccount)
        tasks[task.id] = task
        current.value = task
        return task
    }

    fun get(id: String): LoginTask = tasks[id] ?: throw ApiException(404, "LOGIN_NOT_FOUND", "Login task not found")

    @Synchronized
    fun finish(id: String, status: String, account: JSONObject? = null, message: String = ""): LoginTask {
        val task = get(id)
        if (task.status != "pending") return task
        val finished = task.copy(status = status, account = account, message = message)
        tasks[id] = finished
        current.value = null
        return finished
    }
}
