package org.blaze

import androidx.compose.ui.window.application
import kotlinx.coroutines.CoroutineScope
import org.blaze.di.appModule
import org.blaze.di.applicationModule
import org.blaze.di.engineModule
import org.blaze.di.loggingModule
import org.blaze.di.osIntegrationModule
import org.blaze.di.platformModule
import org.blaze.di.repositoryModule
import org.blaze.di.resolverModule
import org.blaze.di.screenModelModule
import org.blaze.di.themeModule
import org.blaze.di.useCaseModule
import org.blaze.download.api.DownloadsApi
import org.blaze.download.api.ServicePaths
import org.blaze.i18n.i18nModule
import org.blaze.logging.LogConfigurator
import org.blaze.osintegration.ExternalOpenInbox
import org.blaze.osintegration.ExternalOpenParser
import org.blaze.osintegration.platform.MacOpenEventsBridge
import org.blaze.osintegration.platform.PackagedLayout
import org.blaze.osintegration.platform.WindowsUrlSchemeRegistrar
import org.blaze.osintegration.singleinstance.SingleInstanceCoordinator
import org.blaze.osintegration.singleinstance.SingleInstanceOutcome
import org.blaze.platform.BlazePlatformIdentity
import org.blaze.platform.api.OsType
import org.blaze.platform.api.detectOsType
import org.blaze.platform.autostart.AutoStartController
import org.blaze.platform.autostart.AutoStartCoordinator
import org.blaze.platform.autostart.AutoStartServiceFactory
import org.blaze.platform.cleanup.OsIntegrationCleanup
import org.blaze.platform.di.desktopPlatformModule
import org.blaze.platform.taskbar.LauncherEntryInstaller
import org.blaze.presentation.application.BlazeApplication
import org.blaze.presentation.screens.filepicker.di.filePickerModule
import org.blaze.service.LocalApiLauncher
import org.blaze.service.config.LocalApiSettingsRepository
import org.blaze.tray.di.trayModule
import org.koin.core.context.startKoin
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Desktop entry point: OS arguments / native open events are translated here and *only* here.
 *
 * The order is deliberate:
 *  1. parse argv into validated [org.blaze.osintegration.ExternalOpenRequest]s (never shell-parsed),
 *  2. decide single-instance ownership: a second launcher forwards its requests to the running
 *     instance over the authenticated local channel and exits, so a magnet click never opens
 *     two windows racing over one data directory,
 *  3. register the macOS open-files/open-URI event bridge (warm launches deliver Apple events,
 *     not argv - this must happen before the first AWT window exists),
 *  4. start the app; requests already sit in the inbox, so the window shows them as soon as it can.
 *
 * Requests are never dropped during startup: the inbox buffers everything until the UI consumes it.
 *
 * Two more rules keep Blaze from wearing out a welcome in the user's session:
 *  - `--cleanup` never opens a window at all: it removes the registrations Blaze published at the user
 *    level and exits (see [runCleanup]);
 *  - whatever a run leaves behind is swept both at startup (for the runs that were killed) and at
 *    process end, including a debug session ended by the IDE rather than by the user (see [SessionCleanup]).
 */
