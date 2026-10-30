package com.yunx.app.service

import com.yunx.app.data.network.Pan115Constants
import com.yunx.app.data.network.UCConstants
import com.yunx.app.data.network.model.DownloadLink
import org.junit.Assert.*
import org.junit.Test

class DownloadHeadersTest {
    @Test
    fun `115 keeps account and response CDN cookies together`() {
        val link = DownloadLink("file", "name", "https://cdn.example/file", 1, guestCookie = "cdn_token=download-session")
        val headers = downloadHeaders("pan115", link, "UID=account; SEID=login-session")
        assertTrue(headers.getValue("Cookie").contains("UID=account"))
        assertTrue(headers.getValue("Cookie").contains("cdn_token=download-session"))
        assertEquals(Pan115Constants.CLIENT_UA, headers["User-Agent"])
    }

    @Test
    fun `UC guest link uses guest identity and required origin headers`() {
        val link = DownloadLink("file", "name", "https://cdn.example/file", 1, guestCookie = "__pugs=guest")
        val headers = downloadHeaders("uc", link, "account=must-not-be-used")
        assertEquals("__pugs=guest", headers["Cookie"])
        assertEquals(UCConstants.DOWNLOAD_REFERER, headers["Referer"])
        assertEquals(UCConstants.WEB_ORIGIN, headers["Origin"])
        assertEquals(UCConstants.GUEST_UA, headers["User-Agent"])
    }
}
