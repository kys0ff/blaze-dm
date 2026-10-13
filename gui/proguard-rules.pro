# =============================================================================
# Blaze — supplementary ProGuard configuration
# =============================================================================
# Consumed by Compose Desktop's built-in `:gui:proguardReleaseJars` task, which
# runs automatically for the `release` build type (Compose enables ProGuard by
# default there, in shrink-only mode — it passes `-dontobfuscate`).
#
# Compose already feeds the application jar *and every dependency jar* in as
# program input, keeps `org.blaze.MainKt.main`, and merges the JetBrains
# baseline rules (Kotlin, Compose, Skiko, coroutines, kotlinx.serialization)
# from `default-compose-desktop-rules.pro`. This file therefore only adds what
# that baseline does not know about for Blaze specifically.
#
# Wired in gui/build.gradle.kts via `compose.desktop.application.proguard {}`.
# =============================================================================

# --- Optional integrations that are simply not on the classpath -------------
# Blaze drags in a large dependency tree (Guice, BitTorrent/bt, Ktor, Logback,
# commons-logging, dbus-java, JNA, SnakeYAML, Guava). Several of these contain
# adapters for Servlet / Jakarta Mail / Log4J / XZ / App Engine / JLine that are
# never present at runtime — the code paths are dead on a desktop app. ProGuard
# treats every unresolved reference as fatal, so we suppress them wholesale.
-ignorewarnings
-dontnote **

# Belt-and-braces entries for the recurring "can't find referenced class" cases,
# so a future dependency bump that drops one of these optional packages does not
# break the release build.
-dontwarn jakarta.servlet.**
-dontwarn javax.servlet.**
-dontwarn jakarta.mail.**
-dontwarn javax.mail.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.log.**
-dontwarn org.apache.avalon.**
-dontwarn org.jline.jansi.**
-dontwarn org.tukaani.xz.**
-dontwarn com.google.appengine.api.**
-dontwarn com.google.apphosting.api.**
-dontwarn com.google.inject.kotlin.**
-dontwarn io.ktor.utils.io.jvm.javaio.**
-dontwarn java.lang.invoke.MethodHandle
-dontwarn java.lang.invoke.VarHandle

# --- ServiceLoader-discovered providers -------------------------------------
# Shrinking must not strip classes that are only reachable through
# META-INF/services, otherwise the corresponding feature silently breaks at
# runtime. Compose's baseline keeps Kotlin/Compose/serialization roots, but not
# these library- or Blaze-specific service contracts.

-keep class * implements java.util.Service { *; }

# SLF4J locates its binding through the org.slf4j.spi.SLF4JServiceProvider
# ServiceLoader. Compose's rules do not keep it, so shrinking deletes
# ch.qos.logback.classic.spi.LogbackServiceProvider while leaving its
# META-INF/services entry behind — SLF4J then falls back to a NOP logger.
-keep interface org.slf4j.spi.SLF4JServiceProvider { *; }
-keep class * implements org.slf4j.spi.SLF4JServiceProvider { *; }
-keep class ch.qos.logback.** { *; }
-keep class org.slf4j.** { *; }

# Ktor client engines are located through this container SPI.
-keep class * implements io.ktor.client.HttpClientEngineContainer { *; }

# BitTorrent (bt-core) assembles itself from Guice modules discovered via SPI.
-keep interface bt.module.BtModuleProvider { *; }
-keep class * implements bt.module.BtModuleProvider { *; }

# Blaze's own link-handler / theme extension points (loaded by PluginLoader and
# ThemePluginLoader through java.util.ServiceLoader).
-keep interface org.blaze.resolver.api.LinkResolver { *; }
-keep interface org.island.theme.api.IslandThemeProvider { *; }
-keep class * implements org.blaze.resolver.api.LinkResolver { *; }
-keep class * implements org.island.theme.api.IslandThemeProvider { *; }

# --- D-Bus desktop integration (taskbar progress / StatusNotifierItem) ------
# dbus-java builds wire member names from reflective Method.getName() and
# Field.getType(), so the annotated interfaces, signal/method classes and their
# members must survive shrinking intact.

-keep @org.freedesktop.dbus.annotations.DBusInterfaceName class * { *; }
-keep interface * {
    @org.freedesktop.dbus.annotations.* <methods>;
}
-keep class * extends org.freedesktop.dbus.messages.DBusSignal { *; }
-keep class * extends org.freedesktop.dbus.Struct { *; }
-keep class org.freedesktop.dbus.** { *; }
-keep class com.github.hypfvieh.** { *; }

# --- JetBrains Runtime API (jbr-api) ----------------------------------------
# Jewel's DecoratedWindow (and Skiko) detect JBR at runtime by reflectively
# loading com.jetbrains.JBR / WindowDecorations. jbr-api is a *multi-release*
# jar whose Java 9+ classes live under META-INF/versions/9; if shrinking drops
# them or the JBR$*__Holder lookup classes, the app wrongly concludes it is not
# running on JBR and throws "DecoratedWindow can only be used on JBR". Keep the
# whole surface intact.
-keep class com.jetbrains.JBR { *; }
-keep class com.jetbrains.JBR$** { *; }
-keep interface com.jetbrains.** { *; }
-keep class com.jetbrains.** { *; }

# --- Native / AWT / Swing bridges -------------------------------------------
# JNA (used by dbus and Jewel) reads Field.getType() reflectively; AWT TrayIcon
# and java.awt.Desktop back Blaze's tray and platform integration.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
