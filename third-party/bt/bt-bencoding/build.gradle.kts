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
    // Upstream runtime deps. Guava bumped from upstream's 30.1-jre (fork patch #3, TOR-12 housekeeping):
    // 30.1's AbstractFuture calls the now terminally-deprecated sun.misc.Unsafe.objectFieldOffset, which spams
    // a JDK 24+ startup WARNING; measured on JBR 25 with a SettableFuture probe, 33.4.8 never selects that helper
    // and prints no warning. Everything else stays as the 1.10 POM resolved.
    api("com.google.guava:guava:33.4.8-jre")
    implementation("org.yaml:snakeyaml:1.17")
}
