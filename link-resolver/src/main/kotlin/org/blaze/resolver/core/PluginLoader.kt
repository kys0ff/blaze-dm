package org.blaze.resolver.core

import org.blaze.resolver.api.LinkResolver
import org.blaze.resolver.api.ResolverManifest
import org.slf4j.LoggerFactory
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.ServiceLoader
import java.util.zip.ZipFile

/**
 * Loads [LinkResolver] implementations from jar files placed in the extensions directory.
 *
 * The loading pipeline is manifest-first and consent-first:
 *  1. The jar's `META-INF/blaze/resolver.json` is read straight from the zip — *no class
 *     loader is opened and no plugin code runs* — and a jar whose manifest is missing,
 *     malformed, on a schema this build can't read, or requiring a newer Blaze than the
 *     running [appVersion] is skipped with a human-readable reason in [Result.rejectedJars].
 *  2. Only a jar that survives both gates and the host's [trustFilter] is class-loaded.
 *
 * Discovery inside an accepted jar follows the manifest: with `mainClass` set the loader
 * instantiates that single class through its no-arg constructor; otherwise providers are
 * enumerated through [ServiceLoader] (`META-INF/services`) and the *first* one is bound to
 * the jar-level manifest — one manifest means one handler per jar; further providers are
 * logged and skipped.
 *
 * Every jar gets its own [URLClassLoader] parented to this module's loader, so the shared
 * [LinkResolver] interface resolves to the same class across jars.
 */
class PluginLoader(
    /** Blaze's own version, compared against a manifest's `minAppVersion`; blank disables the gate. */
    private val appVersion: String = "",
) {
    private val logger = LoggerFactory.getLogger(PluginLoader::class.java)

    /** A jar that will not be loaded, with the reason the UI / logs can show the author. */
    data class RejectedJar(val path: Path, val reason: String)

    /** Result keeps the class loaders alive so their loaded classes aren't collected. */
    data class Result(
        val resolvers: List<LoadedResolver>,
        val classLoaders: List<URLClassLoader>,
        /** Jars found on disk that [trustFilter] refused, in scan order — candidates for a prompt. */
        val untrustedJars: List<Path> = emptyList(),
        /** Jars that failed the manifest / compatibility gates, with readable reasons. */
        val rejectedJars: List<RejectedJar> = emptyList(),
    )

    /**
     * Reads a jar's manifest without touching its classes — the consent prompt uses this to
     * show the handler's name, author and claimed URL patterns *before* the user agrees to
     * let its code run.
     */
    fun peekManifest(jarPath: Path): ResolverManifest? = ResolverManifest.load(jarPath)

    fun load(extensionDir: Path, trustFilter: (Path) -> Boolean = { true }): Result {
        val resolvers = mutableListOf<LoadedResolver>()
        val loaders = mutableListOf<URLClassLoader>()
        val untrusted = mutableListOf<Path>()
        val rejected = mutableListOf<RejectedJar>()

        if (!Files.isDirectory(extensionDir)) return Result(resolvers, loaders)

        val jars = runCatching {
            Files.list(extensionDir).use { it.filter { p -> p.toString().endsWith(".jar") }.toList() }
        }.getOrElse {
            logger.error("Failed to list extensions directory {}", extensionDir, it)
            emptyList()
        }

        for (jar in jars.sortedBy { it.fileName.toString() }) {
            if (!trustFilter(jar)) {
                logger.info("Skipping untrusted extension jar {} (awaiting user consent)", jar)
                untrusted.add(jar)
                continue
            }

            val manifest = readManifest(jar)
            if (manifest == null) {
                val reason = if (hasManifestEntry(jar)) {
                    "invalid or unsupported ${ResolverManifest.PATH} (schema > v${ResolverManifest.CURRENT_VERSION}, bad id, or malformed JSON)"
                } else {
                    "missing ${ResolverManifest.PATH}; every handler jar must ship a resolver manifest"
                }
                logger.warn("Rejecting extension jar {}: {}", jar, reason)
                rejected += RejectedJar(jar, reason)
                continue
            }

            val minApp = manifest.minAppVersion
            if (minApp != null && appVersion.isNotBlank() &&
                !ResolverManifest.versionAtLeast(appVersion, minApp)
            ) {
                val reason = "requires Blaze >= $minApp (running $appVersion)"
                logger.warn("Rejecting extension jar {}: {}", jar, reason)
                rejected += RejectedJar(jar, reason)
                continue
            }

            val loader = runCatching {
                URLClassLoader(arrayOf(jar.toUri().toURL()), javaClass.classLoader)
            }.getOrElse {
                logger.error("Failed to open extension jar {}", jar, it)
                rejected += RejectedJar(jar, "jar could not be opened as an archive")
                continue
            }
            loaders += loader

            val instance = instantiate(manifest, loader, jar)
            if (instance == null) {
                // [instantiate] already logged the specifics; don't register a handler-less jar.
                continue
            }
            // Wrap in a sandbox so a runtime bug inside the plugin can't crash the app.
            resolvers += LoadedResolver(
                id = manifest.id,
                manifest = manifest,
                resolver = SafeLinkResolver(instance, jar),
                source = ResolverSource.PLUGIN,
                pluginPath = jar,
            )
        }

        return Result(resolvers, loaders, untrusted, rejected)
    }

    /** Parses the jar's manifest file; null when absent, malformed or on an unknown schema. */
    private fun readManifest(jar: Path): ResolverManifest? = ResolverManifest.load(jar)

    private fun hasManifestEntry(jar: Path): Boolean = runCatching {
        ZipFile(jar.toFile()).use { it.getEntry(ResolverManifest.PATH) != null }
    }.getOrDefault(false)

    /**
     * Produce the jar's single handler instance per the manifest's discovery mode, or null
     * (with a log) when the declared class is missing, doesn't implement the SPI, or the
     * jar provides no service providers.
     */
    private fun instantiate(manifest: ResolverManifest, loader: URLClassLoader, jar: Path): LinkResolver? {
        manifest.mainClass?.let { className ->
            return runCatching {
                val type = Class.forName(className, false, loader)
                require(LinkResolver::class.java.isAssignableFrom(type)) {
                    "$className does not implement ${LinkResolver::class.java.name}"
                }
                type.getDeclaredConstructor().newInstance() as LinkResolver
            }.getOrElse {
                logger.error("Extension jar {} declares mainClass '{}' which cannot be instantiated", jar, className, it)
                return null
            }
        }

        // ServiceLoader path: iterate provider-by-provider so a throwing constructor is
        // logged with the jar's name rather than poisoning discovery.
        val iterator = ServiceLoader.load(LinkResolver::class.java, loader).iterator()
        while (runCatching { iterator.hasNext() }.getOrElse {
                logger.error("Failed to scan resolvers in {}", jar, it); false
            }) {
            val resolver = runCatching { iterator.next() }.getOrElse {
                logger.error("Failed to instantiate a resolver from {}", jar, it); continue
            }
            val extra = runCatching { iterator.hasNext() }.getOrDefault(false)
            if (extra) {
                logger.warn(
                    "Extension jar {} declares more than one LinkResolver provider but only one manifest; " +
                            "keeping {} and skipping the rest", jar, resolver.javaClass.name,
                )
            }
            return resolver
        }
        logger.warn("Extension jar {} has no mainClass and provides no LinkResolver service", jar)
        return null
    }
}
