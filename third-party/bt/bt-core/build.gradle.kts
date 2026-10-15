plugins {
    `java-library`
}

// Vendored fork of `com.github.atomashpolskiy:bt-core:1.10` (Apache-2.0), imported unmodified from the 1.10
// sources. Re-namespaced to org.blaze (packages stay `bt.*` for a drop-in). Every Round-2 torrent feature
// (fast resume, preallocation, web seeds, adaptive pipeline, file/piece priority) is added here, in separate
// commits, tracked in third-party/bt/PATCHES.md.
group = "org.blaze"
version = "1.10-blaze"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    api(project(":third-party:bt:bt-bencoding"))
    // Upstream runtime deps, unchanged. Guice is on bt-core's public API surface (module/injection types).
    api("com.google.inject:guice:5.0.1")
    implementation("org.slf4j:slf4j-api:2.0.20")
}
