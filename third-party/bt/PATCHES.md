# `third-party/bt` — fork patches

This file tracks **every** modification made to the vendored `bt` sources. The baseline import is
upstream `com.github.atomashpolskiy:bt` **1.10**, taken from the published `-sources.jar` files and
imported byte-for-byte unmodified (verified by hash comparison against the Gradle-cached jars; see
`NOTICE` for provenance and licensing).

## Fork layout (not a source patch)

- `bt-bencoding` + `bt-core` are vendored as Gradle `java-library` subprojects (`org.blaze:bt-*:1.10-blaze`);
  Java packages stay `bt.*` so the fork is a drop-in replacement.
- `bt-dht` and `bt-http-tracker-client` remain upstream Maven artifacts with the upstream
  `bt-core`/`bt-bencoding` transitively excluded, so only the fork is on the classpath. Rationale in `NOTICE`.
- Build files under `third-party/bt/*/build.gradle.kts` are new blaze-dm scaffolding, not upstream changes.

## Patch log

| # | Commit | Patch | Why | Files touched | Upstreamable? |
|---|--------|-------|-----|---------------|---------------|
| — | *(baseline)* | None yet — tree is unmodified upstream 1.10 | — | — | — |

Append one row per patch commit. Each patch gets its own commit with a message scoped
`fix(third-party/bt): …` / `feat(third-party/bt): …`, and MUST be accompanied by a test in
`download-engine` (or a `third-party/bt` module test) demonstrating the behavior.
