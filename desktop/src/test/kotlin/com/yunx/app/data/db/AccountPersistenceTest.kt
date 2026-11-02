package com.yunx.app.data.db

import com.yunx.app.AppContext
import com.yunx.app.service.ServiceAccounts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class AccountPersistenceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `accounts and credentials survive abrupt process exit before checkpoint`() {
        val data = temporary.newFolder("abrupt")
        runProcess(data, "write", "halt")
        assertTrue("Test must leave uncheckpointed writes", File(data, "yunx.db-wal").length() > 0)
        runProcess(data, "read", "halt")
        runProcess(data, "read", "normal")
    }

    @Test
    fun `accounts and credentials survive normal process restart`() {
        val data = temporary.newFolder("normal")
        runProcess(data, "write", "normal")
        runProcess(data, "read", "normal")
    }

    private fun runProcess(data: File, operation: String, exit: String) {
        val output = temporary.newFile()
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").path,
            "-cp", System.getProperty("java.class.path"),
            AccountPersistenceProcess::class.java.name, operation, exit
        )
            .apply { environment()["YUNX_DESKTOP_DATA_DIR"] = data.absolutePath }
            .redirectErrorStream(true).redirectOutput(output).start()
        val finished = process.waitFor(45, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        assertTrue("Persistence process timed out: ${output.readText()}", finished)
        assertEquals(output.readText(), 0, process.exitValue())
    }
}

object AccountPersistenceProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        AppContext.init()
        val db = AppDatabase.get()
        runBlocking {
            if (args[0] == "write") {
                db.quarkAccountDao().upsert(QuarkAccountEntity(id = "quark-primary", cookie = "test-cookie-1", nickname = "Primary"))
                db.quarkAccountDao().upsert(QuarkAccountEntity(id = "quark-secondary", cookie = "test-cookie-2", nickname = "Secondary", isPrimary = false, remark = "Second"))
                db.pan123AccountDao().upsert(Pan123AccountEntity(id = "pan123", accessToken = "test-token", account = "test-user", nickname = "123"))
            } else {
                val quark = db.quarkAccountDao().getAccounts()
                check(quark.size == 2) { "Expected 2 Quark accounts after restart, got ${quark.size}" }
                check(quark[0].id == "quark-primary" && quark[0].isPrimary && quark[0].cookie == "test-cookie-1")
                check(quark[1].id == "quark-secondary" && !quark[1].isPrimary && quark[1].cookie == "test-cookie-2" && quark[1].remark == "Second")
                check(db.pan123AccountDao().getAccount()?.accessToken == "test-token")
                check(ServiceAccounts(db).directory.list("quark").size == 2)
            }
        }
        if (args[1] == "halt") Runtime.getRuntime().halt(0)
    }
}
