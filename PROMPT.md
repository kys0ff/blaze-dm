# Task: Implement HLS Stream Downloads and Remote Partial Archive Downloads in Blaze Download Manager

You are working on **Blaze Download Manager**, an open-source, cross-platform download manager built with Kotlin and
Compose Multiplatform.

Your task is to extend Blaze with two major capabilities:

1. **HLS stream downloading** — allow users to download accessible media streams through `.m3u8` playlists, including
   playlist parsing, quality selection where supported, segment downloading, and media assembly.
2. **Remote partial archive downloading** — allow users to inspect supported remote ZIP archives, select individual
   files or folders, and download only the entries they need when the archive format and server support this.

These features must integrate properly with Blaze's existing architecture rather than being built as disconnected
subsystems.

Implement the work in phases. First study the current repository and architecture, then create a practical
implementation plan, and proceed through the phases while running appropriate tests and reviewing each set of changes.

Do not assume the architecture, libraries, or supported platforms based on the project's name or technology stack.
Inspect the actual codebase before making decisions.

---

# General Rules

- Study the repository before editing code.
- Follow existing architecture, conventions, naming, dependency choices, and coding style.
- Reuse existing download engines, queues, task models, resolver extensions, persistence, networking, UI components, and
  error handling wherever appropriate.
- Maintain compatibility with currently supported platforms and source sets.
- Keep platform-specific implementations behind the project's established abstractions.
- Avoid JVM-only or platform-specific APIs in shared code unless the project's architecture explicitly supports them
  through an abstraction.
- Prefer maintained libraries that fit the project's multiplatform requirements.
- Introduce dependencies only when justified, and verify their platform compatibility and license.
- Avoid unnecessary rewrites, duplicated functionality, and unrelated refactoring.
- Keep the implementation modular, testable, secure, and maintainable.
- Preserve existing behavior of standard downloads and resolver extensions.
- Do not weaken or remove existing tests merely to make new tests pass.
- Do not claim a feature works on a platform or source type unless it has been verified.
- Do not silently download an entire large archive when the user requested only selected files.
- Do not silently discard unsupported cases. Explain limitations and provide realistic alternatives.
- Treat remote content, playlist metadata, URLs, archives, filenames, and resolver output as untrusted input.
- Do not hardcode unverified links to external documentation, providers, or terms of service.
- Do not stop after producing a plan. After completing the initial repository assessment, proceed with the
  implementation unless there is a genuine blocker.
- At the end of each phase, run the relevant tests, review the changes, correct regressions, and then proceed.
- If a feature conflicts with the existing architecture, document the conflict and choose the smallest maintainable
  solution that fits the project.
- Never report tests, builds, or platform checks as successful unless they were actually executed and passed.
- If a target cannot be tested in the current environment, document that limitation clearly.

# Phase 0 — Study the Repository and Existing Architecture

Before making changes, inspect the repository thoroughly.

## Investigate the following

### Project and platform structure

- Project layout, modules, source sets, and current target platforms.
- Kotlin and Compose Multiplatform versions.
- Existing abstractions for platform-specific functionality.
- Build configuration, dependency management, and build conventions.
- Coding style, formatting tools, and static-analysis configuration.

### Download infrastructure

- Download manager and download-engine architecture.
- Task creation and lifecycle.
- Queue management and concurrency limits.
- Progress reporting and task state transitions.
- Pause, resume, cancellation, retry, and recovery behavior.
- Filename determination and collision handling.
- Persistence models and serialization.
- Existing networking clients and HTTP abstractions.
- Redirect handling, byte-range requests, response headers, cookies, and authentication.
- Temporary-file management and final output validation.

### Resolver and extension infrastructure

- How resolver extensions identify supported URLs.
- How indirect links are resolved.
- The interface used to return resolved resources.
- How headers, authentication, filenames, content types, and expiration are communicated.
- How extensions report errors or unsupported URLs.
- Whether resolvers execute inside a sandbox or have restricted capabilities.
- How backward compatibility with existing extensions is maintained.

### User interface

