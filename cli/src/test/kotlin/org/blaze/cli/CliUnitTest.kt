package org.blaze.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The CLI's argument-parsing and exit-code contract, exercised without any running service.
 *
 * Exit codes are a documented scripting surface (they gate CI pipelines), so they are pinned here
 * independently of the download backend: missing command / bad args -> 2, help -> 0, and an unreachable
 * service -> 3. [Cli.run] is a pure function over injectable streams, which is what makes this cheap.
 */
class CliUnitTest {

    private class Captured(val code: Int, val out: String, val err: String)

    private fun run(vararg args: String): Captured {
        val o = ByteArrayOutputStream()
        val e = ByteArrayOutputStream()
        val code = Cli().run(args, PrintStream(o, true), PrintStream(e, true))
        return Captured(code, o.toString(), e.toString())
    }

    private fun emptyDir(): String =
        File(System.getProperty("java.io.tmpdir"), "blaze-cli-" + System.nanoTime()).apply { mkdirs() }.absolutePath

    @Test
    fun `no command is a usage error`() {
        assertEquals(ExitCodes.INVALID_ARGUMENTS, run().code)
    }

    @Test
    fun `help exits zero and prints the command list`() {
        val r = run("help")
        assertEquals(ExitCodes.SUCCESS, r.code)
        assertTrue("download" in r.out && "exit codes" in r.out.lowercase())
    }

    @Test
    fun `a command against a non-running service reports service unavailable`() {
        val r = run("list", "--storage-dir", emptyDir())
        assertEquals(ExitCodes.SERVICE_UNAVAILABLE, r.code)
    }

    @Test
    fun `quiet suppresses the human error line but keeps the exit code`() {
        val loud = run("list", "--storage-dir", emptyDir())
        val quiet = run("list", "--storage-dir", emptyDir(), "--quiet")
        assertEquals(loud.code, quiet.code)
        assertTrue(loud.err.isNotBlank())
        assertEquals("", quiet.err)
    }
}
