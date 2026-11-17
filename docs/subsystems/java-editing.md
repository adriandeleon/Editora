# Java interactive editing

How a keystroke in a Java file becomes synchronized text, a completion popup, an accepted
proposal, its imports and signature help, and which part of that belongs to Editora and which to
JDT LS. The reference for the behaviour is what IntelliJ IDEA does while you type, not its PSI
architecture: Editora does not infer Java types itself, it carries JDT LS's semantics through
without losing them. The same path serves every language with a completion-capable server, so
changes here are checked against the PHP, snippet, auto-close and sync-diff tests too.

## Pipeline and ownership

1. `BufferCompletion.addCompletionKeys`/`installCommitCharacters`, then
   `BufferSnippets.addSnippetKeys`, `BufferTyping.addAutoClose`/`addAutoIndent`, then RichTextFX `CodeArea` input
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
   `BufferSnippets.startSnippet` → `SnippetParser` → `SnippetSessions`/`SnippetSession` owns nested placeholders/caret/Tab.
8. `LspCoordinator.autoImportAccept` applies eager edits or resolves imports;
   `CompletionEditTracker`/`LspEditShift` translate their ranges and `EditorBuffer.applyLspEdits`
   applies an undoable batch while preserving caret/selection. The item's command runs afterward,
   including JDT LS selection feedback, with the updated document synchronized first.
9. Signature help travels through `LspPopups.signatureHelp`, `SignatureSelection`, `SignatureFormat`, and a popup.
   Diagnostics pass through the session, manager, `DiagnosticMapper`, coordinator, overlays/Problems.
   Hover/navigation/formatting share the same buffer/session routing.
10. `RootResolver`, `LspServerRegistry`, per-root JDT workspaces, JDK discovery, and initialization
    settings supply Maven/Gradle project identity. Semantic relevance belongs primarily to JDT LS.

## Completion sessions and concurrency

`CompletionSource` returns a cancellation action and a `CompletionResult` containing the full list
and `isIncomplete`. `CompletionSession` permits only suffix identifier edits at the requested caret.
It rebases item ranges across those edits. Moving to another expression, deleting below the original
prefix, editing elsewhere, closing the buffer, or dismissing completion invalidates the session.
Both response delivery and worker-to-FX rendering check identity/generation/version/caret. Acceptance
checks the visible session again; a stale popup cannot apply old text edits while refresh is pending.
The local list (snippets/keywords, no server session) has the same guarantee by other means: every
document change recomputes it once the edit has settled, a caret move without an edit closes it, and
acceptance refuses a row whose trigger no longer starts with the word before the caret — the key is
then left unconsumed, so Enter is a newline and Tab indents.

Complete lists are filtered locally on subsequent typing and backspace. Incomplete lists retrigger
with `TriggerForIncompleteCompletions`; real trigger characters use `TriggerCharacter`; explicit
invocation uses `Invoked`, including empty prefixes inside broken Java. A response arriving during
compatible typing can still serve the current prefix. Superseding or dismissing it cancels the
original JSON-RPC future. Cancellation is best effort, so delivery guards remain essential.

The mapper expands `CompletionList.itemDefaults` before mapping or resolve and retains insert/replace
ranges, `filterText`, `commitCharacters`, snippet format, resolve data, and indentation mode. Enter
uses the insert range; Tab uses the replace range. Snippets use the existing parser, including choice
and nested placeholders. `AsIs` preserves indentation (the text goes in untouched apart from line
endings, which are always normalised to LF before tab-stop offsets are used); `AdjustIndentation`
converts the snippet's leading tabs to the buffer's indent unit and uses the snippet indentation
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
Forced synchronization does not launch Structure and signature refresh work for every
completion; those run on the settled 300 ms milestone. Versioned push diagnostics are checked
again at FX delivery, including the raw context retained for quick fixes, and pull diagnostics retain
their requested session/version.