- Navigation and screen structure.
- Existing download creation flows.
- Dialogs, confirmation prompts, selection lists, progress displays, and settings.
- State management patterns.
- Localization and accessibility conventions.
- Existing error-reporting and notification mechanisms.

### Testing and documentation

- Existing unit, integration, and platform-specific tests.
- Test fixtures and mock-server infrastructure.
- Build and test commands.
- Project documentation and contribution guidelines.
- Dependency and licensing requirements for an open-source project.

## Phase 0 deliverables

Provide a concise internal implementation summary or development note identifying:

- The relevant modules and source sets.
- Existing components that can be reused.
- The best integration points for the two new download handlers.
- Any changes required to shared models or APIs.
- Potential compatibility and security issues.
- Required libraries, if any, and why they are appropriate.
- Testing commands and targets available in the repository.

Then continue to Phase 1.

Do not create a new networking stack, queue, extension framework, or download-management subsystem if an existing
component can be extended safely.

---

# Phase 1 — Resource Resolution and Download Handler Integration

Establish a clean separation between resource discovery and downloading.

Blaze already supports link resolver extensions. Preserve their existing responsibility: resolving indirect URLs,
discovering downloadable resources, and returning the metadata needed for a download.

For example:

- A MediaFire resolver might turn a page URL into a direct file URL.
- A video-site resolver might discover an HLS playlist URL.
- A direct M3U8 URL might be sent to the HLS handler without needing a resolver.
- A resolved ZIP URL might be passed to the remote archive inspector.

The resolver should not need to implement an independent HLS downloader or archive extraction engine.

## Requirements

1. Inspect the current resolved-resource contract before changing it.
2. Reuse the existing contract where possible.
3. If additional metadata is necessary, extend the model compatibly.
4. Add or extend download-handler selection so Blaze can route resources to:
    - The existing HTTP downloader.
    - A new HLS download handler.
    - A remote ZIP archive handler.
5. Preserve compatibility with existing resolver extensions and stored tasks.
6. Avoid selecting a handler based solely on a filename extension.
7. Use available metadata, response content type, playlist structure, and validated resource inspection as appropriate.
8. Handle redirects, signed URLs, authentication requirements, unexpected response content, and expired links.
9. Preserve the headers or authentication context required for legitimate requests.
10. Avoid exposing cookies, credentials, or signed URLs in logs, UI error messages, or persistent storage unnecessarily.

A resource descriptor may need fields such as:

- Resource URL.
- Resource or handler type.
- Required headers or authentication context.
- Suggested filename and media type.
- Expiration information, when known.
- Other necessary metadata supported by the current architecture.

Do not introduce a large generalized resource framework if a small, compatible model extension will solve the problem.

## Expected behavior

- Ordinary direct file URLs continue through the existing downloader.
- Direct HLS playlists are routed to the HLS handler.
- Indirect links continue through supported resolvers.
- Resolver-discovered HLS resources can use the same built-in HLS handler.
- Resolver-discovered ZIP resources can be inspected by the archive handler.
- Failures in one handler do not crash the application or corrupt unrelated tasks.

## Phase validation

Run relevant existing tests and add tests for handler selection, resource metadata, resolver compatibility, and fallback
behavior.

Fix regressions before proceeding.

---

# Phase 2 — Implement HLS Stream Downloading

Implement a built-in HLS download handler integrated with Blaze's existing task and download-management infrastructure.

Use a maintained HLS parser or a well-tested implementation appropriate for the project's supported platforms. Avoid
writing a fragile parser from scratch if a suitable solution already exists.

The core application should own HLS downloading. Resolver extensions may discover stream URLs and provide required
metadata, but they should reuse the shared handler.

## Required functionality

### Playlist discovery and parsing

1. Accept direct `.m3u8` URLs and other reliably identified HLS resources.
2. Correctly distinguish master playlists from media playlists.
3. Resolve relative playlist and segment URLs against the correct base URL.
4. Handle redirects and valid server responses.
5. Validate playlist content and report invalid or unsupported playlists clearly.
6. Avoid treating the M3U8 file itself as the completed video.

### Quality and tracks

