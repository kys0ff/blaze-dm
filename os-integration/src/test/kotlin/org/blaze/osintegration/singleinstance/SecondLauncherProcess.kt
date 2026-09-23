package org.blaze.osintegration.singleinstance

import org.blaze.osintegration.ExternalOpenInbox
import org.blaze.osintegration.ExternalOpenParser
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * The "second launcher" role for [SingleInstanceForwardingTest], run as a *separate JVM process*.
 *
 * This is not test theater: POSIX record locks (and therefore [InstanceLock]) are per-process -
 * a second acquire inside the same JVM trivially succeeds, so only a real fork of the process can
 * prove the "browser launched a second instance, it forwards and exits" contract.
 *
 * Contract with the test: prints `OUTCOME=<Owner|Forwarded|Unreachable>` and, for
 * [SingleInstanceOutcome.Unreachable], `KEPT=<n>` (requests retained locally). Exit code 0 for
 * Forwarded/Owner (the launcher's job is done or its GUI would open), 3 for Unreachable.
 */
fun main(args: Array<String>) {
    val storageDir = Paths.get(args[0])
    val parser = ExternalOpenParser()
    val inbox = ExternalOpenInbox()
    val parsed = parser.parse(args.drop(1))
    val requests = parsed.requests

    return when (val outcome = SingleInstanceCoordinator.start(storageDir, inbox, requests)) {
        is SingleInstanceOutcome.Owner -> {
            println("OUTCOME=Owner")
            // A real owner would now open its GUI; the test only needs the decision + release.
            outcome.coordinator.close()
            exitProcess(0)
        }
        is SingleInstanceOutcome.Forwarded -> {
            println("OUTCOME=Forwarded")
            exitProcess(0)
        }
        is SingleInstanceOutcome.Unreachable -> {
            println("OUTCOME=Unreachable")
            println("KEPT=${inbox.pending.value.size}")
            exitProcess(3)
        }
    }
}
