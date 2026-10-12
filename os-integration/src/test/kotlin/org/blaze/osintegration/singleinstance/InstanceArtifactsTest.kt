package org.blaze.osintegration.singleinstance

import org.blaze.download.api.ServicePaths
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the end-of-session and uninstall sweeps may and may not delete inside the data directory.
 *
 * The stakes are asymmetric: leaving a stale `instance.sock` behind is untidy, deleting a *running*
 * instance's socket blinds every launcher that comes after it. The ownership guard is therefore tested
 * against a genuinely separate JVM ([InstanceLockHolderProcess]) rather than a same-process mock, which
 * POSIX record locks would let pass for the wrong reason.
 */
class InstanceArtifactsTest {

    private fun tempStorageDir(): Path = Files.createTempDirectory("blaze-artifacts-test")

    /** Fills the data directory the way a crashed run leaves it: channel files plus real user data. */
    private fun populatedDir(): Path {
        val dir = tempStorageDir()
        Files.writeString(ServicePaths.instanceLockFileFor(dir), "")
        Files.writeString(ServicePaths.instanceSocketFileFor(dir), "not-a-socket")
        Files.writeString(ServicePaths.instanceTcpFileFor(dir), "41234")
        Files.writeString(ServicePaths.instanceTokenFileFor(dir), "0123456789abcdef")
        Files.writeString(dir.resolve("downloads.json"), "[]")
        Files.writeString(dir.resolve("settings.json"), "{}")
        Files.createDirectories(dir.resolve("logs")).also {
            Files.writeString(it.resolve("blaze.log"), "hello")
        }
        return dir
    }

    @Test
    fun `the owned set is exactly the IPC channel plus its secret`() {
        val dir = Paths.get("/data")

        assertEquals(
            listOf(
                dir.resolve("instance.lock"),
                dir.resolve("instance.sock"),
                dir.resolve("instance-tcp"),
            ),
            InstanceArtifacts.channelFiles(dir),
        )
        assertEquals(
            InstanceArtifacts.channelFiles(dir) + listOf(dir.resolve("instance-token")),
            InstanceArtifacts.allFiles(dir),
            "the token is the extra thing only an uninstall drops - whole, not as path components",
        )
    }

    @Test
    fun `an ordinary sweep removes the channel and keeps the secret and the user data`() {
        val dir = populatedDir()

        val removed = InstanceArtifacts.clear(dir, includeToken = false)

        assertEquals(
            setOf(
                ServicePaths.instanceLockFileFor(dir),
                ServicePaths.instanceSocketFileFor(dir),
                ServicePaths.instanceTcpFileFor(dir),
            ),
            removed.toSet(),
        )
        removed.forEach { assertFalse(Files.exists(it), "$it should be gone") }
        assertTrue(Files.exists(ServicePaths.instanceTokenFileFor(dir)), "the secret survives a session end")
        assertTrue(Files.exists(dir.resolve("downloads.json")), "user data is never in scope")
        assertTrue(Files.exists(dir.resolve("settings.json")))
        assertTrue(Files.exists(dir.resolve("logs").resolve("blaze.log")))
    }

    @Test
    fun `an uninstall sweep also drops the shared secret`() {
        val dir = populatedDir()

        val removed = InstanceArtifacts.clear(dir, includeToken = true)

        assertTrue(ServicePaths.instanceTokenFileFor(dir) in removed, "the secret belongs to the channel: $removed")
        assertFalse(Files.exists(ServicePaths.instanceTokenFileFor(dir)))
        assertTrue(Files.exists(dir.resolve("downloads.json")), "still never the user's data")
    }

    @Test
    fun `sweeping a directory that was never used costs nothing`() {
        val fresh = tempStorageDir()

        assertFalse(InstanceArtifacts.isOwned(fresh), "a lock file that was never created holds nobody")
        val removed = InstanceArtifacts.clear(fresh, includeToken = true)

        // Asking must not create: the probe used to open the lock file it was only meant to inspect, so
        // a second cleanup of an already-clean install reported deleting a zero-byte file it had just
        // invented. Nothing of the user's is in scope either way.
        assertEquals(emptyList(), removed, "an unused directory has nothing to sweep: $removed")
        assertEquals(0L, Files.list(fresh).use { it.count() }, "and the sweep leaves it empty")
    }

    @Test
    fun `a lock this very JVM holds is owned, not free - and never swept`() {
        val dir = populatedDir()
        val holder = InstanceLock(ServicePaths.instanceLockFileFor(dir))
        assertTrue(holder.acquire(), "this process plays the running instance")
        try {
            // The JDK reports a same-JVM re-lock as an *overlapping* lock, never as a silent win. A
            // probe that cannot see that reads "our own coordinator owns this" as failure: every real
            // Blaze startup logged a phantom single-instance error (and nearly deleted its own live
            // socket) through exactly this path. Ownership must come back as owned, quietly.
            assertTrue(InstanceArtifacts.isOwned(dir), "held by us is still held")
            assertEquals(emptyList(), InstanceArtifacts.clear(dir, includeToken = true), "our own channel is not debris")
            assertTrue(Files.exists(ServicePaths.instanceSocketFileFor(dir)), "the live owner's socket survives the sweep")
        } finally {
            holder.close()
        }

        assertFalse(InstanceArtifacts.isOwned(dir), "the sweep may work once the owner is gone")
        assertTrue(ServicePaths.instanceSocketFileFor(dir) in InstanceArtifacts.clear(dir, includeToken = true))
    }

    @Test
    fun `the channel of a running instance is never deleted`() {
        val dir = populatedDir()
        val java = Path.of(System.getProperty("java.home"), "bin", "java")
        val holder = ProcessBuilder(
            java.toString(),
            "-cp", System.getProperty("java.class.path"),
            "org.blaze.osintegration.singleinstance.InstanceLockHolderProcessKt",
            dir.toString(),
        ).redirectErrorStream(true).start()
        try {
            awaitOwned(dir, deadlineMs = 30_000)
            val socket = ServicePaths.instanceSocketFileFor(dir)
            val token = ServicePaths.instanceTokenFileFor(dir)

            assertTrue(InstanceArtifacts.isOwned(dir), "the forked JVM holds the directory")
            assertEquals(emptyList(), InstanceArtifacts.clear(dir, includeToken = true), "the sweep must refuse outright")
            assertTrue(Files.exists(socket), "a live owner's socket is not ours to delete")
            assertTrue(Files.exists(token))
        } finally {
            holder.destroy()
            holder.waitFor(10, TimeUnit.SECONDS)
        }

        assertFalse(InstanceArtifacts.isOwned(dir), "the lock died with the process")
        val removed = InstanceArtifacts.clear(dir, includeToken = true)
        assertTrue(ServicePaths.instanceSocketFileFor(dir) in removed, "the same files go once nobody owns them")
    }

    private fun awaitOwned(dir: Path, deadlineMs: Long) {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline) {
            if (InstanceArtifacts.isOwned(dir)) return
            Thread.sleep(50)
        }
        throw AssertionError("the holder process never took the lock at $dir")
    }
}