1. Parse available variants from master playlists.
2. Let users select a quality or variant when supported.
3. Provide a sensible default when only one variant is available.
4. Support separate audio and video playlists where feasible.
5. Preserve relevant metadata for the selected variant.
6. Report unsupported media arrangements clearly rather than silently producing an incomplete file.

### Segment downloading

1. Download segments through the existing networking infrastructure where practical.
2. Use bounded concurrency and appropriate connection limits.
3. Track progress using reliable information where possible.
4. Integrate retries, cancellation, task states, and queue management.
5. Avoid loading an entire stream into memory.
6. Handle transient segment failures without corrupting the final output.
7. Preserve any necessary headers and authentication context.
8. Handle signed URLs and expired resources gracefully.
9. Avoid assuming every segment is independently downloadable or immediately playable.

### Media assembly

1. Assemble segments into a valid output format.
2. Investigate existing media libraries or FFmpeg integration before introducing new dependencies.
3. Account for the supported container formats, codecs, timestamps, and stream layouts.
4. Validate the completed file where technically feasible.
5. Clean up temporary segment files on failure, cancellation, or successful assembly.
6. Use the existing filename and output-location patterns.
7. Do not claim that concatenating arbitrary segments always produces a playable media file.

### Download lifecycle

Integrate the handler with existing Blaze infrastructure for:

- Task creation.
- Queueing and concurrency.
- Progress updates.
- Cancellation.
- Retry behavior.
- Persistence.
- Recovery after application restart.
- Output naming.
- Completion and error reporting.

Resume interrupted downloads when the necessary playlist, segment URLs, authorization context, and completed segments
remain valid. If safe recovery is impossible, restart the affected operation or ask the user to retry with a clear
explanation.

### Live streams and finite playlists

Investigate VOD playlists and live/event-style playlists separately.

Implement support for finite playlists first unless the current application architecture and implementation scope make
additional live-stream support practical.

Do not claim live-stream recording, automatic recording windows, or seamless live-stream resume unless they are
explicitly implemented and tested.

---

# Phase 3 — DRM Detection, Warning Dialog, and User Choice

Blaze should distinguish supported HLS encryption, DRM-protected media, and uncertain or unsupported protection
mechanisms.

**The presence of DRM indicators should trigger a warning and user choice rather than automatically blocking the
download attempt solely because DRM may be present.**

The user must be able to acknowledge the warning and choose to continue or cancel.

Continuing allows Blaze to proceed with the normal download workflow using the protocols and access methods it supports.
It must not initiate DRM circumvention, defeat access controls, extract protected keys, or bypass a provider's
protection system.

## Detection requirements

1. Detect known DRM indicators when reliable signals are available.
2. Distinguish ordinary HLS encryption from known DRM mechanisms where possible.
3. Do not classify every encrypted HLS playlist as DRM-protected.
4. Distinguish confirmed detection from an uncertain indication.
5. Explain that a stream may be protected when detection is uncertain.
6. Avoid claiming that DRM detection is perfect or that every protected stream can be identified automatically.
7. Treat unsupported codecs, playlist features, inaccessible URLs, and DRM as distinct failure categories when possible.
8. Do not deliberately bypass, defeat, or circumvent DRM or other access controls.

## Required warning dialog

When Blaze detects credible indications of DRM or another content-protection mechanism, show a clear warning before
starting the download attempt.

Use the existing Compose Multiplatform dialog and localization patterns.

**Suggested dialog title:**

Potential DRM Protection Detected

**Suggested dialog message:**

"This stream may use DRM or another content-protection mechanism. Downloading or copying protected content may be
restricted by applicable laws, your authorization, or the content provider's terms.

You are responsible for ensuring that you have permission to download this content and that your use complies with
applicable requirements.

You may continue with a normal download attempt using Blaze's supported methods. Continuing does not guarantee success
and does not bypass DRM or other access controls."

The wording may be adapted to match Blaze's tone and localization conventions, but its meaning must remain clear.

## Required actions

**Continue**

- Record the user's acknowledgment for the current download attempt.
- Proceed with the normal download workflow using supported protocols and access methods.
- Do not interpret acknowledgment as proof of authorization or permission.
- Do not implement or trigger any DRM-circumvention process.
- If the resource turns out to be unsupported or inaccessible, report the actual technical limitation.

