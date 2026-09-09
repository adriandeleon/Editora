# Java LSP evaluation — 8 September 2026
comc
Evaluated commit: `f74b15d370eeb444cf26a110a79f5da2189c9f52` (`origin/master` when the evaluation worktree was created).

## Assessment

Editora has a capable Java LSP foundation and substantial automated coverage. Standard completion, diagnostics, navigation, formatting, symbols, semantic tokens, and hierarchies worked against JDT LS on this machine. **Five actionable correctness defects were identified**, including two high-priority problems: custom Java responses are lost at the transport boundary, and stale workspace edits can overwrite unrelated text.

The follow-up implementation resolves all five confirmed findings and the code-observed hardening risks
identified by the audit: startup preparation and workspace-edit I/O leave the JavaFX thread, ordinary requests
are bounded and canceled, abandoned initialization requests complete, version/session/generation guards reject
stale results, dynamic registrations and refresh requests are honored, and create/delete resource operations
join rename in the transactional workspace-edit path. The deterministic reproductions now live as passing
regression tests in the normal test tree; the original evidence and logs remain with this report.

A subsequent real-session crash report exposed a damaged persisted Eclipse resource tree for this repository:
JDT LS logged `ObjectNotFoundException` while restoring a removed Maven compiler-status file, then three
Editora sessions timed out during `initialize`. The corrupt cache was quarantined. The implementation now
self-heals this case and scopes crash recovery to the failed project root.

## Evidence and scope

- Host: macOS, Temurin JDK 25.0.3; installed Homebrew JDT LS 1.60.0; Editora uses LSP4J 1.0.0 and JavaFX 26.0.2.
- Reviewed session startup, runtime discovery, workspace routing, capability negotiation, document synchronization, completion/imports, diagnostics, navigation, formatting, workspace edits, Java extensions, shutdown/restart, and relevant editor/controller wiring.
- Ran the focused LSP/UI/editor suite: **425 tests, 0 failures, 0 errors, 5 skipped** (420 passed).
- Ran baseline `mvn -B verify`: **4,510 tests, 0 failures, 0 errors, 17 skipped** (4,493 passed), coverage gate and formatting check passed. Total Maven time: 3 minutes 53 seconds. Baseline verification finished before adding the audit-only failing tests.
- Added four deterministic regression checks against current behavior: **all four failed on the expected assertions**, confirming the transport, stale edit, partial refactoring, and startup synchronization defects below.
- Drove the production `LanguageServerSession` against real JDT LS using a temporary Maven Java 25 fixture. Also imported the evaluation worktree itself and queried `JavaRuntimes`, `LanguageServerSession`, `LspCoordinator`, and workspace symbols.
- A full JDT LS workspace build completed with **0 errors, 455 warnings, and 108 informational diagnostics** in the latest published reports (172 URIs). The 455 warnings were independently extracted from the captured output; all are available in `WARNINGS.md` and `diagnostics.json`.

Post-fix verification ran **4,531 current tests with 0 failures or errors and 19 skipped**; coverage and
Spotless checks passed, with no Surefire reruns needed. The production session was then driven against JDT LS 1.60 again: library source
returned 65,972 characters, organize imports returned an edit, and all four check → generate flows returned
nonempty workspace edits without unsupported JDT notification warnings.

This does not certify Windows/Linux launch behavior, runtime PATH/JDK discovery from a distributed installer, remote workspaces, debugger launch, accessibility, or actual keyboard/mouse operation of every popup. A macOS app image was built successfully and a live two-module Gradle workspace was imported and navigated. UI checks here used the repository's headless JavaFX harness; the live feature probe exercised the real session/transport, not a manually operated app window. The Java debug bridge was reviewed at its LSP boundary; a DAP evaluation is a separate scope.

## Confirmed findings

### 1. P1 — Custom Java request responses are discarded

**Resolution:** Fixed by registering every used JDT request on `JdtLanguageServer`; a production-registry
serialization test verifies nonempty payloads survive LSP4J dispatch.

**Location:** [LanguageServerSession.java:235](/Users/adriandeleon/src/adl/Editora-V2-worktrees/codex-java-lsp-evaluation/src/main/java/com/editora/lsp/LanguageServerSession.java:235), [rawRequest:851](/Users/adriandeleon/src/adl/Editora-V2-worktrees/codex-java-lsp-evaluation/src/main/java/com/editora/lsp/LanguageServerSession.java:851).