`JavaServerEnvironment` starts the Java server with `-Djava.lsp.joinOnCompletion=true`: on its command
line when it knows the launcher (`--jvm-arg=…` for the jdtls launcher script, `-D…` after a direct
`java`), and through `JDK_JAVA_OPTIONS` only for a wrapper command it cannot extend — the environment
is inherited by every JVM jdtls starts, a debugged program included. Existing options and explicit
command/environment overrides are preserved. Both
installed JDT bytecode and [JDT's implementation](https://github.com/eclipse-jdtls/eclipse.jdt.ls/blob/main/org.eclipse.jdt.ls.core/src/org/eclipse/jdt/ls/core/internal/handlers/JDTLanguageServer.java)
confirm that completion and resolve use this option to await document lifecycle work. Without it,
ordered wire messages are not enough: completion and resolve can read an older working copy and
return import edits that remove an existing import or miss a needed one. Waiting occurs inside the
server, never on FX, and costs server latency in exchange for a consistent document. Other
language server processes are unaffected.

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

## Timing and threads

- Member triggers (`.` and the server's other trigger characters) and cached-list updates run on
  the next FX turn. Java identifiers wait for a 90 ms settled milestone; completion without a
  language server keeps 280 ms. Ordinary document synchronization runs on the settled 300 ms
  milestone unless an interactive request flushes it first.
- Signature help refreshes 120 ms after the caret moves; resolving the selected item's
  documentation is debounced at 180 ms.
- Protocol JSON is decoded on the LSP reader thread; completion mapping, sorting and filtering
  run off FX. Scene-graph updates and short document/prefix reads stay on FX.
- A full text snapshot and `TextSyncDiff` run on the calling thread during
  synchronization, at O(document length); newline search uses `String.indexOf` rather than a
  per-character scan. Moving them off FX needs an ordered per-session synchronization worker:
  capture an immutable document on FX, diff it on the worker, and queue every request that
  depends on that version behind it.
- No blocking future wait or project scan runs on the interactive FX path. Markdown rendering of
  documentation, signature and hover popups is an FX-thread cost.
- Client caching and debouncing cannot remove JDT LS's initial indexing or a cold type search.
- With `-Deditora.completion.trace=true` the client logs stage names and durations (edit to
  sync, filtering and FX dispatch, popup model updates), never source text.

## Where the limits are

Expected-type ranking, overload applicability, accessibility, ambiguous imports, wildcard rules,
constructible types, override proposals, and broken-source recovery require semantic information
from JDT LS. Preserve its `sortText`, edits, snippets, and resolve data; do not invent client-side
Java type inference or rewrite its imports. Existing formatter responses are version guarded;
on-type formatting is configurable and must not race accepted snippets.

| Limit | Owner and reason |
| --- | --- |
| `String` and `println` may not be first on a fresh workspace, and `System.out.` may start with other legal members. | JDT LS relevance and history. Server selection feedback is executed; a client recency policy would need measured evidence before it overrides semantic scores. |
| Expected-type filtering is less strict than IDEA smart completion. | JDT LS. An assignment context can still list incompatible names; the client has no type graph to infer assignability safely. |
| Choosing an external type (`java.util.ArrayList`) for unfinished `new ArrayLi` in a file that declares its own `class ArrayList {}` resolves to a conflicting import. | JDT LS, reproduced by the strict probe. Editora has no semantic Java import resolver of its own. |
| A newer completion can invalidate an earlier item's resolve token (`Invalid completion proposal`). | JDT LS discards the earlier binding; range rebasing cannot restore it. |
| Unresolved receiver or severely broken context can yield no proposals. | JDT LS semantic recovery and project configuration; client requests are still sent and empty results remain safe. |
| Overlapping, branched or excessively old delayed imports keep ordinary undo ordering. | Editora deliberately declines ambiguous history transforms; disjoint recent typing rebases safely. |
| Diagnostics before the next synchronization and unversioned server notifications. | Client/server contract: 300 ms settled synchronization and optional protocol versions limit freshness. Known-stale versions are rejected; unverifiable notifications cannot be. |
| Painted frame latency and how typing feels on a desktop. | Headless JavaFX pulses cannot measure them; the probes drive real projects but not a display. |

## Tests and probes

- `JavaTypingCompletionFxTest` drives a real `EditorBuffer` in a headless JavaFX stage against a
  controllable asynchronous server source. Its `type`, `press`, `respond` and
  `expectCompletion` helpers test interaction sequences: rapid typing, complete-list backspace,
  compatible in-flight results, out-of-order requests, Escape, stale visible items, overload
  navigation, insert/replace acceptance, existing argument lists, commit characters, chained
  completion, completion inside arguments, imports while typing, selection preservation,
  empty-prefix invalid Java and UTF-16 ranges.
- `JavaEditingResponsesFxTest` uses the production coordinator with fake protocol futures for
  old and new signature responses, a pending Escape, typing, and hover caret movement.
- Pure and protocol tests cover default precedence, label details, snippet choices, insertion
  planning, edit translation, incremental/full/None synchronization, queued lifecycle,
  cancellation, negotiated capabilities, worker mapping and diagnostic delivery; others cover
  overload selection across multiline refreshes, nested snippets, import shifts, eager and
  delayed import undo/redo in either split view, redo branching, mark validity, the bounded
  fallback and seeded random editing sequences. Range conversion is checked for every offset
  pair in multiline, CRLF and UTF-16 fixtures.
- `JdtlsTypingProbeTest` (opt-in, `-Dgroups=probe`) creates its own Maven project and checks
  completion for `Str`, `System.`, `System.out.pr`, assignment/return/condition contexts,
  `new Arr`, overrides, unfinished members and generics and method references; it resolves an
  unimported `ArrayList`, checks existing imports are not duplicated, and asks for signature
  help in an unfinished `println(`. [Testing](../testing.md) has the command line.
- `JavaProjectEditingProbeTest` (opt-in) builds two-module Maven and Gradle projects with many
  sibling classes and a large Java source. Readiness means resolving a class from the sibling
  module, not just `java.lang`; it then checks chained members and repeatedly swaps imports
  between unimported, explicit, wildcard and same-file-conflict cases, awaiting each response
  without a retry that would hide an invalid edit. The probes keep Eclipse's workspace in a
  sibling directory: a workspace inside the Maven root overlaps the project and stops JDT from
  importing it, which silently reduces the probe to standalone-file completion.
- `-Dlsp.java.probe.strict=true` makes the probes fail on the same-file import conflict below;
  use it when evaluating a JDT LS upgrade. Without it, a passing probe asserts ordinary import
  preservation, not that conflict.

Measurements from the sustained typing and component-cost studies, and the standalone JDT
reproduction of the import conflict, are kept with their dates in
[`artifacts/java-editing-study/`](../../artifacts/java-editing-study/README.md).

Protocol reference: [LSP completion](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocument_completion).
