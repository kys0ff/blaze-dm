package org.blaze.resolver.core

import org.blaze.resolver.api.LinkResolver
import org.slf4j.LoggerFactory
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.ServiceLoader
import kotlin.streams.toList

/**
 * Loads [LinkResolver] implementations from jar files placed in the extensions directory.
 *
 * Every jar is read with its own [URLClassLoader] whose parent is this module's class
 * loader, so the shared [LinkResolver] interface resolves to the same class and
 * [ServiceLoader] can discover providers declared in `META-INF/services`.
 */
class PluginLoader {
    private val logger = LoggerFactory.getLogger(PluginLoader::class.java)

    /** Result keeps the class loaders alive so their loaded classes aren't collected. */
    data class Result(
        val resolvers: List<LoadedResolver>,
        val classLoaders: List<URLClassLoader>
    )

    fun load(extensionDir: Path): Result {
        val resolvers = mutableListOf<LoadedResolver>()
        val loaders = mutableListOf<URLClassLoader>()

        if (!Files.isDirectory(extensionDir)) return Result(resolvers, loaders)

        val jars = runCatching {
            Files.list(extensionDir).use { it.filter { p -> p.toString().endsWith(".jar") }.toList() }
        }.getOrElse {
            logger.error("Failed to list extensions directory: ${it.message}")
            emptyList()
        }

        for (jar in jars.sortedBy { it.fileName.toString() }) {
            val loader = runCatching {
                URLClassLoader(arrayOf(jar.toUri().toURL()), javaClass.classLoader)
            }.getOrElse {
                logger.error("Failed to open extension jar $jar: ${it.message}")
                continue
            }
            loaders += loader

            runCatching {
                ServiceLoader.load(LinkResolver::class.java, loader).forEach { resolver ->
                    resolvers += LoadedResolver(resolver, ResolverSource.PLUGIN, jar)
                }
            }.onFailure {
                logger.error("Failed to load resolvers from $jar: ${it.message}")
            }
        }

        return Result(resolvers, loaders)
    }
}
