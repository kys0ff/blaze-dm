plugins {
    `java-library`
}

// Vendored fork of `com.github.atomashpolskiy:bt-bencoding:1.10` (Apache-2.0), imported unmodified from the
// 1.10 sources. Coordinates are re-namespaced to org.blaze so the fork cannot clash with the upstream artifact;
// the Java packages stay `bt.bencoding.*` so it remains a drop-in for every existing `import bt.*`.
group = "org.blaze"
version = "1.10-blaze"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    // Upstream runtime deps, unchanged (versions match what the 1.10 POM resolved on the classpath).
    api("com.google.guava:guava:30.1-jre")
    implementation("org.yaml:snakeyaml:1.17")
}