**Cancel**

- Close the dialog.
- Do not start the download attempt.
- Return to the preceding screen or task-creation flow without creating a running download.

Do not add a permanent blanket refusal based solely on the initial DRM suspicion. The warning exists to inform the user
and let them decide whether to attempt a normal download.

If the actual download cannot proceed using supported mechanisms, stop the failed operation safely and explain the
limitation.

## Relevant external links

When a relevant, trustworthy provider documentation page, official help page, or applicable terms page can be identified
and verified:

- Include a clickable link in the warning or its details section.
- Prefer the content provider's official documentation or terms relevant to the detected resource.
- Label the destination clearly.
- Do not claim that a generic help page specifically explains the detected stream's DRM.
- Do not invent URLs.
- If no relevant link can be verified, omit the link and advise the user to consult the provider's official
  documentation or terms.

Do not create an automatic web-scraping subsystem solely for finding policy links. Reuse existing project mechanisms if
available; otherwise, use a verified URL when one can be reliably associated with the provider.

## User-interface and testing requirements

- Integrate the dialog into the existing download flow.
- Ensure Continue and Cancel behave correctly.
- Avoid displaying duplicate warning dialogs for the same user action.
- Preserve accessibility and localization support.
- Do not crash if DRM detection is inconclusive.
- Avoid logging sensitive request information.
- Add tests for confirmed indicators, uncertain indicators, dialog actions, continued normal download attempts,
  cancellation, unsupported resources, and error handling.
- Verify that acknowledgment does not activate an undocumented or unsupported download mechanism.

---

# Phase 4 — Implement Remote Partial ZIP Archive Downloads

Add support for inspecting a remote ZIP archive and downloading selected files or folders without downloading the full
archive first, when technically possible.

This is different from downloading an entire ZIP and extracting everything locally.

## Core technical approach

Investigate whether Blaze's networking implementation can perform HTTP byte-range requests and whether the remote server
supports reliable range retrieval.

Standard ZIP files contain a central directory near the end of the archive. It provides information about entries, their
compressed sizes, compression methods, and offsets to their local file headers.

Use this metadata to identify the byte ranges needed for selected entries. Retrieve the required ranges, validate the
returned data, decompress selected entries as needed, and write the results to the chosen destination.

Prefer an appropriate maintained archive library or an established implementation compatible with Blaze's supported
platforms.

Do not rely on an assumed library capability: verify that it can support remote selective extraction and the project's
multiplatform requirements.

## Required functionality

### Archive discovery

1. Accept a URL that resolves to a supported remote ZIP archive.
2. Determine whether reliable HTTP byte-range access is available.
3. Obtain the necessary archive metadata without downloading the full archive where possible.
4. Validate the response and archive structure.
5. Detect malformed or unsupported archives without crashing.
6. Detect when a resource changes between inspection and download.
7. Avoid accidentally interpreting an HTML error page as a ZIP archive.

### Archive browser

Provide a UI that lets users inspect the remote ZIP contents before downloading.

Where metadata permits, display:

- Entry names.
- Folder hierarchy.
- File sizes.
- Whether an entry is a directory.
- Selection checkboxes.
- A select-all or deselect-all action where appropriate.
- Estimated total selected size.
- Any relevant limitations.

Allow users to select a single file, multiple entries, or complete folders.

Reuse existing Compose Multiplatform UI components, navigation patterns, state management, and localization.

### Selective downloading

1. Identify the required ranges for the selected entries.
2. Retrieve only the necessary data when the archive format and server allow it.
3. Validate response headers and byte-range behavior.
4. Handle offsets and sizes using appropriate numeric types for large archives.
5. Decompress selected entries using supported compression methods.
6. Write output to the selected location using safe paths.
7. Integrate the work with Blaze's queue, progress, retry, cancellation, persistence, and error-handling systems.
8. Avoid downloading unrelated entries when selective extraction is possible.
9. Avoid loading the entire archive or large entries into memory.
10. Verify the extracted data where feasible.
11. Clean temporary files on failure and cancellation.
12. Clearly report entries that could not be downloaded.

