package com.example.app

import com.example.app.data.AppError
import com.example.app.data.AppLog
import com.example.app.data.ErrorCatalog
import com.example.app.data.HttpStatusException
import com.example.app.data.SupabaseAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.UnknownHostException

/** The error catalog: every code listed once, with text in both languages. */
class ErrorCatalogTest {

    private fun names(file: String): Set<String> =
        Regex("""<string name="([^"]+)"""").findAll(File(file).readText()).map { it.groupValues[1] }.toSet()

    private fun stringId(name: String): Int = R.string::class.java.getField(name).getInt(null)

    @Test
    fun `codes are unique and start at 1`() {
        val codes = ErrorCatalog.all.map { it.code }
        assertEquals(codes.distinct(), codes)
        assertTrue(codes.all { it >= 1 })
        // Every ErrorInfo val is in `all`; a code missing there would never be checked.
        val declared = ErrorCatalog::class.java.declaredFields.count { it.type == com.example.app.data.ErrorInfo::class.java }
        assertEquals(declared, codes.size)
    }

    @Test
    fun `every code has its text in English and Hebrew`() {
        val en = names("src/main/res/values/strings.xml")
        val he = names("src/main/res/values-iw/strings.xml")
        for (info in ErrorCatalog.all) {
            val n = info.code
            val wanted = listOfNotNull("e${n}_title", "e${n}_cause", info.solution?.let { "e${n}_solution" })
            for (name in wanted) {
                assertTrue("$name missing in English", name in en)
                assertTrue("$name missing in Hebrew", name in he)
            }
            assertEquals("E-$n title", stringId("e${n}_title"), info.title)
            assertEquals("E-$n cause", stringId("e${n}_cause"), info.cause)
            info.solution?.let { assertEquals("E-$n solution", stringId("e${n}_solution"), it) }
        }
    }

    @Test
    fun `label shows the HTTP status only when there was one`() {
        assertEquals("E-2 | 503", AppError(ErrorCatalog.SERVER, 503).label)
        assertEquals("E-1", AppError(ErrorCatalog.NO_INTERNET).label)
    }

    @Test
    fun `server answers map to codes`() {
        assertEquals(ErrorCatalog.NO_INTERNET, ErrorCatalog.fromHttp(null).info)
        assertEquals(ErrorCatalog.SIGNED_OUT, ErrorCatalog.fromHttp(401).info)
        assertEquals(ErrorCatalog.TOO_MANY_TRIES, ErrorCatalog.fromHttp(429).info)
        assertEquals(ErrorCatalog.SERVER, ErrorCatalog.fromHttp(502).info)
        assertEquals(ErrorCatalog.THERAPIST_CODE, ErrorCatalog.fromHttp(404, ErrorCatalog.THERAPIST_CODE).info)
        assertEquals(404, ErrorCatalog.fromHttp(404).httpStatus)

        val auth = { code: Int?, msg: String -> ErrorCatalog.fromAuth(SupabaseAuth.AuthResult.Error(msg, code)).info }
        assertEquals(ErrorCatalog.WRONG_CREDENTIALS, auth(400, "Invalid login credentials"))
        assertEquals(ErrorCatalog.EMAIL_NOT_CONFIRMED, auth(400, "Email not confirmed"))
        assertEquals(ErrorCatalog.NO_INTERNET, auth(null, "network error"))

        assertEquals(ErrorCatalog.NO_INTERNET, ErrorCatalog.fromException(UnknownHostException(), ErrorCatalog.UPDATE_FAILED).info)
        assertEquals(ErrorCatalog.SERVER, ErrorCatalog.fromException(HttpStatusException(500), ErrorCatalog.UPDATE_FAILED).info)
        assertEquals(ErrorCatalog.UPDATE_FAILED, ErrorCatalog.fromException(HttpStatusException(404), ErrorCatalog.UPDATE_FAILED).info)
        assertEquals(ErrorCatalog.MODEL_LOAD, ErrorCatalog.fromException(IllegalStateException(), ErrorCatalog.MODEL_LOAD).info)
    }

    @Test
    fun `the log keeps only the newest lines`() {
        repeat(AppLog.MAX_LINES + 20) { AppLog.add("I", "Test", "line $it", null) }
        val lines = AppLog.snapshot().lines()
        assertEquals(AppLog.MAX_LINES, lines.size)
        assertTrue(lines.first().endsWith("line 20"))
        assertTrue(lines.last().endsWith("line ${AppLog.MAX_LINES + 19}"))
    }
}
