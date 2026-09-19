# Java interactive editing review

Review base: `85a60dc5`, September 2026. The reference is observable IDEA typing behavior,
not its PSI architecture. This is a source audit, headless JavaFX interaction test, and live JDT LS
probe. It is **not** a side-by-side IDEA usability study or a claim of feature parity.

## Pipeline and ownership

1. `BufferCompletion.addCompletionKeys`/`installCommitCharacters`, then
   `EditorBuffer.addSnippetKeys`/`addAutoClose`/`addAutoIndent`, then RichTextFX `CodeArea` input
   handle physical keys. Completion must register first and lower-priority handlers must respect
   consumed events. The two split views share a document.
2. `plainTextChanges` advances `EditorBuffer.docVersion` synchronously and invalidates the shared
   immutable text snapshot. `SettledEditDispatcher` runs background-feature milestones.
3. `EditorBuffer.sendLspChange` deduplicates by buffer version; `LspCoordinator.wireBuffer` forwards
   text through `LspManager.changeDocument` to `LanguageServerSession.didChange`.
4. The session maintains protocol versions and a shadow; `TextSyncDiff` creates a UTF-16 splice.
   Initialization queues lifecycle notifications; incremental/full/None modes are negotiated.
5. `BufferCompletion.documentChanged` queues immediate member-trigger/cache work after the caret
   settles. `updateCompletion` handles the identifier debounce or manual invocation, computes the
   prefix, obtains local snippets, and flushes sync before `requestLspCompletion` requests through
   coordinator → manager → session → `textDocument/completion`.
6. `CompletionMapper` converts protocol items to neutral `Completion` values; `CompletionEngine`
   ranks and `CompletionPopup` renders, handles selection, and drives `CompletionDocPopup` resolve.
7. `BufferCompletion.acceptCompletion` applies the range and literal/snippet;
   `EditorBuffer.startSnippet` → `SnippetParser` → `SnippetSessions`/`SnippetSession` owns nested placeholders/caret/Tab.
8. `LspCoordinator.autoImportAccept` applies eager edits or resolves imports;
   `CompletionEditTracker`/`LspEditShift` translate their ranges and `EditorBuffer.applyLspEdits`
   applies an undoable batch while preserving caret/selection. The item's command runs afterward,
   including JDT LS selection feedback, with the updated document synchronized first.
9. Signature help travels through `LspCoordinator.signatureHelp`, `SignatureSelection`, `SignatureFormat`, and a popup.
   Diagnostics pass through the session, manager, `DiagnosticMapper`, coordinator, overlays/Problems.
   Hover/navigation/formatting share the same buffer/session routing.
10. `RootResolver`, `LspServerRegistry`, per-root JDT workspaces, JDK discovery, and initialization
    settings supply Maven/Gradle project identity. Semantic relevance belongs primarily to JDT LS.

## Investigation: issues by impact

