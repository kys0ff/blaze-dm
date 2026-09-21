package org.blaze.theming.core

import org.blaze.theming.api.BlazeThemeProvider
import org.slf4j.LoggerFactory
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.ServiceLoader

/**
 * Loads [BlazeThemeProvider] implementations from jar files placed in the themes directory.
 *
 * Every jar is read with its own [URLClassLoader] whose parent is this module's class
 * loader, so the shared [BlazeThemeProvider] interface resolves to the same class and
 * [ServiceLoader] can discover providers declared in `META-INF/services`.
 */
class ThemePluginLoader {
    private val logger = LoggerFactory.getLogger(ThemePluginLoader::class.java)

    /** Result keeps the class loaders alive so their loaded classes aren't collected. */
    data class Result(
        val themes: List<LoadedTheme>,
        val classLoaders: List<URLClassLoader>
    )

    fun load(themesDir: Path): Result {
        val themes = mutableListOf<LoadedTheme>()
        val loaders = mutableListOf<URLClassLoader>()

        if (!Files.isDirectory(themesDir)) return Result(themes, loaders)

        val jars = runCatching {
            Files.list(themesDir).use { it.filter { p -> p.toString().endsWith(".jar") }.toList() }
        }.getOrElse {
            logger.error("Failed to list themes directory {}", themesDir, it)
            emptyList()
        }

        for (jar in jars.sortedBy { it.fileName.toString() }) {
            val loader = runCatching {
                URLClassLoader(arrayOf(jar.toUri().toURL()), javaClass.classLoader)
            }.getOrElse {
                logger.error("Failed to open theme jar {}", jar, it)
                continue
            }
            loaders += loader

            runCatching {
                ServiceLoader.load(BlazeThemeProvider::class.java, loader).forEach { provider ->
                    themes += LoadedTheme(provider, ThemeSource.PLUGIN, jar)
                }
            }.onFailure {
                logger.error("Failed to load themes from {}", jar, it)
            }
        }

        return Result(themes, loaders)
    }
}