## Range support and fallback behavior

Selective remote ZIP access depends on the origin server and archive format.

Handle at least the following cases:

- The server supports reliable range requests.
- The server ignores a Range request and returns the full resource.
- The server does not support partial access.
- The server does not provide reliable resource length.
- The server redirects to another resource.
- The resource requires authentication or temporary signed URLs.
- The resource expires during inspection or download.
- The archive changes between inspection and extraction.
- Required ranges fail or return invalid data.

If selective access is unavailable, do not silently download the entire archive.

Instead, display a clear explanation and offer the user the option to download the complete archive and extract selected
files afterward, if that fallback is supported.

Tell the user when the fallback requires downloading much more data than the selected entries. Let the user explicitly
choose whether to proceed with the full download.

If the fallback is unavailable, explain the limitation and stop safely.

## Archive format and feature scope

Focus the initial implementation on standard ZIP files and explicitly document which variants are supported.

Investigate ZIP64, encrypted entries, split archives, compression methods, and other variants according to library
support and implementation cost.

Do not claim support for an archive variant until it has been tested.

Do not assume every ZIP file supports efficient selective downloading. Archive layouts, data descriptors, encryption,
compression methods, and server behavior can affect feasibility.

## Security requirements

Treat all remote archive metadata and entry paths as untrusted.

Implement appropriate defenses against:

- Path traversal.
- Absolute paths and writes outside the chosen destination.
- Malformed offsets and incorrect size values.
- Truncated entries and invalid byte ranges.
- Unsupported or malicious compression structures.
- Decompression bombs and suspicious compression ratios.
- Excessive entry counts and resource consumption.
- Unsafe symbolic links and special entries.
- Unbounded allocations from attacker-controlled size fields.
- Conflicts with existing files.
- Resource changes during extraction.

Use appropriate resource limits, validate paths before writing, and prevent archive entries from writing outside the
destination folder.

Do not log archive passwords, authentication data, or sensitive signed URLs.

---

# Phase 5 — Distinguish ZIP Downloads, ISO Downloads, and ISO Browsing

Do not treat ZIP archives and ISO images as the same format.

These are three distinct capabilities and must be implemented or documented separately.

## A. Remote ZIP entry selection

This is the primary requirement of Phase 4.

Users can inspect a supported remote ZIP and download selected files when range access and archive structure make this
feasible.

## B. Ordinary ISO downloading

An ISO image is a file. If the server supports HTTP byte ranges, the existing HTTP download handler should already be
able to download portions of that file for ordinary resumption.

This does not mean Blaze can select individual files inside the ISO.

Do not introduce a separate ISO-download implementation unless the current architecture has a concrete requirement for
one.

## C. Browsing and selectively retrieving files inside a remote ISO

This requires separate support for the relevant filesystem structures inside ISO images, such as ISO 9660 and
potentially extensions used by other disc images.

Treat this as a separate, optional enhancement.

During architecture assessment, determine whether a maintained compatible library and the current project scope make it
practical. If so, propose an implementation plan separately. Do not allow optional ISO browsing to block the primary ZIP
implementation.

If implemented later, reuse the archive-browser UI and resource-download abstractions where appropriate while keeping
format-specific parsing separate.

Document the limitations clearly. Do not claim selective ISO extraction just because ordinary ISO downloads or ZIP
extraction work.

---

# Phase 6 — Persistence, Recovery, and Reliability

Both new download handlers must cooperate with Blaze's current task lifecycle and persistence system.

Inspect existing behavior before deciding which metadata needs to be stored.

Where appropriate, persist:

- Resource and handler type.
- Necessary non-sensitive resource metadata.
- The selected HLS variant and relevant playlist state.
- Completed segment identifiers or verified byte ranges.
- Archive inspection metadata needed to resume safely.
- Selected archive entry identifiers.
- Output destination and download state.
- Retry and recovery state.

Do not persist credentials or sensitive authorization headers unnecessarily. Reuse the project's secure storage
practices where available.

## Required behavior

