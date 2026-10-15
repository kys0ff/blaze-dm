package org.island.theme.core

import org.island.theme.api.IslandThemeProvider
import org.slf4j.LoggerFactory
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

/**
 * Loads [IslandThemeProvider] implementations from jar files placed in the themes directory.
 *
 * Every jar is read with its own [URLClassLoader] whose parent is this module's class
 * loader, so the shared [IslandThemeProvider] interface resolves to the same class and
 * [ServiceLoader] can discover providers declared in `META-INF/services`.
 *
 * A jar is only ever opened after [trustFilter] vouches for it: theme plugins run inside the app,
 * so an unapproved jar is skipped (and reported in [Result.untrustedJars]) instead of being
 * class-loaded, keeping the "ask the user first" consent possible *before* any of its code
 * executes. The default filter trusts everything, for hosts without a consent UI.
 */
class ThemePluginLoader {
    private val logger = LoggerFactory.getLogger(ThemePluginLoader::class.java)

    /** Result keeps the class loaders alive so their loaded classes aren't collected. */
    data class Result(
        val themes: List<LoadedTheme>,
        val classLoaders: List<URLClassLoader>,
        /** Jars found on disk that [trustFilter] refused, in scan order — candidates for a prompt. */
        val untrustedJars: List<Path> = emptyList(),
    )

    fun load(themesDir: Path, trustFilter: (Path) -> Boolean = { true }): Result {
        val themes = mutableListOf<LoadedTheme>()
        val loaders = mutableListOf<URLClassLoader>()
        val untrusted = mutableListOf<Path>()

        if (!Files.isDirectory(themesDir)) return Result(themes, loaders)

        val jars = runCatching {
            Files.list(themesDir).use { it.filter { p -> p.toString().endsWith(".jar") }.toList() }
        }.getOrElse {
            logger.error("Failed to list themes directory {}", themesDir, it)
            emptyList()
        }

        for (jar in jars.sortedBy { it.fileName.toString() }) {
            if (!trustFilter(jar)) {
                logger.info("Skipping untrusted theme jar {} (awaiting user consent)", jar)
                untrusted.add(jar)
                continue
            }
            val loader = runCatching {
                URLClassLoader(arrayOf(jar.toUri().toURL()), javaClass.classLoader)
            }.getOrElse {
                logger.error("Failed to open theme jar {}", jar, it)
                continue
            }
            loaders += loader

            // Iterate provider-by-provider so one broken class (a throwing constructor, a
            // missing dependency) doesn't hide the other themes the same jar declares.
            val iterator = ServiceLoader.load(IslandThemeProvider::class.java, loader).iterator()
            while (runCatching { iterator.hasNext() }.getOrElse {
                    logger.error("Failed to scan theme providers in {}", jar, it); false
                }) {
                val provider = runCatching { iterator.next() }.getOrElse {
                    logger.error("Failed to instantiate a theme provider from {}", jar, it); continue
                }
                // Wrap in a sandbox so a runtime bug inside the plugin can't crash the app.
                // The wrapper preserves the configurable facet when the jar declares one.
                themes += LoadedTheme(
                    SafeThemeProvider.wrap(provider, jar),
                    ThemeSource.PLUGIN,
                    jar,
                )
            }
        }

        return Result(themes, loaders, untrusted)
    }
}
