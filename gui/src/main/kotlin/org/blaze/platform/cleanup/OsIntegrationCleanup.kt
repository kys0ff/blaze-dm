package org.blaze.platform.cleanup

import org.blaze.download.api.ServicePaths
import org.blaze.osintegration.platform.WindowsUrlSchemeRegistrar
import org.blaze.osintegration.singleinstance.InstanceArtifacts
import org.blaze.platform.api.OsType
import org.blaze.platform.api.PlatformIdentity
import org.blaze.platform.api.detectOsType
import org.blaze.platform.autostart.AutoStartController
import org.blaze.platform.taskbar.LauncherEntryInstaller
import org.blaze.service.instance.ServiceLock
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Blaze's own OS-integration cleanup: the app-specific glue that reaches every registration and
 * leftover file the app can produce in a user's session and removes the ones that no longer describe
 * this machine.
 *
 * Nothing here is a surprise to the user. Each pass only ever deletes what Blaze wrote (XDG user dirs,
 * the per-user registry keys, files inside the data directory), never a system path, never the user's
 * downloads, settings or logs, and never anything a live process is using. The three entry points are
 * deliberately different in ambition:
 *
 * - [pruneStaleRegistrations] runs at startup: drop registrations that cannot work any more (an
 *   uninstalled or moved launcher, a login item written by a debug run) and sweep files a killed JVM
 *   left in the data directory;
 * - [sweepSessionFiles] runs when this process exits (see `Main.kt`), which for a debug session means
 *   SIGTERM, which skips the tail of `main()` entirely;
 * - [uninstallRegistrations] is the explicit `--cleanup` mode: remove *everything* Blaze may have
 *   published, as though the app had never been run.
 */