- Restore queued tasks after application restart.
- Resume supported operations when the remote resource is still valid.
- Avoid resuming from stale URLs or changed archives.
- Prevent duplicate downloads and conflicting outputs.
- Handle invalid persisted state gracefully.
- Clean up abandoned temporary files when safe.
- Avoid corrupting previously completed files on retry.
- Keep task state consistent with actual data on disk.
- Respect existing connection and concurrency limits.
- Avoid starting the same operation twice due to duplicate events or retries.

Do not claim that saving task state makes a download resumable.

Verify that the remote resource, authorization context, playlist state, archive metadata, and already downloaded
portions remain valid. Where safe recovery is impossible, restart the relevant operation or ask the user to retry, with
an explanation.

Run migration tests if any persisted models or schemas change.

---

# Phase 7 — Automated Testing and Cross-Platform Validation

Testing is mandatory. Neither feature is complete merely because it works for a single URL during manual testing.

Follow the existing repository's test conventions. Add unit, integration, and platform-specific tests appropriate to the
actual implementation.

Use deterministic local fixtures, controlled mock responses, and local HTTP servers where possible. Do not depend on
unpredictable third-party streaming websites or public file hosts for routine automated tests.

## HLS unit and integration tests

Cover the cases supported by the implementation, including:

- Valid media playlists.
- Master playlists containing multiple variants.
- Relative playlist and segment URLs.
- Redirect handling.
- Separate audio/video playlists, where supported.
- Segment ordering and assembly.
- Successful output validation.
- Invalid, empty, truncated, and malformed playlists.
- Unsupported codecs or segment formats.
- Segment download failures and retries.
- Network interruptions.
- Cancellation and temporary-file cleanup.
- Expired URLs.
- Authentication headers and permitted request context.
- Recovery and resume behavior where supported.
- Ordinary HLS encryption where implemented.
- DRM indicators and uncertain detections.
- Warning dialog presentation and its actions.
- Continuing with the normal supported download workflow after acknowledgment.
- Cancelling before the download begins.
- Failure when a resource cannot be accessed or processed.
- Progress and task-state consistency.

Tests must verify that continuing after a DRM warning does not activate unsupported DRM-circumvention behavior.

## ZIP and remote archive tests

Cover the cases supported by the implementation, including:

- Reading a remote ZIP central directory using range requests.
- Entry selection for a single file, several files, and nested folders.
- Correct retrieval and extraction of selected entries.
- Output content verification, including checksums where appropriate.
- Supported compression methods.
- ZIP64 if implemented.
- Encrypted entries and unsupported compression methods.
- Servers that ignore or reject range requests.
- Incorrect Content-Range headers or byte counts.
- Missing or inaccurate Content-Length.
- Redirects, authentication, and expiring URLs.
- Archives changing after inspection.
- Malformed or truncated archive metadata.
- Path traversal and absolute-path entry names.
- Unsafe symbolic links or special entries.
- Suspicious compression ratios and oversized entries.
- Cancellation and retry handling.
- Cleanup after failures.
- Full-archive fallback behavior and user confirmation.
- Ensuring that selective mode does not silently fetch the whole archive.

## Integration and regression tests

- Verify standard direct HTTP downloads still work.
- Verify existing resolver extensions still work.
- Test routing between the HTTP, HLS, and archive handlers.
- Verify progress and task states remain correct.
- Verify persistence and migration behavior.
- Verify UI state updates safely.
- Verify parsing, networking, and decompression do not block the UI thread.
- Verify cancellation cleans up only the appropriate temporary resources.
- Verify no cross-platform API leaks into incompatible shared source sets.
- Add regression tests for every significant bug discovered.

## Build and test process

Before large changes, identify the project's actual build and test commands.

Run relevant existing tests at the start, then rerun affected suites after integration.

As the implementation progresses:

1. Run module-level unit tests.
2. Run integration tests.
3. Run formatting and static-analysis checks used by the project.
4. Compile every practical affected target.
5. Run the broader regression suite.
6. Inspect test results and resolve failures caused by the changes.

Do not bypass failing tests or report unexecuted checks as passing.

