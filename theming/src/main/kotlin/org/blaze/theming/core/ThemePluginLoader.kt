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

            // Iterate provider-by-provider so one broken class (a throwing constructor, a
            // missing dependency) doesn't hide the other themes the same jar declares.
            val iterator = ServiceLoader.load(BlazeThemeProvider::class.java, loader).iterator()
            while (runCatching { iterator.hasNext() }.getOrElse {
                    logger.error("Failed to scan theme providers in {}", jar, it); false
                }) {
                val provider = runCatching { iterator.next() }.getOrElse {
                    logger.error("Failed to instantiate a theme provider from {}", jar, it); continue
                }
                // Wrap in a sandbox so a runtime bug inside the plugin can't crash the app.
                themes += LoadedTheme(SafeThemeProvider(provider, jar), ThemeSource.PLUGIN, jar)
            }
        }

        return Result(themes, loaders)
    }
}
