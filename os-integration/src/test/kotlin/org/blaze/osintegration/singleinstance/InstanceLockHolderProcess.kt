package org.blaze.osintegration.singleinstance

import org.blaze.download.api.ServicePaths
import java.nio.file.Paths

/**
 * The "another instance is running" role for [InstanceArtifactsTest]: a JVM that takes the
 * single-instance lock for a data directory and holds it until it is killed.
 *
 * It has to be a *separate process* for the same reason as [SecondLauncherProcess]: POSIX record locks
 * are per-process, so a lock taken inside the test JVM would not stop that same JVM from taking it
 * again - the guard under test would pass for the wrong reason.
 */
fun main(args: Array<String>) {
    val lockFile = ServicePaths.instanceLockFileFor(Paths.get(args[0]))
    val lock = InstanceLock(lockFile)
    if (!lock.acquire()) {
        println("FAILED")
        kotlin.system.exitProcess(1)
    }
    println("LOCKED")
    // Park until killed: reading stdin blocks, and the test's destroy() is the release signal.
    System.`in`.read()
    lock.close()
}
