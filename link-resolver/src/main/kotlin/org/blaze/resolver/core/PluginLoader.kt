package org.blaze.resolver.core

import org.blaze.resolver.api.LinkResolver
import org.slf4j.LoggerFactory
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.ServiceLoader

/**
 * Loads [LinkResolver] implementations from jar files placed in the extensions directory.
 *
 * Every jar is read with its own [URLClassLoader] whose parent is this module's class
 * loader, so the shared [LinkResolver] interface resolves to the same class and
 * [ServiceLoader] can discover providers declared in `META-INF/services`.
 *
 * A jar is only ever opened after [trustFilter] vouches for it: plugin code runs with the app's own
 * permissions, so an unapproved jar is skipped (and reported in [Result.untrustedJars]) instead of
 * being class-loaded, keeping the "ask the user first" consent possible *before* any of its code
 * executes. The default filter trusts everything, for hosts without a consent UI.
 */
class PluginLoader {
    private val logger = LoggerFactory.getLogger(PluginLoader::class.java)

    /** Result keeps the class loaders alive so their loaded classes aren't collected. */
    data class Result(
        val resolvers: List<LoadedResolver>,
        val classLoaders: List<URLClassLoader>,
        /** Jars found on disk that [trustFilter] refused, in scan order — candidates for a prompt. */
        val untrustedJars: List<Path> = emptyList(),
    )

    fun load(extensionDir: Path, trustFilter: (Path) -> Boolean = { true }): Result {
        val resolvers = mutableListOf<LoadedResolver>()
        val loaders = mutableListOf<URLClassLoader>()
        val untrusted = mutableListOf<Path>()

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
            val loader = runCatching {
                URLClassLoader(arrayOf(jar.toUri().toURL()), javaClass.classLoader)
            }.getOrElse {
                logger.error("Failed to open extension jar {}", jar, it)
                continue
            }
            loaders += loader

            // Iterate provider-by-provider so one broken class (a throwing constructor, a
            // missing dependency) doesn't hide the other handlers the same jar declares.
            val iterator = ServiceLoader.load(LinkResolver::class.java, loader).iterator()
            while (runCatching { iterator.hasNext() }.getOrElse {
                    logger.error("Failed to scan resolvers in {}", jar, it); false
                }) {
                val resolver = runCatching { iterator.next() }.getOrElse {
                    logger.error("Failed to instantiate a resolver from {}", jar, it); continue
                }
                // Wrap in a sandbox so a runtime bug inside the plugin can't crash the app.
                resolvers += LoadedResolver(SafeLinkResolver(resolver, jar), ResolverSource.PLUGIN, jar)
            }
        }

        return Result(resolvers, loaders, untrusted)
    }
}