class OsIntegrationCleanup(
    private val identity: PlatformIdentity,
    private val storageDir: Path,
    private val launcherEntryInstaller: LauncherEntryInstaller,
    private val autoStartController: AutoStartController,
    private val osType: OsType = detectOsType(),
    private val magnetScheme: String = MAGNET_SCHEME,
    private val staleTempFileAge: Long = DEFAULT_STALE_TEMP_AGE_MILLIS,
) {
    /** What a cleanup pass did, so callers can report it instead of guessing. */
    data class Report(val removed: List<Path> = emptyList(), val notes: List<String> = emptyList()) {
        val didAnything: Boolean get() = removed.isNotEmpty() || notes.isNotEmpty()

        fun merge(other: Report): Report = Report(removed + other.removed, notes + other.notes)

        /** A one-line-per-action rendering, used by the `--cleanup` command output. */
        fun describe(): String = buildString {
            removed.forEach { appendLine("removed  $it") }
            notes.forEach { appendLine("note     $it") }
            if (!didAnything) appendLine("nothing to clean up")
        }
    }

    /**
     * Startup self-heal. Removes the desktop registrations this machine can no longer honor and the
     * debris of a previously killed run; a healthy install is left completely untouched.
     *
     * The dead `.desktop` entry itself is pruned by [LauncherEntryInstaller.ensureInstalled], which the
     * launcher calls right after this.
     */
    fun pruneStaleRegistrations(): Report {
        val notes = buildList {
            if (autoStartController.pruneStaleRegistration()) {
                add("dropped a run-at-startup registration that can no longer launch Blaze")
            }
            // A leftover `HKCU\...\Classes\magnet` pointing at a deleted Blaze makes every magnet link
            // in the browser fail instead of offering another player. Only ever ours, only ever dead.
            if (osType == OsType.WINDOWS &&
                WindowsUrlSchemeRegistrar.unregister(magnetScheme, identity.executableName, staleOnly = true)
            ) {
                add("removed the dead per-user $magnetScheme:// registration")
            }
        }
        return sweepSessionFiles(includeSecrets = false).merge(Report(notes = notes))
    }

    /**
     * The full uninstall pass (`blaze --cleanup`): every user-level registration Blaze can publish is
     * removed, then the data directory's runtime files go with it. Settings, logs and downloads stay -
     * removing a program is not permission to delete what the user stored in it.
     */
    fun uninstallRegistrations(): Report {
        val desktop = if (osType == OsType.LINUX) {
            Report(removed = launcherEntryInstaller.uninstall())
        } else {
            Report()
        }
        // Ask first, so the report says what was actually taken back rather than that a command
        // succeeded: an app whose "run at startup" was never turned on has nothing to remove.
        val autostart = when {
            !autoStartController.isRegistered() -> Report()

            autoStartController.applyLive(false) ->
                Report(notes = listOf("removed the run-at-startup registration"))

            else -> Report(notes = listOf("could not remove the run-at-startup registration"))
        }
        val scheme = if (osType == OsType.WINDOWS &&
            !WindowsUrlSchemeRegistrar.unregister(magnetScheme, identity.executableName, staleOnly = false)
        ) {
            Report(notes = listOf("left the $magnetScheme:// handler alone: it is not Blaze's own registration"))
        } else {
            Report()
        }
        return desktop
            .merge(autostart)
            .merge(scheme)
            .merge(sweepSessionFiles(includeSecrets = true))
    }

    /**
     * Removes the files a Blaze process owns inside its data directory: the single-instance channel
     * (socket/port/lock, plus the shared secret when [includeSecrets]) the local API's discovery
     * descriptor, and half-written JSON left behind by a JVM killed mid-save.
     *
     * Both guards are about living processes, not cleanup order: nothing is touched while another
     * instance holds either lock, and an atomic-write `*.json.tmp` has to be old enough that no write
     * can still be in flight.
     */
    fun sweepSessionFiles(includeSecrets: Boolean): Report {
        val instance = InstanceArtifacts.clear(storageDir, includeToken = includeSecrets)
        val api = clearLocalApiFiles(includeSecrets)
        val temp = deleteStalePartialWrites()
        return Report(removed = instance + api + temp)
    }

    /** The discovery descriptor (and, on a full cleanup, token and lock) of the loopback API. */
    private fun clearLocalApiFiles(includeSecrets: Boolean): List<Path> {
        val lockFile = ServicePaths.lockFileFor(storageDir)
        // An absent lock file cannot be held, and ServiceLock creates the file it locks: asking whether
        // a service is running must not leave a file behind in a directory we were only asked to sweep.
        // Held by *anyone* - another process or this JVM's own launcher - means the endpoint is live and
        // off-limits; a probe never releases or claims anything (a same-JVM re-lock is reported as an
        // overlap, not as a win we are free to hand back).
        if (Files.exists(lockFile) && !ServiceLock.isFree(lockFile)) {
            logger.info("Leaving the local-API files in {} alone: a running service owns them.", storageDir)
            return emptyList()
        }
        val candidates = buildList {
            add(ServicePaths.endpointFileFor(storageDir))
            if (includeSecrets) add(ServicePaths.tokenFileFor(storageDir))
        }
        return candidates.filter { path ->
            runCatching { Files.deleteIfExists(path) }
                .onFailure { logger.warn("Could not remove the leftover API file {}", path, it) }
                .getOrDefault(false)
        }
        // The lock file itself stays: it is empty, reused on every start, and its presence is what
        // makes lock-taking race-free (see ServiceLock).
    }

    /**
     * Deletes abandoned atomic-write temporaries (`downloads*.json.tmp` and friends). Every repository
     * in the app writes through a temp file and renames, so anything left here belongs to a JVM that
     * died mid-save; the age floor keeps a concurrent write in another process safe.
     */
    private fun deleteStalePartialWrites(): List<Path> {
        if (!Files.isDirectory(storageDir)) return emptyList()
        val now = System.currentTimeMillis()
        return runCatching {
            Files.list(storageDir).use { entries ->
                entries
                    .filter { it.fileName.toString().endsWith(TEMP_WRITE_SUFFIX) }
                    .filter { isOlderThan(it, now) }
                    .toList()
            }
        }.getOrElse {
            logger.warn("Could not inspect {} for abandoned temporary files", storageDir, it)
            emptyList()
        }.filter { path ->
            runCatching { Files.deleteIfExists(path) }
                .onSuccess { logger.info("Removed the abandoned partial write {}", path) }
                .onFailure { logger.warn("Could not remove the abandoned partial write {}", path, it) }
                .getOrDefault(false)
        }
    }

    private fun isOlderThan(path: Path, nowMillis: Long): Boolean = runCatching {
        val stamp = Files.readAttributes(path, BasicFileAttributes::class.java).lastModifiedTime().toMillis()
        nowMillis - stamp >= staleTempFileAge
    }.getOrDefault(true) // an unreadable stamp means we did not write it recently either

    internal companion object {
        private val logger = LoggerFactory.getLogger(OsIntegrationCleanup::class.java)

        /** The scheme the packaged launcher registers itself for (Windows runtime registration). */
        const val MAGNET_SCHEME = "magnet"

        /** How old a `*.json.tmp` must be to count as debris rather than a write in flight. */
        const val DEFAULT_STALE_TEMP_AGE_MILLIS = 60_000L

        /** The suffix every repository's atomic write uses for its temporary file. */
        const val TEMP_WRITE_SUFFIX = ".json.tmp"
    }
}