If a target cannot be tested because its toolchain or SDK is unavailable, state that limitation explicitly in the final
report.

---

# Phase 8 — Documentation and Open-Source Readiness

Update documentation using the project's existing organization and style.

Document the following.

## HLS downloading

- How to download an HLS resource.
- Supported playlist and media types.
- Quality selection and track limitations.
- Whether VOD and live/event playlists are supported.
- Supported encryption behavior.
- DRM warning and user-choice behavior.
- Unsupported formats and failure cases.
- Dependencies or external tools required.
- Platform-specific limitations.

## Remote ZIP downloading

- How to inspect a remote archive.
- How to select files or folders.
- When selective retrieval works.
- How server byte-range support affects behavior.
- What happens when selective retrieval is unavailable.
- Whether the full-download fallback is available.
- Unsupported archive variants and compression methods.
- Security limitations and resource safeguards.

## Architecture and development

- New handlers and how they integrate with the existing download manager.
- Any changes to resolver or resource contracts.
- New dependencies and their licenses.
- How to run the relevant tests.
- Platform requirements and limitations.
- Any deferred work, including optional ISO browsing.

Do not introduce a mandatory external executable unless it is justified and compatible with the project's packaging
policy.

If FFmpeg is used, follow existing dependency and packaging conventions. Document availability checks, errors, platform
support, and installation expectations when applicable.

Verify that added dependencies are compatible with the project's open-source license and distribution model.

---

# Phase 9 — Final Review and Acceptance Criteria

Before marking the task complete, review the full changeset.

## Architecture

- The implementation fits the existing application architecture.
- Existing download handlers and resolver extensions continue to function.
- There is no unnecessary duplication of queues, networking, or task management.
- Shared code and platform-specific code are separated correctly.
- New APIs and persisted data remain backward compatible, or required migrations are implemented and tested.

## HLS

- Direct HLS URLs are detected and routed correctly.
- Supported playlists can be downloaded and assembled into usable output.
- Progress, cancellation, retries, and supported resume behavior work with existing Blaze infrastructure.
- Unsupported resources fail clearly.
- Suspected DRM triggers a warning and a choice to Continue or Cancel.
- Continue attempts the normal supported download workflow.
- DRM circumvention is not implemented.
- Cancel does not start the download.
- Relevant official links are included only when they have been verified.

## Remote ZIP

- Supported remote ZIP files can be inspected without full download when range access allows it.
- Users can select specific files or folders.
- Selected entries are retrieved and extracted correctly.
- Unsupported range access produces a clear explanation and, where implemented, a user-confirmed full-download fallback.
- Archive path handling, resource limits, and temporary-file cleanup are tested.
- Ordinary ISO downloads are not confused with selective retrieval of files inside an ISO.
- Optional ISO browsing does not undermine completion of the core ZIP feature.

## Quality

- Relevant automated tests pass.
- Formatting and static-analysis checks pass.
- All practical affected targets compile.
- Existing regression tests pass.
- Documentation is updated.
- Known limitations are documented accurately.

# Required Final Report

At completion, provide a concise but specific report containing:

1. **Initial architecture assessment:** the relevant existing modules, abstractions, and integration points discovered.
2. **Implementation by phase:** what was implemented and in which modules.
3. **Important files changed:** include the principal models, handlers, UI components, and tests.
4. **Dependencies:** additions, rationale, license compatibility, and platform constraints.
5. **Tests executed:** actual commands and pass/fail results.
6. **Build verification:** which targets were compiled successfully and which could not be checked.
7. **Feature limitations:** unsupported playlists, archive variants, servers, or platforms.
8. **Persistence and migrations:** any changes and verification performed.
9. **Outstanding issues:** deferred functionality and remaining risks.
10. **Documentation:** what was updated.

Clearly differentiate fully implemented and tested features from partially implemented or unverified behavior.

Do not state that all acceptance criteria are satisfied if any remain incomplete.

**Priority:** deliver a reliable, secure, maintainable first implementation that fits Blaze's existing architecture.
Implement the essential functionality first, test it thoroughly, and only then expand into more complex stream formats,
archive variants, and optional ISO browsing.