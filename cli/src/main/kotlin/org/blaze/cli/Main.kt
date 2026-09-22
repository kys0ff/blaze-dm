package org.blaze.cli

import kotlin.system.exitProcess

/**
 * Entry point for the `blaze` command-line client.
 *
 * All logic lives in [Cli] (which is stream-injectable and unit-tested); this is just the process seam that
 * turns a parsed command into an exit code.
 */
fun main(args: Array<String>) {
    val code = Cli().run(args, System.out, System.err)
    exitProcess(code)
}