| Priority / layer | Observed code behavior and root cause | Expected behavior / fix |
| --- | --- | --- |
| High / server configuration | JDT completion/resolve can overtake pending document lifecycle jobs despite ordered wire messages; raw edits can remove an existing import or omit a needed one. | `JavaServerEnvironment` enables `java.lsp.joinOnCompletion`; explicit command/environment overrides remain authoritative. Live Maven/Gradle probes exercise this ordering. |
| High / UI | `EditorBuffer` registered completion keys after auto-indent, incorrectly assuming the last filter runs first. Enter could insert a newline before accepting. | Register completion first; lower-priority filters respect consumption. Real key-event regression tests cover Enter and Tab. |
| High / UI + client | All automatic completion waits 280 ms; every refresh requests again; list metadata is flattened in `LspManager.completion`. | Immediate member triggers, shorter identifier debounce, complete-list reuse, incomplete-list retriggers. |
| High / UI | Response guards exist, but accepting an already-visible item does not validate its context; dismissal does not cancel the wire future. | Track prefix edits, reject unrelated changes, cancel superseded requests, guard both worker and FX delivery. |
| High / client | `filterText`, item defaults, trigger context and commit characters are discarded; insert/replace has only one range. | Carry protocol semantics end-to-end and advertise only implemented capabilities. |
| High / UI | Method snippets can duplicate existing parentheses; insertion does not explicitly request signature help. | Reuse existing calls without consuming arguments; preserve server snippets otherwise; request help after insertion. |
| High / client | Imports already in an item still wait for resolve; deferred imports are dropped on any subsequent typing. | Apply eager edits immediately; safely track deferred edits, rejecting conflicting changes. |
| High / UI | `applyLspEdits` restores only the caret, collapsing selected snippet arguments when an import arrives. | Translate and restore both ends of the selection. |
| Medium / UI | Completion dismissal could also cancel an enclosing snippet; independent split carets could invalidate the focused completion session. | Escape dismisses completion first; caret invalidation observes only the focused view. Both have interaction regressions. |
| Medium / ranking | Deduplication by inserted text collapses overloads and ambiguous classes; fuzzy alternatives disappear whenever any prefix match exists. | Preserve server identities and overloads; honor filterText and server relevance within match tiers. |
| Medium / client | No completion selection command is executed; advertising label details without consuming them would hide JDT LS parameters. | Execute server commands after edits; show label details and package/type descriptions. |
| Medium / async UI | Signature help and hover lack request generation, caret, and version guards. | Older responses must not overwrite newer state or reopen dismissed popups. |
| Medium / performance | Mapping, regex snippet flattening, sorting/filtering run on FX; completion calls `getText` separately from snapshot cache. | Process lists off FX, use bounded prefix reads and shared snapshots, cap only displayed results. |
| Medium / diagnostics | Push versions checked before queuing FX delivery, but not at delivery; pull results have no version guard. | Recheck session/version when rendering results. |
| Medium / sync | `didChange` increments the version before detecting an identical snapshot, including forced completion/hover/signature flushes. | Advance the wire version only when sending a change; preserve queued open/change/close order. |

| Medium / UI | Signature help closes on newline and offers no overload navigation. | Retain the popup across lines, debounce caret refreshes, preserve the chosen overload, and send `activeSignatureHelp` on retrigger. |
| Medium / snippets | Accepting a method snippet inside an argument destroys the outer session; an import cancels its tracking. | Bounded nested sessions suspend parent mirroring and resume outer Tab stops; non-overlapping imports translate every level. |
| Medium / undo | Completion and resolved imports undo separately, especially when typing continues before resolution. | Group completion/imports while retaining intervening typing as separate steps; safely rebase disjoint history. |

## Changes and concurrency model

`CompletionSource` returns a cancellation action and a `CompletionResult` containing the full list
and `isIncomplete`. `CompletionSession` permits only suffix identifier edits at the requested caret.
It rebases item ranges across those edits. Moving to another expression, deleting below the original
prefix, editing elsewhere, closing the buffer, or dismissing completion invalidates the session.
Both response delivery and worker-to-FX rendering check identity/generation/version/caret. Acceptance
checks the visible session again; a stale popup cannot apply old text edits while refresh is pending.

Complete lists are filtered locally on subsequent typing and backspace. Incomplete lists retrigger
with `TriggerForIncompleteCompletions`; real trigger characters use `TriggerCharacter`; explicit
invocation uses `Invoked`, including empty prefixes inside broken Java. A response arriving during
compatible typing can still serve the current prefix. Superseding or dismissing it cancels the
original JSON-RPC future. Cancellation is best effort, so delivery guards remain essential.

The mapper expands `CompletionList.itemDefaults` before mapping or resolve and retains insert/replace
ranges, `filterText`, `commitCharacters`, snippet format, resolve data, and indentation mode. Enter
uses the insert range; Tab uses the replace range. Snippets use the existing parser, including choice
and nested placeholders. `AsIs` preserves indentation; `AdjustIndentation` uses the snippet indentation
logic. Existing method argument lists are reused, and method references do not acquire parentheses.
Plain method completions get a conservative call insertion when the server omits one.

Ranking uses exact-case prefix, case-insensitive prefix, and subsequence match tiers. Within a tier,
server `preselect`/`sortText` are retained, label is the protocol fallback when `sortText` is absent,
and equal sort keys retain server order. There is no speculative client Java type inference or
hard-coded promotion of `String`/`println`. Explicit keyboard selection survives local filtering if
the item remains. Only the displayed list is capped at 50; the cache retains every server candidate.

Deferred additional edits own an independent bounded transformation history (at most eight pending
acceptances, 128 edits each). Non-conflicting typing and edits before imports are translated; undoing
the acceptance, changing its name, overlapping the import edit, exceeding the bound, closing or
renaming the buffer drops the stale batch. The client preserves the server's import ordering and
conflict decisions rather than inventing a second Java import manager.

