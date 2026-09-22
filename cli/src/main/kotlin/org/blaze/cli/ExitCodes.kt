package org.blaze.cli

/**
 * The CLI's process exit contract - stable enough for shell scripts and CI to branch on.
 *
 * These values are part of the documented CLI surface; changing one is a breaking change for any
 * `blaze ... && ...` pipeline. Scripts are expected to use `--json` for machine-readable results and
 * reserve the exit code for control flow, never for parsing output.
 */
object ExitCodes {
    const val SUCCESS = 0
    const val GENERAL_FAILURE = 1
    const val INVALID_ARGUMENTS = 2
    const val SERVICE_UNAVAILABLE = 3
    const val DOWNLOAD_FAILED = 4
    const val AUTH_FAILURE = 5
}