fun main(rawArgs: Array<String>) {
    // The cleanup command runs before any of the startup machinery below: it must work when the app it
    // is cleaning up after is itself already gone (no window, no engine, no data directory needed).
    if (ExternalOpenParser.OPTION_CLEANUP in rawArgs) exitProcess(runCleanup(rawArgs.toList()))

    val koin = startKoin {
        modules(
            appModule,
            osIntegrationModule,
            engineModule,
            applicationModule,
            loggingModule,
            desktopPlatformModule(BlazePlatformIdentity),
            platformModule,
            repositoryModule,
            resolverModule,
            themeModule,
            useCaseModule,
            screenModelModule,
            i18nModule,
            filePickerModule,
            trayModule,
        )
    }.koin

    // Apply persisted logging settings before anything else logs, then keep them in sync.
    koin.get<LogConfigurator>().start(koin.get<CoroutineScope>())
    val log = LoggerFactory.getLogger("org.blaze.Main")
    log.info("Blaze is starting up.")

    val parser = koin.get<ExternalOpenParser>()
    val inbox = koin.get<ExternalOpenInbox>()
    val parsed = parser.parse(rawArgs.toList())
    parsed.rejected.forEach { log.warn("Ignoring unsupported launch argument '{}' ({})", it.raw.markSafe(), it.reason) }
    if (parsed.requests.isNotEmpty()) {
        log.info("Received {} external open request(s) from the OS.", parsed.requests.size)
    }

    // Single instance: whoever loses the race forwards and exits; whoever wins has the requests
    // delivered into the very same inbox the GUI will read (start() submits them on the owner path).
    val storageDir = koin.get<Path>()
    var coordinator: SingleInstanceCoordinator? = null
    if (parsed.allowMultipleInstances) {
        log.info("--new-instance: skipping single-instance ownership (dev mode).")
        inbox.submit(parsed.requests)
    } else {
        when (val outcome = SingleInstanceCoordinator.start(storageDir, inbox, parsed.requests)) {
            is SingleInstanceOutcome.Owner -> coordinator = outcome.coordinator
            is SingleInstanceOutcome.Forwarded -> {
                log.info("Another Blaze instance is running; the open request was handed to it.")
                exitProcess(0)
            }
            is SingleInstanceOutcome.Unreachable ->
                log.warn("A running instance did not answer; starting anyway (requests kept locally).")
        }
    }

    // macOS delivers later opens as native events, never as argv; on other platforms this no-ops.
    MacOpenEventsBridge.register(inbox, parser, onReopen = inbox::notifyBringToFront)

    // Windows: jpackage's MSI cannot declare URL protocols, so the packaged app keeps the
    // per-user `magnet:` handler pointed at its own launcher (idempotent, self-healing per launch).
    if (WindowsUrlSchemeRegistrar.isWindowsHost()) {
        PackagedLayout.packagedLauncher()?.let { launcher ->
            if (!WindowsUrlSchemeRegistrar.ensureRegistered("magnet", "Magnet BitTorrent Link", launcher.toString())) {
                log.warn("Could not register Blaze as the magnet: handler for this user.")
            }
        }
    }

    // Sweep up after the runs that never got to clean up after themselves: a debug session stopped by
    // the IDE, a machine where Blaze was uninstalled or moved, a login item pointing at a JDK that has
    // since been updated away. Best-effort, and loud about what it did - never a reason to not open.
    val cleanup = OsIntegrationCleanup(
        identity = BlazePlatformIdentity,
        storageDir = storageDir,
        launcherEntryInstaller = koin.get(),
        autoStartController = koin.get(),
    )
    runCatching { cleanup.pruneStaleRegistrations() }
        .onSuccess { report -> report.notes.forEach { log.info("Startup cleanup: {}", it) } }
        .onFailure { log.warn("Startup cleanup did not finish; Blaze continues.", it) }

    // Reconcile the run-at-startup OS registration with the persisted setting (repairs drift).
    koin.get<AutoStartCoordinator>().sync()

    // Ensure the launcher/icon desktop registration is healthy before the window maps, so
    // the taskbar shows the app icon even on a plain `gradle run` (a stale `.desktop` from
    // an earlier install would otherwise win over the window's own icon and show blank).
    if (detectOsType() == OsType.LINUX) {
        runCatching { koin.get<LauncherEntryInstaller>().ensureInstalled() }
            .onSuccess { removed -> removed.forEach { log.info("Startup cleanup: removed {}", it) } }
    }

    // Best-effort: expose the same running downloads to external clients (CLI, a future browser bridge)
    // over the loopback API. Ownership of the data directory's endpoint is locked, so a second instance
    // simply declines to publish a duplicate. Any failure here must never stop the window from opening.
    val launcher = LocalApiLauncher(storageDir, koin.get<CoroutineScope>())
    runCatching {
        launcher.tryStart(koin.get<DownloadsApi>(), LocalApiSettingsRepository(storageDir).load())
    }.onFailure {
        log.warn("Local download API did not start; the GUI is unaffected.", it)
    }

    // Closing the window returns from `application { }`; a terminated process (IDE Stop, `kill`, login
    // session end) does not - it only runs shutdown hooks. One idempotent sweep for both paths keeps
    // this process's own socket, port and endpoint files from outliving it.
    val session = SessionCleanup(log, storageDir, launcher, coordinator, cleanup)
    Runtime.getRuntime().addShutdownHook(Thread(session, "blaze-session-cleanup"))

    application {
        BlazeApplication()
    }

    session.run()
}

