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
| — | *(baseline)* | Import itself unmodified — upstream 1.10, byte-identical (see `NOTICE`) | — | — | — |
| 1 | this commit | Classify read-loop failures: a cause chain containing `InvalidMessageException` (peer sent an unknown/invalid message type) is logged as a WARN one-liner instead of an ERROR stack trace. Connection handling unchanged — the socket is already shut down by `SocketChannelHandler.read` before the loop sees the exception. | TOR-12: real-swarm runs (2026-10-15, nyaa) flooded logs with `Failed to process key` traces while downloads completed correctly; routine hostile/legacy-peer churn must not read like an internal defect. | `bt/net/ReadLoopFailures.java` (new), `bt/net/DataReceivingLoop.java`, `bt-core/build.gradle.kts` (JUnit 4 test scaffolding), `bt/net/ReadLoopFailuresTest.java` (new) | Yes — small, behavior-preserving; matches upstream's own TODO-level noise complaints. |
| 2 | this commit | Extend the #1 classifier with `CONNECTION_CHURN`: `SocketException` (reset/aborted), `EOFException`, `ClosedChannelException` anywhere in the cause chain → DEBUG one-liner; the loop's separate `EOFException` catch collapses into the same switch. Peer misbehavior keeps priority; other I/O failures stay ERROR+trace (conservative). | TOR-12: `Connection reset` from rate-limited/firewalled peers is normal public-swarm churn, previously ERROR+trace (`DataReceivingLoop.java:130-132` upstream). | `bt/net/ReadLoopFailures.java`, `bt/net/DataReceivingLoop.java`, `bt/net/ReadLoopFailuresTest.java` (+5 churn cases) | Yes — same rationale as #1. |
| 3 | this commit | Dependency bump: guava `30.1-jre` → `33.4.8-jre` in `bt-bencoding` (no source change). | 30.1's `AbstractFuture` calls the terminally-deprecated `sun.misc.Unsafe::objectFieldOffset`, spamming a JDK-24+ WARNING on every startup (observed in a real `:gui:run` torrent download). Measured with a SettableFuture probe on JBR 25: 30.1 reproduces the warning, 33.4.8 prints none. Guice 5.0.1 + all engine/fork tests green on the new version. | `bt-bencoding/build.gradle.kts` | Yes — trivial version bump; upstream already allows newer guava at runtime. |

Append one row per patch commit. Each patch gets its own commit with a message scoped
`fix(third-party/bt): …` / `feat(third-party/bt): …`, and MUST be accompanied by a test in
`download-engine` (or a `third-party/bt` module test) demonstrating the behavior.
