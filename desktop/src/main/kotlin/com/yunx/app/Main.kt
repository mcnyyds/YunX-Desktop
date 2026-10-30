package com.yunx.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.window.application
import com.yunx.app.data.db.AppDatabase
import com.yunx.app.data.network.HttpClients
import com.yunx.app.data.network.SystemProxy
import com.yunx.app.data.network.XunleiDeviceFingerprint
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.service.*
import com.yunx.app.ui.jcef.JcefHolder
import com.yunx.app.util.RenderApiChooser
import com.yunx.app.util.SingleInstance

fun main() {
    val config = serviceConfig()
    AppContext.init()
    check(SingleInstance.acquire()) { "YunX HTTP service is already running for this data directory" }
    configureRuntime()
    val accounts = ServiceAccounts(AppDatabase.get())
    val logins = LoginTasks()
    val server = ResolverHttpServer(config.first, config.second, accounts, logins)
    Runtime.getRuntime().addShutdownHook(Thread {
        server.close()
        JcefHolder.disposeQuietly()
    })
    server.start()
    println("YunX HTTP service listening on http://127.0.0.1:${server.port}/api/v1")
    try {
        application(exitProcessOnExit = false) {
            val task by logins.active.collectAsState()
            task?.let { login -> key(login.id) { LoginWindow(login, logins, accounts) } }
        }
    } finally {
        server.close()
    }
}

private fun serviceConfig(): Pair<Int, String> {
    val port = (System.getenv("YUNX_HTTP_PORT") ?: "17890").toInt()
    require(port in 1..65535) { "YUNX_HTTP_PORT must be between 1 and 65535" }
    val token = System.getenv("YUNX_HTTP_TOKEN").orEmpty()
    require(token.isNotBlank() && token.none { it == '\r' || it == '\n' }) { "Set YUNX_HTTP_TOKEN before starting the service" }
    return port to token
}

private fun configureRuntime() {
    val renderApi = System.getenv("YUNXPC_RENDER_API") ?: RenderApiChooser.chooseWindows()
    System.setProperty("skiko.renderApi", renderApi)
    val settings = SettingsRepository()
    when (settings.proxyMode) {
        SettingsRepository.PROXY_MODE_MANUAL -> HttpClients.setProxy(settings.proxyHost, settings.proxyPort)
        SettingsRepository.PROXY_MODE_SYSTEM -> {
            val proxy = SystemProxy.inspect()
            if (proxy is SystemProxy.Inspect.Proxy) HttpClients.setProxy(proxy.host, proxy.port)
        }
    }
    HttpClients.setHttp2Enabled(settings.http2Enabled)
    XunleiDeviceFingerprint.init()
}