/**
 * `blaze --cleanup`: remove every desktop registration and runtime file Blaze may have published into
 * this user's session.
 *
 * This is the counterpart to what Blaze does on its own at startup: an app-level uninstaller cannot
 * take back a user-level `.desktop`, a hicolor icon, a login item or a per-user `magnet:` key, so
 * either the next run prunes them (it notices a launcher that is gone) or the user asks here. Nothing
 * user-created is touched: settings, logs and downloads in the data directory stay exactly where they
 * are, and a registration Blaze does not own is reported and left alone.
 *
 * Returns the process exit code (0 = the session is clean).
 */
private fun runCleanup(args: List<String>): Int {
    val storageDir = args.zipWithNext()
        .firstOrNull { (flag, _) -> flag == "--storage-dir" || flag == "-d" }
        ?.second
        ?.let { runCatching { Paths.get(it) }.getOrNull() }
        ?: ServicePaths.defaultStorageDir()

    val identity = BlazePlatformIdentity
    val cleanup = OsIntegrationCleanup(
        identity = identity,
        storageDir = storageDir,
        launcherEntryInstaller = LauncherEntryInstaller(identity),
        autoStartController = AutoStartController(AutoStartServiceFactory.create(identity)),
    )
    return runCatching { cleanup.uninstallRegistrations() }.fold(
        onSuccess = { report ->
            println("Blaze desktop-integration cleanup ($storageDir):")
            print(report.describe())
            0
        },
        onFailure = { error ->
            System.err.println("Blaze cleanup failed: ${error.message}")
            1
        },
    )
}

/**
 * The end of a process, however it ends: [api] and [coordinator] are the resources this JVM took, and
 * releasing them is what makes the final [OsIntegrationCleanup.sweepSessionFiles] pass safe - with our
 * locks gone, anything left in the data directory belongs to a process that is no longer running.
 *
 * Idempotent because both paths can fire for the same run (window closed, then the hook on exit).
 */
private class SessionCleanup(
    private val log: Logger,
    private val storageDir: Path,
    private val api: LocalApiLauncher,
    private val coordinator: SingleInstanceCoordinator?,
    private val cleanup: OsIntegrationCleanup,
) : Runnable {
    private val finished = AtomicBoolean(false)

    override fun run() {
        if (!finished.compareAndSet(false, true)) return
        log.info("Blaze is shutting down (data directory: {}).", storageDir)
        runCatching { api.stop() }.onFailure { log.warn("The local download API did not stop cleanly.", it) }
        runCatching { coordinator?.close() }.onFailure { log.warn("Single-instance ownership did not release cleanly.", it) }
        runCatching { cleanup.sweepSessionFiles(includeSecrets = false) }
            .onFailure { log.warn("Session files were not cleaned up.", it) }
    }
}

/** Rejected argv may carry attacker-controlled text; keep logs tidy and injection-proof. */
private fun String.markSafe(): String = take(160).replace('\n', ' ').replace('\r', ' ')