Signature and hover responses carry request generation, buffer/path/view, document version, and caret
guards. If continued typing invalidates the first signature response before its popup opens, the
settled milestone retries the pending context. Escape disarms this retry in either split view.
Forced synchronization no longer launches Structure and signature refresh work for every
completion; those remain on the settled 300 ms milestone. Versioned push diagnostics are checked
again at FX delivery, including the raw context retained for quick fixes, and pull diagnostics retain
their requested session/version.

`JavaServerEnvironment` adds `-Djava.lsp.joinOnCompletion=true` to the Java server process's
`JDK_JAVA_OPTIONS`, preserving existing options and explicit command/environment overrides. Both
installed JDT bytecode and [JDT's implementation](https://github.com/eclipse-jdtls/eclipse.jdt.ls/blob/main/org.eclipse.jdt.ls.core/src/org/eclipse/jdt/ls/core/internal/handlers/JDTLanguageServer.java)
confirm that completion and resolve use this option to await document lifecycle work. Waiting occurs
inside the server, never on FX. Other language server processes are unaffected.

`SignatureSelection` keeps an explicitly chosen signature by label across reordered lists, returning
to the server choice when that overload disappears. `lsp.nextSignature` and `lsp.previousSignature`
are registered, bindable commands; the popup's previous/next buttons dispatch those commands.
Finished zero-argument Java calls and method references do not issue an unnecessary signature request;
empty argument placeholders, existing argument lists and enclosing snippet arguments retain help.
These commands can be assigned through keymap settings. Newlines retain the popup; caret movement
refreshes after 120 ms, duplicate version/caret refreshes are skipped, and an empty server response,
Escape, actual scrolling, or focus loss dismisses it. Retriggers include `activeSignatureHelp`.

`SnippetSessions` bounds nested expansions to 16 levels. Only the child handles typing/Tab; suspended
parents track ranges, then update mirrors when the child finishes. Escape, undo, or overlapping
external edits abandon the chain. Imports before the placeholders shift both the child and parent
ranges without losing selection. The child finishes at its own final stop; the next Tab advances
the enclosing arguments. Both split views retain the same buffer-owned session stack.

`CompletionUndoManager` decorates the bounded UndoFX manager. It records completion changes only
during acceptance, prevents merging with preceding/following typing, and groups adjacent imports.
`CompletionUndoFactory` adds a queue adapter that can commute disjoint delayed imports backwards
through intervening edits. `ImportUndoRebase` translates offsets and preserves plain/styled payloads;
the resulting document is identical, and subsequent typing remains independently undoable. There
is no temporary document undo/replay while rebasing. Both split-view managers participate, and group
identities are updated when an earlier import crosses a later completion.

The adapter delegates ordinary history, marks, capacity, merging and replay to UndoFX. Its dependency
on UndoFX's public `impl.ChangeQueue`/`MultiChangeUndoManagerImpl` constructors is covered by integration
tests and should be checked on library upgrades. It scans at most 128 history entries / 256 primitive
edits; missing, trimmed, overlapping, ambiguous same-position or branched histories fall back to
ordinary chronological undo. Marks inside rewritten history become invalid, while earlier marks
remain valid. These limits protect correctness and bound the uncommon late-import path.

## Validation and scenario coverage

Follow-up formatting and `mvn verify` passed on JDK 25: **4,826 tests, zero failures/errors,
24 skipped**, with formatting, packaging, and coverage checks passing. Opt-in live probes run
separately from ordinary CI. The skips include the sustained and component-cost probes, which
are run separately without coverage instrumentation for meaningful measurements.

`JavaTypingCompletionFxTest` supplies a controllable async server source to a real `EditorBuffer`
inside a headless JavaFX Stage. Its `type`, `press`, `respond`, and `expectCompletion` helpers test
actual interaction sequences, not just mapper methods. It covers rapid typing, complete-list
backspace, compatible in-flight results, out-of-order requests, Escape cancellation, stale visible
items, overload navigation, insert/replace acceptance, existing argument lists, method signature
requests, commit characters, chained completion, completion within arguments, imports while typing,
selection preservation, empty-prefix invalid Java, and UTF-16 ranges.

`JavaEditingResponsesFxTest` uses the production coordinator and fake protocol futures for old/new
signature responses, pending Escape, typing, and hover caret movement. Focused pure and protocol
tests cover default precedence, label details, snippet choices, insertion planning, edit translation,
incremental/full/None synchronization, queued lifecycle, cancellation, negotiated capabilities,
worker mapping, and diagnostic delivery. Existing PHP, snippet, auto-close, sync-diff, and completion
tests are part of validation because this path serves languages besides Java.

`JdtlsTypingProbeTest` is opt-in and creates its own Maven project rather than depending on a personal
fixture. It uses production session capabilities and checks availability for `Str`, `System.`,
`System.out.`, `System.out.pr`, String assignment and return contexts, boolean conditions, `new Arr`,
override proposals, unfinished members/generics, and method references. It resolves an unimported
`ArrayList`, checks existing explicit/wildcard imports are not duplicated, and requests signature
help inside unfinished `println(`.

`JavaProjectEditingProbeTest` creates two-module Maven or Gradle projects with 300 sibling-module
classes and a 74,973-character Java source. Readiness requires resolving `fixture.Api.compose` from
the sibling module, not just `java.lang`. It then checks chained members and repeatedly replaces
imports/declarations between unimported, explicit-import, wildcard-import, and same-file-conflict
cases. Each response and resolve is awaited without a retry that hides invalid edits.

**Fixture correction:** the original small probe placed Eclipse's workspace inside the Maven root.
JDT's log showed an overlapping workspace/project location prevented proper Maven import. Its early
results proved standalone completion only. Both probes now use sibling project/workspace directories;
the cross-module assertion proves the larger fixtures actually loaded. The performance numbers below
are from the corrected fixtures.

With lifecycle joining explicitly disabled, a 40-request Maven run returned six invalid import batches
(including removing an existing import). With the production default enabled, 200 requests in Maven
and 100 in Gradle returned **zero import-preservation failures**. The smaller scenario probe also
passed with that default. This isolates a concrete launch-configuration fix rather than inventing a
second client-side Java import resolver.

A separate server gap remains in JDT LS 1.61.0.202607301809: in a file declaring `class ArrayList {}`,
selecting the external `java.util.ArrayList` proposal for unfinished `new ArrayLi` resolves to an
import that conflicts with the same-file declaration. This occurred in all 50 Maven and 25 Gradle
conflict rounds after lifecycle joining. `-Dlsp.java.probe.strict=true` makes that recorded gap fail;
the default probe still asserts ordinary import preservation. A passing default probe is not a
claim of ambiguous-import parity. No report has been sent upstream by this task.

Additional regressions cover overload selection across multiline refreshes and protocol context,
three nested snippet levels, parent mirrors/final selections, import shifts, chain cancellation,
and eager/delayed import undo/redo, intervening typing in either split view, multiple pending
completions, styled payloads, mark validity, redo branching, bounded fallback and 500 seeded editing
sequences. Range conversion checks every offset pair in multiline/CRLF/UTF-16 fixtures.

## Performance findings

The initial measurements below are indicative single-machine samples. The subsequent sustained and
component-cost results are recorded in the [study evidence](../../artifacts/java-editing-study/README.md).
The corrected initial live probes used JDK 25, installed JDT LS above, and Gradle 9.6.1:

| Stage/scenario | Observed time |
| --- | ---: |
| Small-source sync call before request | 0.12–0.45 ms |
| `Str` request/response | 204 ms |
| `System.` / `System.out.` / `System.out.pr` | 80 / 180 / 67 ms |
| Expected String assignment / return / boolean | 177 / 172 / 177 ms |
| `new Arr` type search | 337 ms |
| Override / unfinished generic / method reference | 65 / 74 / 70 ms |
| Mapping and sorting | 0.06–1.92 ms |
| Maven / Gradle cross-module readiness | 2.42 / 6.28 seconds |
| Maven / Gradle 75 KB chained-completion sync + request | 358 / 356 ms |
| Maven / Gradle repeated completion + import resolve median | 75 / 77 ms |
| Maven / Gradle repeated completion + import resolve maximum | 1,173 / 1,308 ms |

The 40-request unjoined Maven baseline had a 15 ms median but returned six invalid import batches;
joining pending lifecycle work increases server latency in exchange for consistent document state.
Local complete-list filtering still avoids server round trips while narrowing a prefix. The probes
use small generated classpaths and normal-mode source; they do not establish enterprise-project
startup, desktop frame latency, or sustained typing percentiles.

With `-Deditora.completion.trace=true`, a separate headless interaction run using fake server
responses measured immediate edit-to-sync at median 2.46 ms (15 samples), filtering plus FX dispatch
at median 0.61 ms (20 samples), and popup model updates at median 4.96 ms (20 samples; first/cold
update about 35 ms). These timings exclude server work and do not measure a painted display frame.
Identifier typing intentionally waits 90 ms rather than the previous 280 ms; members and cached-list
updates run on the next FX turn. Non-LSP completion retains its 280 ms timing. Ordinary document
synchronization remains at 300 ms unless an interactive request flushes it first.

Protocol JSON decoding is on the LSP reader; completion mapping, sorting, and filtering now run off
FX. Scene graph updates and short document/prefix reads remain on FX. A full text snapshot and
`TextSyncDiff` still run on the calling thread during synchronization, and have O(document length)
cost. The sustained-study follow-up profiles these separately and replaces per-character range scans
with optimized `String.indexOf` newline searches (see the evidence below). No blocking future wait or project scan was
added to the interactive FX path. Resolving selection documentation is still debounced at 180 ms,
and Markdown rendering for documentation/signature/hover remains an existing FX-thread cost.

Client caching/debounce improvements cannot remove initial JDT indexing or cold type-search cost.

## Scope boundaries

Expected-type ranking, overload applicability, accessibility, ambiguous imports, wildcard rules,
constructible types, override proposals, and broken-source recovery require semantic information
from JDT LS. Preserve its `sortText`, edits, snippets, and resolve data; do not invent client-side
Java type inference or rewrite its imports. Existing formatter responses are version guarded;
on-type formatting is configurable and must not race accepted snippets.

| Remaining difference | Owner and reason |
| --- | --- |
| `String` and `println` are available but not necessarily first on a fresh workspace; `System.out.` starts with other legal members. | JDT LS relevance/history. The live probe returned `StrictMath` before `String` and `print` overloads before `println`; server selection feedback is now executed. A recency policy needs measured evidence before overriding semantic scores. |
| Expected-type filtering is less strict than IDEA smart completion. | JDT LS. The String assignment probe placed `value` first but also returned incompatible `variant`; the client has no type graph with which to safely infer assignability. |
| Conflicting same-file type import in transient broken source. | JDT LS, directly reproduced above. No independent semantic Java import resolver exists in Editora. |
| A newer completion can invalidate an earlier item's resolve token. | JDT LS: the standalone client reproduced `Invalid completion proposal` after two identical requests without a document change. Range rebasing cannot restore the server's discarded binding; see the proposal-lifetime evidence in the upstream report. |
| Unresolved receiver or severely broken context can yield no proposals. | JDT LS semantic recovery and project configuration; client requests are still sent and empty results remain safe. |
| Overlapping, branched or excessively old delayed imports retain ordinary undo ordering. | Editora deliberately declines ambiguous history transforms; disjoint recent typing now rebases safely. |
| Diagnostics before the next synchronization and unversioned server notifications. | Client/server contract: 300 ms settled synchronization and optional protocol versions limit freshness guarantees. This change rejects known-stale versions, not unverifiable notifications. |
| Painted desktop frame latency and human typing comfort. | The sustained harness now opens disposable copies of real Maven/Gradle projects, but headless JavaFX pulses cannot establish human desktop usability. |
| Intermittent `Str` popup timeouts in the initial long macro stress run. | Unclassified: five Maven failures were recorded; later event/macro runs passed. A source-free request/state history is now captured by the probe on recurrence. No speculative retry or claimed root-cause fix was added. |

## Recommended next improvements

1. Reproduce the recorded intermittent popup timeouts with the new bounded request/state capture
   before classifying them as client, server or harness behavior.
2. Complete a human desktop typing trial with painted-frame measurements. Automated project typing,
   component profiling and split-view undo are now covered; headless timings cannot replace this trial.
3. Design a per-session ordered synchronization worker with immutable snapshot capture and explicit
   queued/sent version semantics. Uncached 4 MB snapshots still occupy about 9–10 ms of FX time even
   after the cheaper range scan.
4. Submit the prepared [standalone JDT report](../../artifacts/java-editing-study/jdt-import-conflict/REPORT.md)
   when ready, and rerun it against server updates. The report has not been sent externally.
5. Evaluate a bounded context-aware recency policy against the observed unchanged selection ranks,
   including expected-type and overload cases. Repetition alone is not evidence that overriding JDT
   semantic ordering is safe.

Protocol reference: [LSP completion](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocument_completion).