The launcher only registers the standard `LanguageServer` interface. Calls such as `java/classFileContents`, `java/checkToStringStatus`, and `java/organizeImports` go directly through the raw endpoint, without a registered response type. LSP4J 1.0.0 parses the unknown response into JSON and then converts it to `null` during its second conversion pass because its method return type is unknown.

**Reproduction:** a valid response containing `{"fields":[{"name":"count"}]}` parses to `null` with the production method registry. Registering that same method with a `JsonElement` return type preserves the payload. The live session returned a valid `jdt://` library definition but `null` library contents; generation candidate requests also came back empty through the same path.

**Impact:** library source viewing, generation pickers, direct organize imports, and other custom requests that need a returned value cannot work reliably through this transport. Standard `workspace/executeCommand` is registered and is not affected by this specific defect. Custom notifications also do not need a response type.

**Fix direction:** register typed Java extension methods on the launcher, preferably with their actual DTO/`WorkspaceEdit` return types, or explicitly register supported raw response types. Test a serialized round trip rather than a fake raw sink.

**Acceptance:** real library contents are nonempty; a field produces generator candidates; direct organize imports applies the returned edits. Preserve nonempty, null, and error responses distinctly.

Source confirmation: [LSP4J 1.0.0 MessageTypeAdapter](https://github.com/eclipse-lsp4j/lsp4j/blob/v1.0.0/org.eclipse.lsp4j.jsonrpc/src/main/java/org/eclipse/lsp4j/jsonrpc/json/adapters/MessageTypeAdapter.java), particularly the second `parseResult` and `fromJson(JsonElement, Type)` paths.

### 2. P1 — Workspace edits lose document versions and can corrupt changed text

**Resolution:** Fixed by retaining document versions and request-time text for every target, then rejecting
the complete edit before mutation when any target has changed.

**Location:** [WorkspaceEditMapper.java:64](/Users/adriandeleon/src/adl/Editora-V2-worktrees/codex-java-lsp-evaluation/src/main/java/com/editora/lsp/WorkspaceEditMapper.java:64), [LspCoordinator.java:2298](/Users/adriandeleon/src/adl/Editora-V2-worktrees/codex-java-lsp-evaluation/src/main/java/com/editora/ui/LspCoordinator.java:2298).

`TextDocumentEdit.textDocument.version` is discarded when mapping to the neutral `FileEdit`. The application checks editability but cannot reject a reply computed for an earlier document version. Rename, quick-fix, and generation application also lack a shared snapshot check covering every affected file.

**Reproduction:** request a version-1 edit changing `A` to `B` in `class A {}`. Insert `// note` above it and sync version 2 before applying the old edit. The current implementation reports success and changes the comment to `// notB`; the class remains `A`.

**Impact:** a delayed rename/quick fix or a refactoring preview left open while documents change can edit unrelated text. An in-bounds stale range is especially dangerous because offset validation cannot detect it. Full-document formatting already has a snapshot guard, but workspace refactoring does not share that protection.

**Fix direction:** retain protocol versions, compare them to the corresponding synchronized document state, and snapshot affected buffers for unversioned edits and interactive previews. Reject the whole stale edit before mutation. Account for unsent local changes as well as server versions.

**Acceptance:** delayed edits after typing, undo, tab closure/reopen, and modifications to a second affected file must be rejected without changing any buffer.

Protocol reference: [LSP workspace edits and versioned document edits](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocumentEdit).

### 3. P2 — Generator requests use the wrong JSON parameter shape

**Resolution:** Fixed with the four JDT DTO object shapes, including constructor choices and
`regenerate=false` for hashCode/equals.

**Location:** [JdtlsGenerate.java:171](/Users/adriandeleon/src/adl/Editora-V2-worktrees/codex-java-lsp-evaluation/src/main/java/com/editora/lsp/JdtlsGenerate.java:171), [LspManager.java:2587](/Users/adriandeleon/src/adl/Editora-V2-worktrees/codex-java-lsp-evaluation/src/main/java/com/editora/lsp/LspManager.java:2587).

`generateParams` builds positional arrays. JDT LS 1.60 expects one object: toString uses `context` and `fields`; hashCode/equals also uses `regenerate`; constructors use `context`, `constructors`, and `fields`; override methods use `context` and `overridableMethods`.

All four direct generation requests timed out in the live probe. The live server rejected Editora's generated array with `Expected END_ARRAY but was BEGIN_ARRAY … $.params[1]`. The request then waited until the probe timeout. The production manager uses a 30-second timeout. This is independent of finding 1: after preserving candidate responses, generation would still fail at the request boundary.

**Fix direction:** emit the server's request objects and preserve constructor/method selections. Use protocol-aware decoding for returned workspace edits. Recheck the existing tests, which currently assert the incorrect positional shape.

**Acceptance:** execute each of the four check → select → generate → apply flows against the pinned server, verify nonempty edits, and compile the generated source.

Primary contracts: [toString](https://github.com/eclipse-jdtls/eclipse.jdt.ls/blob/v1.60.0/org.eclipse.jdt.ls.core/src/org/eclipse/jdt/ls/core/internal/handlers/GenerateToStringHandler.java), [hashCode/equals](https://github.com/eclipse-jdtls/eclipse.jdt.ls/blob/v1.60.0/org.eclipse.jdt.ls.core/src/org/eclipse/jdt/ls/core/internal/handlers/HashCodeEqualsHandler.java), [constructors](https://github.com/eclipse-jdtls/eclipse.jdt.ls/blob/v1.60.0/org.eclipse.jdt.ls.core/src/org/eclipse/jdt/ls/core/internal/handlers/GenerateConstructorsHandler.java), [override methods](https://github.com/eclipse-jdtls/eclipse.jdt.ls/blob/v1.60.0/org.eclipse.jdt.ls.core/src/org/eclipse/jdt/ls/core/internal/handlers/OverrideMethodsHandler.java).

### 4. P2 — Reopening a document during initialization desynchronizes the shadow

**Resolution:** Fixed by updating shadows inside queued sends and completing the initialization queue flush
under the same ordering lock used by newly arriving notifications.

**Location:** [LanguageServerSession.java:682](/Users/adriandeleon/src/adl/Editora-V2-worktrees/codex-java-lsp-evaluation/src/main/java/com/editora/lsp/LanguageServerSession.java:682), [didClose:767](/Users/adriandeleon/src/adl/Editora-V2-worktrees/codex-java-lsp-evaluation/src/main/java/com/editora/lsp/LanguageServerSession.java:767).

Open/close mutate the shadow immediately, while queued changes mutate it later when initialization completes. These two notions of order diverge if a document is closed and reopened during startup.

**Reproduction:** before initialization, open `class A {}`, change to `class B {}`, close, and reopen the original `class A {}`. Complete initialization, then change the reopened document to `class B {}`. Only one change notification is sent instead of two: the last edit is suppressed as supposedly identical, although the server still has `A`.

**Impact:** after tab churn during a cold Java startup, diagnostics, completion, and refactor ranges can refer to a different document from the visible buffer. The periodic full resync is not an immediate repair.

**Fix direction:** update the server shadow in actual send order, including opens/closes, and make queued flush ordering safe against concurrent post-initialization sends. Ensure collapse keys do not cross document lifetimes.

**Acceptance:** the deterministic reopen sequence converges exactly; also test close/reopen of the same URI and edits arriving during queue flush.

### 5. P2 — A failed file move leaves a partial refactoring

**Resolution:** Fixed by staging all sources and overwrite backups, rolling the batch back on a late failure,
and applying buffer edits only after the filesystem transaction commits.

**Location:** [LspCoordinator.java:2378](/Users/adriandeleon/src/adl/Editora-V2-worktrees/codex-java-lsp-evaluation/src/main/java/com/editora/ui/LspCoordinator.java:2378).

The application applies every text edit before attempting file moves. If a later move fails, it returns `false` while retaining the text changes and any earlier successful moves. Preflight currently checks destination collisions but not all failure conditions; the documented all-or-nothing behavior is not maintained.

**Reproduction:** one workspace edit changes `class A {}` to `class B {}` and renames a source file that no longer exists. The operation returns failure, but the first buffer remains `class B {}`.

**Impact:** deletion or permission changes while a rename preview is open can leave references and filenames inconsistent. Per-buffer undo helps recover text but is not a transaction across buffers and files.

**Fix direction:** preflight all operations, stage changes, and roll back both text and completed moves if a later operation fails. Move filesystem work off the FX thread while guarding against intervening edits.

**Acceptance:** inject a failure on the second move; all original text, paths, and dirty-buffer states must be restored, and the server must receive an accurate failure response.

## Feature matrix

“Live” means a real JDT LS response through Editora's production session; it does not imply every corresponding UI interaction was manually tested.

| Area | Evidence / assessment |
| --- | --- |
| Java discovery/install | Registry, runtime discovery, and install paths reviewed and unit-tested. JDK discovery now runs on the start executor. Script and in-app installer both pin 1.60.0 / 202606262232. A macOS app image built successfully; distributed-install runtime discovery remains a device check. |
| Startup/import | Live Maven Java 25 fixture, a two-module Gradle fixture, and the actual Editora worktree imported. Initial fixture handshake was approximately five seconds; indexing continued after handshake. This is a functional contract, not a benchmark. |
| Workspace ownership | Per-root sessions, per-window workspace claims, idle eviction, crash limits, process-tree cleanup, failed-initialize cache repair/bypass, and root-scoped crash recovery have tests. Explicit shared `-data` overrides remain outside automatic cache ownership. |
| Synchronization | Full/incremental sync and UTF-16 diff tests pass. Startup reopen ordering is covered by a permanent regression test. |
| Completion/snippets | Live member completion returned 6 items; item resolution completed. Existing headless tests cover snippet placeholders, replacement ranges, and additional edits. |
| Auto-import on completion/paste | Existing tests cover mapping/application and stale paste guards. Not separately validated end to end against live Java in this audit. |
| Diagnostics | Live invalid `MissingType` produced an error after incremental change. Repository diagnostics and full-build results recorded separately below. |
| Definition/references | Live local definition and 2 method references returned. |
| JDK/library source | Live definition returned `jdt://`; the registered custom response returned 65,972 source characters. |
| Hover/signature help | Live hover and 1 signature returned. |
| Occurrences | 3 live document highlights returned. |
| Symbols | Fixture returned 4 document symbols. Worktree files returned 13, 121, and 182; workspace symbol search found `LspManager`. |
| Formatting | Live document and range formatting each returned 6 edits. Existing tests verify application ordering, undo, and stale full-format refusal. |
| On-type formatting/smart semicolon | Capability/wiring and focused tests reviewed; not exercised with actual typing in this audit. |
| Semantic highlighting | Live response contained 165 integers, representing 33 tokens. Cache entries are scoped to the serving session and monotonically increasing request generation, preventing restart and overlapping-response regressions. |
| Inlay hints | 2 live hints returned. Visibility/filtering/positioning have headless coverage. |
| Folding/selection | 4 live folding ranges and 1 selection-range chain returned. |
| Call/type hierarchy | Live call root plus 1 incoming call; type root plus 2 supertypes returned. |
| Implementation/type/declaration navigation | Request construction, mapping, and headless navigation coverage reviewed; not separately live-probed here. |
| Rename | Real prepare/rename response included the public-class file move and mapped successfully. Stale edits and late move failures now refuse the full operation. |
| Code actions/generation | Standard action discovery returned results. Custom response decoding and all four generator request shapes are fixed. |
| Direct organize imports/library extensions | Raw Java response types are registered so returned edits and library text survive decoding. |
| Watched files/reload/build | Watcher coalescing and routing reviewed. Reload uses a notification, so it is not affected by discarded response values. Full-build diagnostic results below. |
| Run/debug bridge | Standard execute-command bridge and debug-bundle initialization reviewed; no live DAP launch performed. |

## Performance, robustness, and documentation

Existing strengths include off-thread process startup, stderr draining, process-tree cleanup, initialization/command timeouts, debounced changes, bounded startup change retention, incremental synchronization, semantic deltas, lazy activation of background buffers, and stale-result guards for completion and formatting.

The code-observed risks from the original audit are resolved:

- JDT workspace directory creation and installed-JDK discovery run on `lsp-start`. Unopened workspace-edit files use the background file loader; resource staging, rollback, and cleanup run on virtual threads.
- Ordinary requests share a 30-second cancellation bound. Initialization keeps its 60-second bound, and queued command/raw-request futures complete exceptionally when startup fails or the session is disposed.
- Versioned diagnostics, structure outlines, and semantic-token cache/reply paths carry document, session, and request-generation guards.
- Dynamic registrations update the effective capabilities used by feature gates. Diagnostic, semantic-token, inlay, and folding refresh requests are routed to managed buffers. Create, rename, and delete filesystem operations are mapped and applied transactionally.
- A JDT session that dies before its first successful initialize removes its data cache when the Eclipse lock
  is free. If the lock is held, a sidecar marker makes the retry choose a fresh suffixed cache. Crash callbacks
  only deactivate and restart buffers whose resolved LSP root matches the failed session.

## Whole-project Java diagnostics

The full JDT workspace build reported **zero errors**. This is consistent with the passing Maven compilation and verification. Its **455 warnings** are primarily module API exposure warnings, not 455 independently confirmed functional bugs.

| JDT warning | Count | Triage |
| --- | ---: | --- |
| Public API exposes a type from a dependency without `requires transitive` | 193 | Review intended module/plugin API boundaries; do not automatically make every dependency transitive. |
| Public API exposes a type from a package this module does not export | 154 | Review exported surfaces and visibility; often application-internal API design. |
| Unnecessary deprecation suppression | 29 | Cleanup candidates; confirm annotations are unnecessary for supported dependency versions. |
| Unused fields | 25 | Inspect before removal; some values may support planned work or reflective behavior. |
| Unused local variables | 12 | Cleanup candidates. |
| Calls to deprecated methods | 10 | Check replacement API and behavior before migrating. |
| Unused private methods | 8 | Cleanup candidates; one example is `LanguageServerSession.markDead(Process)`. |
| Deprecated types | 7 | JavaFX/other dependency API migration review. |
| Unstable automatic-module names | 5 | Packaging/JPMS dependency metadata review. |
| Other warnings | 12 | Unchecked casts, deprecated fields/constructors, static access style, and other module API exposure. |

The largest file counts were `EditorBuffer` (41), `SettingsWindow` (23), and `MainController` (20). Counts indicate concentration, not severity. The detailed warning inventory includes exact paths, source lines, JDT codes, and messages.

The 108 informational diagnostics consist of 107 TODO/task markers and one Maven lifecycle mapping notice. They are included in `diagnostics.json`; the independent Maven result and the registered `java/buildWorkspace` response agree that the build completed without errors.

## Regression and live-contract coverage

The fast fake-server suite now covers request shapes, queue disposal, dynamic registration, refresh routing,
diagnostic versions, workspace resource operations and rollback, asynchronous production application, and
semantic-token generation/session guards. `JdtlsExtensionContractProbeTest` is a permanent opt-in test that
uses the production session and a throwaway project to assert library contents, organize imports, and all four
check → generate extension flows. Its optional Gradle case builds a two-module workspace and verifies
cross-module definition resolution.

```sh
mvn -Dtest=JdtlsExtensionContractProbeTest#realJavaFeatureMatrix -Dgroups=probe -Dlsp.probe=true test
mvn -Dtest=JdtlsExtensionContractProbeTest#realGradleMultiModuleImport -Dgroups=probe \
  -Dlsp.probe=true -Dlsp.probe.gradle=true test
```

Both live cases passed against JDT LS 1.60 on this host. The macOS app-image build and the final 4,531-test
repository verification also passed; logs are preserved beside this report. Remaining evidence requires another environment: Windows/Linux launch behavior,
a distributed installer rather than the local app image, remote workspaces, and comprehensive manual keyboard,
mouse, and accessibility checks. DAP remains a separate subsystem evaluation.

The `reproductions/` directory preserves the original failing audit fixtures and raw logs for provenance; use
the permanent opt-in test above for current behavior.

Evidence files: `mvn-verify.log`, `editora-java-lsp-tests.log`, `editora-java-lsp-verify.log`,
`editora-java-lsp-repros.log`, `editora-java-lsp-raw-repro.log`, and the live-session logs in this directory.
