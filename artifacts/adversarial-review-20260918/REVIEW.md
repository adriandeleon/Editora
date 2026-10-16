# Editora adversarial production-readiness review

> Archived 2026-10-15. Review of commit `85a60dc5` (2026-09-18); its findings were fixed in
> [#1000](https://github.com/adriandeleon/Editora/pull/1000). Source links point at the reviewed commit, not
> current master. The probe and test outputs beside this file are the raw evidence it cites.

## Architecture and interaction model

Reviewed commit **85a60dc5** (the checkout HEAD supplied for this task), in the isolated `codex/adversarial-review-20260918` worktree. Production code and repository tests were not modified. This report concerns this revision, not later changes on master.

Each window owns a `MainController`, its `EditorBuffer` instances, `FileWorkflowCoordinator`, `LspManager`/`LspCoordinator`, `GitCoordinator`, and `DiffCoordinator`. A buffer owns one RichTextFX document, with an optional second view of that document. Separate windows can have independent buffers for the same path. Shared configuration owns an application-wide `DocumentWriteSequencer`, but there is no application-wide live-document ownership or conflict-resolution service.

An editor mutation increments `EditorBuffer.docVersion` immediately. A settled-edit dispatcher later sends LSP changes, normally after 300 ms; completion and several explicit LSP commands also flush text. The LSP session separately tracks its protocol version and last-sent text. Session routing uses document URI strings, whereas several UI lookups use canonical filesystem paths. Server results generally cross back to FX using `Platform.runLater`. Some consumers validate buffer versions; others validate only before queuing, or not at all.

Saves capture immutable text/bytes and a path ticket on FX, stage and replace files on a per-window save executor, then acknowledge the captured text on FX. This correctly leaves subsequent edits dirty. Synchronous save callers nevertheless run a nested FX event loop while waiting, so further edits and callbacks can execute before the save returns. Local explicit saves do not perform the conflict check used by remote saves.

Git commands execute through a per-window serial worker. This serializes that window's Git commands, not editor saves, LSP resource edits, other windows' Git commands, or external Git. Status, gutters, and diff views are refreshed separately. Branch/stash paths invalidate pending save tickets and reload clean buffers, but Git Log mutations use a different path without those protections. Dirty buffers deliberately survive checkout and therefore require reconciliation with the new on-disk version.

Diffs combine fetched Git blobs, files, clipboard snapshots, or live buffer text. Most apply paths reconstruct a complete replacement document and compare a displayed baseline with current text. Git staging constructs a patch and runs `git apply --check` followed by `git apply`. A rendered diff can also contain a separately editable Result draft. Merge results are another separate editable control. These drafts are not `EditorBuffer`s and do not participate in normal dirty-tab close protection.

| Mutable state | Owner/thread | Existing protection | Important gap |
| --- | --- | --- | --- |
| Live document, undo, selections | Buffer; FX thread | Immediate local version, shared split-view document | Programmatic callers can bypass read-only/narrowing rules |
| Pending saves | Window worker + shared path sequencer | Superseding tickets, atomic replacement, captured-content acknowledgment | No local explicit-save preimage check; close does not recheck dirty state |
| LSP shadows/protocol versions | Session; FX callers and initialization/RPC threads | Concurrent maps, queued startup notifications, bounded requests | Version can advance without a notification; result identity/epoch guards vary |
| LSP resource transaction | Coordinator; virtual thread then FX | Staging, backups, rollback, validation of text-edit buffers | Resource-only targets escape dirty-buffer validation; overwrite identities collide |
| Git repository/UI snapshot | Git worker, then FX | Serial executor; refresh counter | Counter checked before FX delivery; some mutation paths bypass reconciliation |
| Diff snapshots/Result draft | Coordinator/view; worker computations, FX UI | Refresh generations; baseline checks | Summary rows can be applied as source text; draft lifecycle is separate |

### Evidence and validation

**25 findings: 13 High, 12 Medium. No Critical or Low findings are asserted.** “CONFIRMED BUG” means the behavior follows directly from the code; findings marked “reproduced” also ran in disposable probes against the compiled application classes. “HIGH-CONFIDENCE BUG” identifies a concrete execution path whose complete real-server/external-process timing still needs reproduction. No generic theoretical risks are counted as findings.

I ran **31 existing test classes: 288 tests reported, 0 failures, 0 errors, 1 skipped** using the installed Temurin JDK 25.0.4 and Maven 3.9.16. Coverage included LSP protocol/lifecycle/requests/workspace edits, Git destructive operations and patch application, diff/merge models and editable results, save ordering/lifecycle, atomic writes, narrowing, multicaret, and replacement. These were targeted runs, not a full `mvn verify` or a live JDT LS/platform matrix. Disposable headless JavaFX and temporary-Git-repository probes confirmed the concrete results quoted below. No user project files were used for destructive probes.

The evidence packet contains [test results](test-results.txt), [core/data-loss probes](probe-output.txt), [LSP protocol/parser probes](probe-protocol-output.txt), [document-identity probes](probe-identity-output.txt), [native Git transcripts](probe-native-git-output.txt), and the [dirty Result command probe](probe-draft-output.txt). Native Git probes used argv arrays, so the wildcard deletion came from Git pathspec handling, not shell expansion.

Source links below point to the reviewed worktree. Each anchor identifies the relevant method; ranges stated in prose are inclusive.

## Critical

None established. Several High findings can lose unsaved or untracked data, but I did not establish a broad, unavoidable catastrophic failure warranting Critical.

## High

### H01 — Successful Save-and-close discards edits made while the save is running

**CONFIRMED BUG — reproduced. Severity: High. Confidence: High. Subsystems: core document, save, tab/window lifecycle.**

- **Locations:** [MainController.java:8958](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/MainController.java#L8958), `confirmCloseIfDirty`, especially 8976–8977; [MainController.java:8524](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/MainController.java#L8524), `closeTab`; [FileWorkflowCoordinator.java:1501](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/FileWorkflowCoordinator.java#L1501), `awaitSave`; [FileWorkflowCoordinator.java:1456](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/FileWorkflowCoordinator.java#L1456), acknowledgment.
- **Execution path / trigger:** Dirty tab → Close → choose Save → capture text A → nested FX loop waits for disk → keyboard/programmatic edit produces B → disk commits A → save returns true → tab is removed. Window close uses the same approval helper.
- **Expected:** Close only after the current document is durably saved, or ask again if newer edits remain.
- **Actual / cause:** The save acknowledgment correctly leaves B dirty, but `confirmCloseIfDirty` returns the save result without rechecking dirtiness, identity, or version. Probe: `allowed=true dirty=true text=later unsaved edit`, while disk contained `snapshot`.
- **Consequence / data loss:** The subsequent removal disposes the only current buffer. B is lost despite choosing Save. The ordinary save correctness safeguard does not protect close.
- **Concurrency/lifecycle:** The nested event loop explicitly permits the interleaving; no background-thread document mutation is required. Multi-tab/window quit can also invalidate an earlier close approval during later prompts.
- **Existing coverage:** `FileWorkflowSaveLifecycleFxTest.failedSaveFromDirtyCloseKeepsTheLatestTextDirtyAndTheTabOpen` injects an edit during a **failed** save. It does not cover the successful-save version of the same race.
- **Regression:** Hold the document writer after capture, edit on FX, complete successfully, then invoke the real close path. Assert the tab/window stays open or requests another decision and the latest text survives.
- **Fix direction:** Make close approval conditional on a post-save current-buffer check. Revalidate all approved buffers before final window disposal; do not equate “one snapshot saved” with “document safe to close.”

### H02 — LSP DeleteFile silently closes and destroys a dirty buffer

**CONFIRMED BUG — reproduced. Severity: High. Confidence: High. Subsystems: LSP workspace edits, filesystem, document lifecycle.**

- **Locations:** [LspCoordinator.java:2439](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/LspCoordinator.java#L2439), async application; [LspCoordinator.java:2630](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/LspCoordinator.java#L2630), validation and delete finalization; [LspCoordinator.java:2874](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/LspCoordinator.java#L2874), deletion staging; [MainController.java:1652](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/MainController.java#L1652), `onProjectFileDeleted`.
- **Execution path / trigger:** A server edit contains `DeleteFile` for an open dirty file, without a `TextDocumentEdit` for that file. Staging moves the disk file aside; `finishWorkspaceEdit` validates only `mapped.edits()`; `fileDeleted` removes the tab directly; cleanup deletes the staged disk copy.
- **Expected:** Include every affected resource's open buffers in preflight. Preserve or explicitly resolve unsaved data before deleting/closing.
- **Actual / cause:** Resource targets are not part of the dirty/editability validation set. The production callback reuses a Project-tree deletion completion hook that assumes protection already occurred. The LSP caller bypasses `ProjectDeleteCoordinator`'s protection. Probe: `accepted=true exists=false disposed=true` for a buffer containing `ONLY UNSAVED COPY`.
- **Consequence / data loss:** Both the file and the only unsaved buffer copy can disappear without a save/discard prompt or durable capture of the unsaved text.
- **Concurrency/lifecycle:** Applies even without a race. Edits made during resource staging are also invisible when the resource has no text-edit entry. Directory deletion additionally needs descendant-buffer enumeration.
- **Existing coverage:** `LspWorkspaceEditFxTest.createAndDeleteResourceOperationsApply` verifies filesystem success. Its `FakeOps.fileDeleted` only records a path; it never removes a real tab. Staging-interference tests put the affected buffer in the text-edit list.
- **Regression:** Use `FxWindowFixture` with a dirty open file and a resource-only delete, including recursive directory deletion and another-window buffer. Assert no silent disposal and recoverable content on refusal/failure.
- **Fix direction:** Share destructive-resource preflight with Project deletion, but validate all targets and descendants before staging. Preserve explicit delete semantics while protecting unsaved text.

### H03 — Applying a degraded/binary diff can replace source data with display metadata

**CONFIRMED BUG — metadata case reproduced. Severity: High. Confidence: High. Subsystems: diff, core document, binary handling.**

- **Locations:** [DiffService.java:52](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/diff/DiffService.java#L52), degradation choice; [DiffEngine.java:214](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/diff/DiffEngine.java#L214), `metadataOnly`; [DiffViewerPane.java:392](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/DiffViewerPane.java#L392), editable controls; [DiffViewerPane.java:2022](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/DiffViewerPane.java#L2022) and [DiffViewerPane.java:2081](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/DiffViewerPane.java#L2081), reconstruction; [DiffCoordinator.java:1238](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/DiffCoordinator.java#L1238), binary surrogate acquisition.
- **Execution path / trigger:** Compare an editable local file with more than 120,000 lines. The model becomes one modified row containing descriptive metadata. Click its hunk/line arrow. For binary comparisons, `BinaryDiff.describe` likewise becomes ordinary side text without a non-applicable type marker.
- **Expected:** Metadata-only and binary display content must never enter text-application paths.
- **Actual / cause:** `setEditable` and `computeAppliedFor` do not gate on `Quality.METADATA_ONLY` or binary origin. Reconstruction uses rendered rows as if they were document lines. Probe: a 480,004-character document produced replacement `Binary/large content: 480004 chars, 120001 lines\n`.
- **Consequence / data loss:** One apply replaces the whole buffer with a summary; Save persists truncation. A closed binary file can be opened through the background text loader and replaced with the binary description. Undo availability depends on buffer size/mode and cannot be the safety boundary.
- **Concurrency/lifecycle:** No race needed; baseline validation succeeds because the original snapshot is still current. This demonstrates that freshness alone does not establish that a model is suitable for mutation.
- **Existing coverage:** `DiffViewerBehaviorFxTest.largeDiffsDegradeThroughLineOnlyAndMetadataModes` asserts only the quality enum. `BinaryDiffTest` tests sniffing/descriptions, not editable integration.
- **Regression:** Build the actual coordinator/view for >120,000 lines and a binary target. Invoke every line/hunk/Git/Result action and assert none substitutes metadata or enables an unsupported text operation.
- **Fix direction:** Carry typed content/source fidelity through the pipeline and make apply capability explicit. Render-only models must be non-mutating; reconstruction should operate on original snapshots and ranges, not presentation rows.

### H04 — Git file actions treat literal filenames as wildcard/pathspec expressions

**CONFIRMED BUG — reproduced with native Git. Severity: High. Confidence: High. Subsystems: Git stage/discard, filesystem.**

- **Locations:** [GitCoordinator.java:509](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/GitCoordinator.java#L509), stage/unstage argv; [GitCoordinator.java:572](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/GitCoordinator.java#L572), discard commands; [GitService.java:85](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/git/GitService.java#L85), command construction; [GitCoordinator.java:1011](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/GitCoordinator.java#L1011), literal invalidation matching.
- **Execution path / trigger:** Create an untracked file literally named `a*.txt` beside `alpha.txt` and `another.txt`. Select only `a*.txt` and confirm Discard. The command is `git clean -f -- a*.txt`.
- **Expected:** Operate on exactly the selected path(s).
- **Actual / cause:** `--` terminates options but does not disable Git pathspec glob/magic interpretation. No `--literal-pathspecs` or per-path literal prefix is applied. Native probe removed **all three files**. `add`, `reset ... --`, and tracked `checkout --` share this issue.
- **Consequence / data loss:** Discard can delete unrelated untracked files, which Git cannot recover. Stage can accidentally include unintended files; tracked discard can overwrite unrelated changes.
- **Concurrency/lifecycle:** No race needed. Pending-write invalidation uses literal/prefix comparisons and therefore may not even invalidate all files selected by Git's broader interpretation.
- **Existing coverage:** `GitDestructiveOperationsFxTest.mixedDiscardPreservesTheIndexAndHandlesLiteralPathNames` covers dash, spaces, and newline names, not wildcard or `:(...)` names.
- **Regression:** Test literal `*`, `?`, bracket, and pathspec-magic names in stage, unstage, tracked discard, and untracked deletion. Assert adjacent matching files and their pending saves remain untouched.
- **Fix direction:** Use Git's literal-pathspec facility for every UI-selected filesystem path, distinguishing those paths from intentionally user-entered pathspec expressions.

### H05 — Stale Git hunk staging can modify a different repeated block

**CONFIRMED BUG — reproduced. Severity: High. Confidence: High. Subsystems: diff, Git index.**

- **Locations:** [DiffCoordinator.java:1115](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/DiffCoordinator.java#L1115), Git hunk configuration; [GitService.java:793](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/git/GitService.java#L793), `applyPatch`; [PatchWriter.java:23](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/diff/PatchWriter.java#L23), three-line context.
- **Execution path / trigger:** Open a diff whose index contains two identical contextual blocks. Build a stage patch for the first. Another Git operation removes the first block from the index. Apply the previously displayed hunk.
- **Expected:** Reject a patch whose intended index preimage/target changed, or explicitly refresh/rebase it against verified identity.
- **Actual / cause:** `git apply --check` checks applicability, not snapshot identity. Git can relocate a hunk to matching context at another offset. Probe: both check and apply returned 0, and the **second** remaining `old` block became `new`.
- **Consequence / data loss:** The index contains an edit to the wrong occurrence and can be committed as if the selected hunk was staged. Unstage has the same preimage ambiguity. Working-buffer undo does not undo index mutations.
- **Concurrency/lifecycle:** The serial worker only separates this window's commands; external Git and other windows can change the index between display and action. The defect exists even when check and apply themselves run without interleaving.
- **Existing coverage:** `GitPatchApplyFxTest` covers a stale patch that cannot apply. It does not cover a stale patch that still matches another region.
- **Regression:** Generate a real patch through `PatchWriter`, mutate the index to remove the intended repeated block, and assert staging refuses despite native `git apply --check` accepting the patch.
- **Fix direction:** Capture the target index blob identity and mode when building the diff; validate them as part of the index update transaction. Reject or recompute on mismatch. Account for external Git changing the index between a separate validation command and apply; a per-window executor alone cannot close that gap. Patch applicability alone is insufficient.

### H06 — Explicit local Save overwrites external/new-branch/other-window data without a conflict check

**CONFIRMED BUG — direct execution path. Severity: High. Confidence: High. Subsystems: core save, Git, external files, multi-window.**

- **Locations:** [FileWorkflowCoordinator.java:1338](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/FileWorkflowCoordinator.java#L1338), `writeAsync`, especially the `autoSave && !remote` guard; [FileWorkflowCoordinator.java:1225](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/FileWorkflowCoordinator.java#L1225), local write; [FileWorkflowCoordinator.java:657](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/FileWorkflowCoordinator.java#L657), focus/tab-only external check; [MainController.java:7401](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/MainController.java#L7401), dirty-buffer reload exclusion; [MainController.java:2027](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/MainController.java#L2027), watcher callback.
- **Execution path / trigger:** Keep a dirty local buffer open. An external tool or Git checkout changes its file while the window retains focus. Press Save, or save an older copy in another window. Checkout deliberately preserves dirty buffers, making this a normal cross-system sequence.
- **Expected:** Detect that the local disk preimage changed and resolve the conflict before replacing it.
- **Actual / cause:** Local conflict detection inside the writer runs only for autosave. Explicit Save calls `writeIf` with only a ticket-current predicate. Watcher refreshes Git/build/diff state but does not reconcile the active document. Focus/tab prompts are not a save-time precondition.
- **Consequence / data loss:** New branch contents, another editor's changes, or another window's newer save are silently replaced by the stale buffer. The shared sequencer orders writes but cannot distinguish a newer stale save from a valid one.
- **Concurrency/lifecycle:** Autosave also checks local metadata before staging rather than using an expected-byte commit check, leaving a concrete staging-window race. Two-window buffers do not share a saved-content baseline.
- **Existing coverage:** Save ordering tests exercise ticket order and own-commit acknowledgment; Git destructive tests assert dirty text survives checkout. Neither follows that checkout with a conflicting explicit Save. Remote conflict tests cover a stronger path that locals do not use.
- **Regression:** After checkout changes a dirty file, Save without focus change and assert conflict handling. Repeat with two windows and with an external write while atomic staging is paused.
- **Fix direction:** Use a saved disk preimage/content identity for local and remote saves, and reconcile app-wide open-buffer owners. Carry an explicit “overwrite the observed external version” decision to the commit boundary.

### H07 — Git Log checkout/reset/revert/cherry-pick bypass the protected working-tree mutation path

**CONFIRMED BUG — code traced. Severity: High. Confidence: High. Subsystems: Git history, saves, editor reload, LSP.**

- **Locations:** [GitWindowCoordinator.java:279](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/GitWindowCoordinator.java#L279), history actions; [GitWindowCoordinator.java:392](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/GitWindowCoordinator.java#L392), `gitMutate`; compare [GitCoordinator.java:652](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/GitCoordinator.java#L652), branch checkout protections.
- **Execution path / trigger:** Use Git Log checkout or hard reset with open files and a queued save. `gitMutate` submits the command and later refreshes Git/log state. It neither invalidates document writes nor reloads changed clean buffers. Reset schedules `checkExternalChanges` immediately after submission, before completion; new-branch-at-commit similarly queues its reload too early.
- **Expected:** Every command that changes the working tree must coordinate pending writes and reconcile buffers after the command has finished, including partial failure.
- **Actual / cause:** History mutations duplicate command execution without using `checkoutBranch`/stash's invalidation/reload path. The save executor can write an old snapshot after reset, undoing part of the repository operation; clean buffers can continue showing the old revision.
- **Consequence / data loss:** Reset results can be silently overwritten, and subsequent editing/saving can restore old branch content. LSP still sees the old open-document text while repository/disk state has advanced.
- **Concurrency/lifecycle:** Two independent executors are involved. An FX `runLater` after submitting Git is not a completion barrier for the Git worker.
- **Existing coverage:** `GitDestructiveOperationsFxTest` tests branch checkout and stash/discard. `GitLogHistoryMergeFxTest` tests history comparison selection, not mutation coordination.
- **Regression:** Queue a staged save, execute real Git Log hard reset/checkout through the production action, release the save, and verify disk/buffer/LSP coherence. Hold Git execution so the test proves reload occurs after completion.
- **Fix direction:** Route all worktree-changing commands through one repository mutation coordinator with declared affected paths, save invalidation/serialization, completion-time reload, and dirty-document conflict handling.

### H08 — A workspace edit against a narrowed buffer writes into the wrong document region

**CONFIRMED BUG — reproduced through the coordinator. Severity: High. Confidence: High. Subsystems: LSP, narrowing, document offsets.**

- **Locations:** [LspCoordinator.java:2630](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/LspCoordinator.java#L2630), preflight; [LspCoordinator.java:2743](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/LspCoordinator.java#L2743), text application; [EditorBuffer.java:9155](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/editor/EditorBuffer.java#L9155), LSP offsets; [EditorBuffer.java:9415](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/editor/EditorBuffer.java#L9415), narrowing; [LspCoordinator.java:1180](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/LspCoordinator.java#L1180), ordinary LSP eligibility.
- **Execution path / trigger:** Request a multi-file edit, then narrow one target before response/application; or receive an edit to an already narrowed target. `getContent()` still returns the complete unchanged file, so an expected-text check passes. The actual CodeArea contains only the visible region.
- **Expected:** Widen before applying whole-document LSP coordinates, or reject the transaction.
- **Actual / cause:** Preflight checks editability, version, and whole text, but not narrowing. `applyLspEdits` addresses `focusedArea` directly. Probe: an edit for line 0 `outside` in `outside\nvisible\ntail\n` instead replaced the narrowed `visible` text; coordinator returned true.
- **Consequence / data loss:** Wrong-region mutation, skipped out-of-range edits, and a falsely successful multi-file refactor. Normal sync eligibility disables LSP while narrowed but does not protect incoming workspace edits initiated elsewhere or earlier.
- **Concurrency/lifecycle:** Narrowing preserves logical whole-file text while changing coordinate space; text equality alone cannot detect this transition. Unversioned JDT edits are especially exposed.
- **Existing coverage:** `NarrowingFxTest` verifies whole-file save/replacement safety, and workspace tests verify versions/snapshots. Neither combines narrowing with LSP application.
- **Regression:** Start with a full-file expected snapshot, narrow an open target, and apply unversioned edits through `applyWorkspaceEditsAsync`. Assert correct full-document coordinates and one undoable transaction, or atomic refusal.
- **Fix direction:** Centralize full-document mutation with an explicit coordinate-space contract. Widen before LSP range resolution, with deliberate undo semantics, or reject narrowed targets before any resource staging.

### H09 — Failed Save As changes file identity and can leave an unsaved destination marked clean

**CONFIRMED BUG — reproduced. Severity: High. Confidence: High. Subsystems: Save As, dirty state, lifecycle.**

- **Locations:** [FileWorkflowCoordinator.java:1018](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/FileWorkflowCoordinator.java#L1018), `applySaveAsTarget`; [FileWorkflowCoordinator.java:1147](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/FileWorkflowCoordinator.java#L1147), asynchronous enqueue result; [FileWorkflowCoordinator.java:1387](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/FileWorkflowCoordinator.java#L1387), failure callback; [MainController.java:8959](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/MainController.java#L8959), close approval.
- **Execution path / trigger:** A clean buffer is saved to a new filename. `setPath` and new EditorConfig rules are applied before the write is attempted. The write fails, for example on permissions/disk error.
- **Expected:** Retain the old committed identity, or keep the new destination explicitly unsaved/dirty and recoverable until a write succeeds.
- **Actual / cause:** Failure only reports status. It does not restore the old path/rules or mark the new target unsaved. Probe: `path=.../failed.txt dirty=false exists=false` after a failed Save As.
- **Consequence / data loss:** Close can discard the tab without a prompt and the intended new file never exists. With a previously saved source, the old content may still be recoverable there, but the current tab/session falsely claims the new identity. Untitled clean/template buffers can lose the only copy.
- **Concurrency/lifecycle:** The asynchronous API returns true for enqueueing, while UI identity changes immediately. Failure and tab close are separated in time.
- **Existing coverage:** `FileWorkflowSaveLifecycleFxTest.saveAsCommitIsAlsoPendingCloseStateUntilAcknowledged` covers unresolved success, not failed clean Save As. Save-ordering tests focus on superseding destinations.
- **Regression:** Start from clean existing and clean untitled buffers; fail the destination writer. Assert original identity/rules are retained or new identity remains dirty, and closing cannot silently drop the content.
- **Fix direction:** Treat Save As as an identity transaction with a pending target. Commit identity and dependent LSP/settings state only on success, or maintain an explicit unsaved-target state with rollback on failure.

### H10 — Closing diff/merge tabs or the window discards dirty Result drafts without a prompt

**CONFIRMED BUG — code traced. Severity: High. Confidence: High. Subsystems: diff/merge Result editors, tab/window lifecycle.**

- **Locations:** [DiffViewerPane.java:1093](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/DiffViewerPane.java#L1093), draft dirty tracking; [MainController.java:8042](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/MainController.java#L8042), tab construction; [MainController.java:8933](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/MainController.java#L8933), non-buffer close acceptance; [MainController.java:9107](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/MainController.java#L9107), window close iteration; [MergeViewerPane.java:112](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/MergeViewerPane.java#L112), editable Result.
- **Execution path / trigger:** Type custom code in a diff Result or merge Result without applying it. Close its tab, Close All, or quit the window.
- **Expected:** Warn about unapplied edits or persist/recover the draft.
- **Actual / cause:** Close protection only recognizes `EditorBuffer`. Diff/merge content returns null from `bufferOf`, so single-tab close approves immediately and window close skips it. The draft's `resultDirty` is private to the view.
- **Consequence / data loss:** Manual resolution/draft text disappears; it was never in the real buffer, disk, or document history.
- **Concurrency/lifecycle:** No race needed. Pending draft recomputations do not confer document ownership or durable recovery. Nested review panes also need draft aggregation.
- **Existing coverage:** `DiffViewerBehaviorFxTest` covers dirty drafts blocking swap; `DiffEditableResultIntegrationFxTest` covers application/staleness. Neither exercises dirty draft closure. Merge tests apply text before ending.
- **Regression:** Edit drafts in standalone diff, multi-file review, and merge tabs; drive tab close, Close All, and window quit; assert protection and cancellation preserve drafts.
- **Fix direction:** Give every editable `TabContent` a lifecycle/unsaved-state contract and aggregate nested drafts. Integrate draft recovery and explicit Apply/Discard/Cancel decisions.

### H11 — LSP overwrite-rename produces two independent open buffers for one destination

**CONFIRMED BUG — reproduced. Severity: High. Confidence: High. Subsystems: LSP resources, path identity, editor/LSP routing.**

- **Locations:** [LspCoordinator.java:2959](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/LspCoordinator.java#L2959), overwrite staging; [LspCoordinator.java:2664](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/LspCoordinator.java#L2664), rename finalization; [MainController.java:1585](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/MainController.java#L1585), remap; [LspManager.java:224](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/lsp/LspManager.java#L224), URI session mapping.
- **Execution path / trigger:** A.java and B.java are both open; B has unsaved edits. Apply server `RenameFile(A,B,overwrite=true)` with no text edit to B.
- **Expected:** Resolve the destination's live-buffer ownership and unsaved text before replacing its disk identity; one coherent document must own B afterward.
- **Actual / cause:** Staging backs up/replaces disk B, but buffer preflight does not consider the destination unless it has a text-edit entry. `onProjectFileRenamed` only changes A's path. Probe returned success with both buffers pointing at B: A text `class A {}`, B text `UNSAVED B`, B still dirty.
- **Consequence / data loss:** Either tab can overwrite the other's content. Canonical lookup can select the wrong buffer, and closing one can close the URI used by the other. The old disk backup is deleted on commit even while the old B tab remains.
- **Concurrency/lifecycle:** This is identity aliasing without concurrency; normal later saves, diagnostics, and closes turn it into cross-component corruption. Create-overwrite has a related stale-open-buffer problem because `fileCreated` only refreshes the tree.
- **Existing coverage:** `LspWorkspaceEditFxTest.anOverwritingRenameIsAllowedWhenTheServerSaysSo` checks disk behavior through fake rename callbacks, not two live tabs or dirty destination ownership.
- **Regression:** Use the real controller with open source and dirty destination, then exercise save/close/diagnostics after overwrite. Include create-overwrite of an already open file.
- **Fix direction:** Preflight all source/destination owners, including other windows. Refuse or explicitly reconcile overwrite collisions, then atomically remap a single document identity and its LSP registration.

### H12 — Closed-file LSP edit snapshots are captured after the stale edit has already been computed

**HIGH-CONFIDENCE BUG. Severity: High. Confidence: High. Subsystems: LSP multi-file edits, disk changes, unopened documents.**

- **Locations:** [LspManager.java:384](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/lsp/LspManager.java#L384), `expectedDocuments`; [LspManager.java:400](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/lsp/LspManager.java#L400), `mapWorkspaceEdit`; [LspManager.java:1402](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/lsp/LspManager.java#L1402), rename snapshot/request; [LspCoordinator.java:2653](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/LspCoordinator.java#L2653), expected-text validation.
- **Execution path / trigger:** A rename/code action computes edits for closed B from its old disk contents. While the request is in flight, an external editor/Git operation changes B. When the response arrives, the manager reads B's **new** contents and labels them the expected text for the **old** edit ranges. The background loader reads the same new contents and validation passes.
- **Expected:** Validate against the revision from which the server derived those edits; refuse unknown/stale preimages.
- **Actual / cause:** Request-time snapshots include only documents open in that session. Missing paths are snapshotted at response-mapping time with `Files.readString`, which establishes no relationship to the server's computed ranges. Strong code evidence; an actual delayed JDT response against a concurrently changed closed target remains to be reproduced.
- **Consequence / data loss:** A multi-file refactor can modify the wrong range of a newly changed file and report success. Even a versioned edit may lack a meaningful managed version for a closed file.
- **Concurrency/lifecycle:** Other-window documents and files served by another session are also outside the snapshot set. The fallback decoder is UTF-8, unlike normal BOM/EditorConfig loading: UTF-8 BOM targets can be wrongly rejected, and unsupported decoding can leave `expectedText` absent.
- **Existing coverage:** Workspace tests supply explicit expected strings for open fake buffers; manager tests cover known open-document versions. They do not change an unopened target between server computation and mapping.
- **Regression:** Delay a rename response for unopened B, rewrite B before completion, and verify refusal. Add UTF-8 BOM/Latin-1 targets and a dirty B in another window/session.
- **Fix direction:** Track workspace file revisions/change epochs across the request, retain exact decoded/byte preimages where available, and reject unverified targets. Use the same decoding boundary as the editor. Do not call a response-time read a request snapshot.

### H13 — Choosing another merge resolution erases manual Result edits

**CONFIRMED BUG — code traced. Severity: High. Confidence: High. Subsystems: merge Result editor, undo/data preservation.**

- **Locations:** [MergeViewerPane.java:187](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/MergeViewerPane.java#L187), `choose`; [MergeViewerPane.java:194](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/MergeViewerPane.java#L194), `refreshResult`, ending in `resultArea.setText`.
- **Execution path / trigger:** Resolve one conflict, manually refine the Result text, then choose Ours/Theirs/Base/Both on a second conflict.
- **Expected:** Change only the selected conflict in the current Result or warn before replacing custom edits.
- **Actual / cause:** Every choice regenerates the entire Result from original conflict segments plus the choice list. Manual edits are not represented there. `setText` replaces the Result wholesale.
- **Consequence / data loss:** Custom resolution work disappears, including edits outside the selected conflict. It has not yet reached the real buffer or its undo/history.
- **Concurrency/lifecycle:** No race needed; two independently editable representations have no reconciliation protocol.
- **Existing coverage:** `MergeViewerPaneFxTest.choiceUpdatesEditableResultAndSaveAppliesItsExactText` chooses first and manually edits afterward. It does not make another choice after manual editing.
- **Regression:** With two conflicts, choose the first, edit Result both inside and outside a conflict, then choose the second. Assert custom text survives or the user can cancel a replacement.
- **Fix direction:** Apply conflict choices as tracked ranges in the current Result, or lock/reconcile choice actions once manual edits diverge. Preserve undo and draft lifecycle protection.

## Medium

### M01 — Normal file rename and Save As leave the old LSP URI registered

**CONFIRMED BUG — Project rename reproduced. Severity: Medium. Confidence: High. Subsystems: core path changes, LSP lifecycle, EditorConfig.**

- **Locations:** [MainController.java:1585](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/MainController.java#L1585), Project rename; [MainController.java:8720](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/MainController.java#L8720), tab rename; [FileWorkflowCoordinator.java:1018](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/FileWorkflowCoordinator.java#L1018), Save As; [EditorBuffer.java:7606](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/editor/EditorBuffer.java#L7606), `setPath`; [LspCoordinator.java:842](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/LspCoordinator.java#L842), deferred-only `onBufferShown`.
- **Execution path / trigger:** Open a managed Java file and rename it through the Project tree/tab action, or Save As to another path. The buffer path changes; subsequent change callbacks address the new path.
- **Expected:** Close old URI, atomically remap document identity/settings, open the new URI, and clear old diagnostics.
- **Actual / cause:** These ordinary path-change paths do not invoke the LSP old-close/new-open sequence present in LSP resource renames. `setPath` only changes language/grammar/run state. Probe: `old-managed=true new-managed=false`. Tab switching only calls sync for a buffer still in `deferredLsp`.
- **Consequence / data loss:** Completion/change notifications for the new URI no-op until a later sync; the old URI/session can remain retained after the tab closes. Save may eventually open the new URI but leaves the old one behind. Moving between EditorConfig scopes through rename also lacks the Save As re-resolution.
- **Concurrency/lifecycle:** Old responses and new-path callbacks coexist; closing the renamed tab removes only the new path's registration.
- **Existing coverage:** `LspCoordinatorSyncFxTest` tests eligibility/deferred starts; LSP workspace rename tests cover the special path that explicitly closes the old URI. Normal UI rename/Save As is not tested end-to-end with a fake server.
- **Regression:** Assert exact didClose(old)/didOpen(new), subsequent didChange(new), no old routing entry, and correct EditorConfig after all three rename/Save As entry points, including directory moves.
- **Fix direction:** Introduce one path-change transaction hook used by every caller rather than relying on `setPath` and opportunistic later saves.

### M02 — An unchanged incremental sync advances the client version without telling the server

**CONFIRMED BUG — reproduced. Severity: Medium. Confidence: High. Subsystems: LSP versions, diagnostics, workspace edits.**

- **Locations:** [LanguageServerSession.java:703](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/lsp/LanguageServerSession.java#L703), `didChange`; [LanguageServerSession.java:731](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/lsp/LanguageServerSession.java#L731), `changeEventsFor`; [LspManager.java:2673](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/lsp/LspManager.java#L2673), diagnostic version comparison; [LspCoordinator.java:2568](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/LspCoordinator.java#L2568), edit version comparison.
- **Execution path / trigger:** An incremental server has version 1/text A. Hover/navigation/code-action preflush resubmits unchanged A. `versions.merge` increments first; the text diff is empty, so no didChange is sent.
- **Expected:** The recorded protocol version should equal the last version sent on the wire.
- **Actual / cause:** Probe: server didOpen version 1, zero changes sent, client version 2. The next version-1 diagnostics or versioned workspace edit is rejected as stale even though its text is current.
- **Consequence / data loss:** Valid diagnostics disappear or valid refactors fail unpredictably after read-only LSP commands. No direct text loss established.
- **Concurrency/lifecycle:** No concurrent edit is necessary. Frequent explicit preflushes increase divergence until a real change/resync sends a later version.
- **Existing coverage:** `LanguageServerSessionProtocolTest` asserts identical text sends no event and separately asserts increasing versions, but does not combine those contracts. `LspManagerRequestsFxTest.staleVersionedPushDiagnosticsAreDropped` changes actual text first.
- **Regression:** Send identical text repeatedly, then publish diagnostics and a workspace edit at the last wire version. Assert version stability and acceptance.
- **Fix direction:** Increment the wire version only when emitting a change, keeping startup collapse, shadows, and version updates in the same ordered operation.

### M03 — Save notifications can precede document synchronization; autosave and includeText are incomplete

**CONFIRMED BUG — includeText omission reproduced; ordering traced. Severity: Medium. Confidence: High. Subsystems: save, LSP didChange/didSave, EditorConfig.**

- **Locations:** [FileWorkflowCoordinator.java:1401](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/FileWorkflowCoordinator.java#L1401), completion side effects; [LspCoordinator.java:1426](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/LspCoordinator.java#L1426), `notifyDocumentSaved`; [LspCoordinator.java:1180](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/LspCoordinator.java#L1180), `syncBuffer`; [LanguageServerSession.java:773](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/lsp/LanguageServerSession.java#L773), `didSave`; [FileWorkflowCoordinator.java:1116](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/FileWorkflowCoordinator.java#L1116), save transforms.
- **Execution path / trigger:** Type and immediately save; disk I/O completes before the 300 ms LSP debounce. Alternatively enable autosave or use a server requesting save text.
- **Expected:** Synchronize the relevant document state before didSave, honor negotiated save options, and notify real autosaves. Persisted/transformed text should have an explicit consistent relationship to the live server document.
- **Actual / cause:** `syncBuffer` does not send current text for an already managed URI. Completion calls didSave directly; autosave skips the entire LSP notification block. Session didSave never supplies text. Probe with `SaveOptions(true)` recorded `text=null`. Save transforms affect bytes only, so a “clean” buffer/server can differ from disk after whitespace/EOL transformations.
- **Consequence / data loss:** Save-triggered validation/build/code analysis may run against stale contents or never run under autosave. Diagnostics and on-save behavior can disagree with the saved file; no direct data loss claimed for the notification omission itself.
- **Concurrency/lifecycle:** Ordering depends on debounce versus disk completion. If further edits arrive while saving, the code must distinguish the committed snapshot from the newer unsaved document rather than announcing arbitrary current text as saved.
- **Existing coverage:** Protocol tests check didSave URI, not includeText/order. Save lifecycle tests assert bytes/dirty state, not the LSP wire transcript.
- **Regression:** Record events for immediate save, delayed save with a subsequent edit, autosave, `includeText=true`, and EditorConfig transforms. Assert a defined didChange/didSave order and correct text semantics.
- **Fix direction:** Give the save-completion API the committed snapshot and document identity. Centralize negotiated save synchronization; make autosave and explicit Save use the same protocol contract.

### M04 — Diagnostics and navigation callbacks lack consistent delivery-time freshness guards

**CONFIRMED BUG — code traced. Severity: Medium. Confidence: High. Subsystems: LSP diagnostics, hover/navigation, lifecycle.**

- **Locations:** [LspManager.java:2617](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/lsp/LspManager.java#L2617), pull diagnostics; [LspManager.java:2668](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/lsp/LspManager.java#L2668), push diagnostics; [LspCoordinator.java:565](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/LspCoordinator.java#L565), UI application; [LspCoordinator.java:1853](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/LspCoordinator.java#L1853), definition; [LspCoordinator.java:2165](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/LspCoordinator.java#L2165), hover; [LspCoordinator.java:3159](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/LspCoordinator.java#L3159), signature help.
- **Execution path / trigger:** Response A is accepted/queued; the document changes, closes/reopens, or changes server before the FX runnable executes. Or two pull/hover/navigation requests complete in reverse order.
- **Expected:** Validate document identity, local version, session epoch, and latest-request token when applying on FX.
- **Actual / cause:** Push version validation occurs before `runLater`; pull diagnostics have no version/generation check. Pushed diagnostics can also bypass version comparison when a canonical server URI does not find the as-opened session key. Definition/hover callbacks lack origin-version/request guards; signature help checks only active buffer identity. The coordinator can therefore reinstall stale ranges or execute an old jump after newer activity.
- **Consequence / data loss:** Wrong squiggles, misleading Problems, obsolete hover/signatures, unexpected navigation/tab reopening. No direct text loss asserted for these read-only responses.
- **Concurrency/lifecycle:** Clearing diagnostics on edit or shutdown does not prevent already queued old callbacks from repopulating them. Protocol version also lags immediate local edits during debounce.
- **Existing coverage:** Manager tests reject an already-stale push before queuing; navigation tests cover normal jumps and stale **formatting**, not reverse-order read responses or a version change after queueing. Canonical-key tests do not combine identity with version checks.
- **Regression:** Hold FX delivery, enqueue A, edit/reopen/restart, then release it. Reverse two pull/definition/hover futures. Verify only results for the current request/document/session survive.
- **Fix direction:** Carry origin identity/local generation/session epoch through every callback and validate on FX immediately before use. Cancel obsolete futures where supported, but retain guards because cancellation is not sufficient.

### M05 — Find/Replace and diff application mutate buffers marked read-only

**CONFIRMED BUG — Find/Replace reproduced. Severity: Medium. Confidence: High. Subsystems: core editability, search, diff/merge.**

- **Locations:** [FindReplaceBar.java:497](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/FindReplaceBar.java#L497) and [FindReplaceBar.java:543](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/FindReplaceBar.java#L543); [DiffCoordinator.java:364](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/DiffCoordinator.java#L364), `applyToLocal`; [EditorBuffer.java:9493](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/editor/EditorBuffer.java#L9493), unrestricted whole-document replacement; [DiffCoordinator.java:1365](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/DiffCoordinator.java#L1365), merge application.
- **Execution path / trigger:** Put a buffer in View mode, then Replace All from the find bar, or apply a diff/history/merge result targeting it. A loading shell is similarly not protected by the generic diff replacement boundary.
- **Expected:** User-facing mutation commands respect the buffer's editability and loading/truncation state.
- **Actual / cause:** JavaFX `setEditable(false)` blocks normal input, not programmatic replacements. These callers omit `isEditable`; `replaceWholeDocument` itself is intentionally unrestricted. Probe: `editable=false text=bar` after replacing read-only `foo`.
- **Consequence / data loss:** A protected document becomes dirty or is overwritten despite its read-only UI. Applying to a loading buffer can be lost when the load completes. Truncated-save refusal remains a separate safeguard and should not be mistaken for edit protection.
- **Concurrency/lifecycle:** Mode/loading state may change after a diff opens; the mutation boundary must recheck it at application time.
- **Existing coverage:** LSP workspace tests reject read-only buffers; search-in-files does too. Find bar tests and diff Result integration tests use writable buffers.
- **Regression:** Exercise single/all replacement, local diff apply, history restore, and merge apply against View mode, loading, truncated, and disposed buffers. Assert refusal leaves text/undo untouched.
- **Fix direction:** Use an explicit user-edit mutation gateway with editability/lifecycle checks, while keeping separately named internal load/restore operations capable of bypassing those checks when appropriate.

### M06 — Replace Current inserts literal regex group syntax for contextual matches and can replace arbitrary selections

**CONFIRMED BUG — contextual regex reproduced. Severity: Medium. Confidence: High. Subsystems: core search/replace, regex.**

- **Locations:** [FindReplaceBar.java:497](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/FindReplaceBar.java#L497), selected-range replacement; [FindReplaceBar.java:529](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/FindReplaceBar.java#L529), `replacementForSingle`.
- **Execution path / trigger:** Text `foo1`, regex `(?<=foo)(1)`, replacement `$1$1`; select the match and Replace Current. Or select unrelated text manually after entering a query and press Replace Current.
- **Expected:** Replace the current actual match using capture groups evaluated in its original document context; unrelated selections should not be replaced as matches.
- **Actual / cause:** The pattern is rerun only against selected text. Lookbehind/lookahead context is gone, so the code falls back to the literal replacement. Probe output was `foo$1$1`, not `foo11`. The caller also only checks nonempty selection/in-scope, not membership in the match set.
- **Consequence / data loss:** Incorrect replacement text or deletion of an unrelated selection; recoverable by undo until saved/closed. Single Replace and Replace All disagree for valid regexes.
- **Concurrency/lifecycle:** Selection and document can change independently of the previously computed match list; the method does not validate the selected match against current full text. No async race is needed for the regex failure.
- **Existing coverage:** `FindReplaceBarFxTest` checks simple capture groups without surrounding context. Invalid-group tests primarily cover Replace All. No contextual single-match or unrelated-selection test exists in that class.
- **Regression:** Test lookbehind/lookahead, anchors, named groups, zero-width matches, and manually changed selections. Compare single replacement semantics with the corresponding full-text matcher result.
- **Fix direction:** Locate the selected match in a matcher over the original document and expand its groups there. Refuse or navigate when the selection is not the current match; use the existing regex execution budget.

### M07 — Unmerged Git entries are omitted, allowing a conflicted repository to look clean

**CONFIRMED BUG — parser reproduced. Severity: Medium. Confidence: High. Subsystems: Git status, conflicts, review UI.**

- **Locations:** [GitService.java:184](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/git/GitService.java#L184), porcelain-v2 status command; [StatusParser.java:43](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/git/StatusParser.java#L43), entry switch; [GitStatus.java:24](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/git/GitStatus.java#L24), `isClean`; [DiffCoordinator.java:1097](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/DiffCoordinator.java#L1097), review targets.
- **Execution path / trigger:** Merge/rebase/cherry-pick leaves a `u UU ... path` porcelain-v2 record, with no other changed entries.
- **Expected:** Display the conflicted path and distinguish it from ordinary staged/unstaged changes.
- **Actual / cause:** The parser handles `#`, `1`, `2`, and `?`; `u` falls through to ignore. Probe parsed a conflicted status as `files=[]`, making `isClean()` true. Conflict files are also absent from review-target selection.
- **Consequence / data loss:** The Commit tree/review UI hides the file that needs resolution and can suggest a clean state. Git itself still refuses an invalid commit; this is not a claim that Git commits unresolved entries.
- **Concurrency/lifecycle:** The defect persists on every refresh; better refresh timing cannot correct it.
- **Existing coverage:** `StatusParserTest` covers ordinary, rename, untracked, quoted, and ignored entries, but no porcelain-v2 `u` records.
- **Regression:** Generate actual merge conflicts for both-modified/add-add/delete-modify, feed native porcelain output through the parser, and verify visible conflict state and review/merge entry points.
- **Fix direction:** Model and parse unmerged entries explicitly; carry conflict status through Git file coloring, panels, and review actions rather than pretending it is an ordinary staged pair.

### M08 — Git refresh freshness is checked before queuing, and context-clearing does not invalidate in-flight work

**CONFIRMED BUG — code traced. Severity: Medium. Confidence: High. Subsystems: Git status/gutters, tab/project lifecycle.**

- **Locations:** [GitService.java:154](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/git/GitService.java#L154), refresh generation; [GitCoordinator.java:237](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/GitCoordinator.java#L237), null/remote context fast paths; [GitCoordinator.java:261](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/GitCoordinator.java#L261), `applyState`; [GitCoordinator.java:304](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/GitCoordinator.java#L304), background-buffer diffs.
- **Execution path / trigger:** A status result passes the worker generation check and queues its FX callback. Before callback delivery, switch tabs/start another refresh or disable Git. Alternatively switch to a no-project Welcome/remote context, which applies NONE without calling `service.refresh`.
- **Expected:** Apply only results matching the current context, active file, settings, and owner lifetime.
- **Actual / cause:** The generation is not checked inside the FX runnable. Fast-path NONE does not increment it. `RepoState` also does not identify its diff file; `applyState` applies its gutters to whichever buffer is active now.
- **Consequence / data loss:** Old repository controls/branch state can reappear, and gutters describe the wrong file. Commands can use an obsolete `repoRoot` while the user sees another context. Direct data loss depends on a later mutation and is not asserted here.
- **Concurrency/lifecycle:** Even one serial Git worker cannot serialize FX context changes. Background gutter callbacks also lack buffer path/disposal checks.
- **Existing coverage:** `GitStateFxTest` tests applying known states; diff-refresh tests capture repository roots correctly for diff fetches. They do not hold FX delivery across a context change/disable.
- **Regression:** Block FX delivery after computation, switch to another file/Welcome/remote tab or disable Git, release A, and assert A cannot repopulate the UI. Include shutdown and a renamed background buffer.
- **Fix direction:** Validate generation/context on FX, invalidate on every context-clear/disable/shutdown, and include source file identity in gutter payloads.

### M09 — Patch export/staging omits EOF markers when both files lack a trailing newline

**CONFIRMED BUG — reproduced with native Git. Severity: Medium. Confidence: High. Subsystems: diff patch writer, Git staging/export.**

- **Locations:** [PatchWriter.java:23](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/diff/PatchWriter.java#L23), `unifiedDiff`, especially the `eofDiff` condition at 27–43; [DiffCoordinator.java:1139](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/DiffCoordinator.java#L1139), hunk patch generation.
- **Execution path / trigger:** Index/file contains `old` without a trailing newline; desired content is `new`, also without a trailing newline. Export or stage the change.
- **Expected:** Emit `\ No newline at end of file` for both removed and added final lines.
- **Actual / cause:** Markers are added only when the two final-newline flags **differ**. When both are false, the emitted patch represents newline-terminated lines. Probe: `git apply --cached --check` rejected it with “patch does not apply.”
- **Consequence / data loss:** Valid hunk staging/unstaging/export fails or an exported patch represents incorrect EOF semantics. No direct loss was observed in the rejecting Git case.
- **Concurrency/lifecycle:** Deterministic fidelity bug, independent of freshness.
- **Existing coverage:** `PatchWriterTest` covers newline-only differences and content changes combined with a changed EOF state; not content changes where both EOF flags are false.
- **Regression:** Apply generated patches with real Git for both-no-newline, empty↔nonempty without newline, repeated last lines, and content changes away from EOF. Compare exact bytes.
- **Fix direction:** Determine EOF marker placement from each side's termination state and actual hunk line positions, not merely whether the states differ. Avoid locating final lines by text equality alone.

### M10 — File acquisition crosses the FX boundary in both unsafe directions

**CONFIRMED BUG — code traced. Severity: Medium. Confidence: High. Subsystems: diff, LSP rename/workspace edits, file reload.**

- **Locations:** [DiffCoordinator.java:435](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/DiffCoordinator.java#L435), Git working-side callback; [DiffCoordinator.java:789](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/DiffCoordinator.java#L789), `fileSide`; [DiffCoordinator.java:1216](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/DiffCoordinator.java#L1216), `worktreeText`; [LspCoordinator.java:2396](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/LspCoordinator.java#L2396), synchronous rename application; [LspManager.java:400](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/lsp/LspManager.java#L400), disk fallback; [FileWorkflowCoordinator.java:706](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/FileWorkflowCoordinator.java#L706), synchronous reload.
- **Execution path / trigger:** Diff a closed file against HEAD, select a directory comparison entry, perform a multi-file symbol rename, or reload a slow/network-mounted local file.
- **Expected:** Snapshot live buffers on FX; read/decode disk and stage resources off FX; apply guarded immutable results on FX.
- **Actual / cause:** Common Git diffs call `worktreeText` from FX, which performs unbounded `Files.readAllBytes` and decoding for closed files. Conversely `fileSide` invokes `worktreeText` on its worker, where it can access controller tabs and `b.text()`/RichTextFX state. Symbol rename calls `applyWorkspaceEdits` synchronously instead of the background production handler; manager mapping can read closed files on FX in code-action/server-apply flows. Reload also reads synchronously.
- **Consequence / data loss:** UI stalls on filesystem latency/large files; concurrent off-FX reads of UI/document state have undefined consistency and can fail or capture mismatched state. No deterministic text corruption is claimed solely from the off-thread read.
- **Concurrency/lifecycle:** The same helper chooses between UI and disk dynamically, so merely moving its caller to an executor cannot make it safe. The documented asynchronous rename contract does not match the user rename entry point.
- **Existing coverage:** `DiffCharsetFxTest` uses small files; directory tests verify results, not thread ownership. `productionApplyPathRunsResourceTransactionAsynchronously` invokes the async method directly, not the symbol rename command that bypasses it.
- **Regression:** Inject slow reads and assert FX barriers remain responsive; assert buffer lookup/snapshot methods run on FX. Trigger actual rename UI with a controlled executor and verify staging never runs on FX.
- **Fix direction:** Split acquisition into an FX snapshot phase and an I/O phase, returning a typed result with identity/error/fidelity. Route rename through the same asynchronous transaction API as code actions.

### M11 — A code action executes its trailing command even when its edit failed

**CONFIRMED BUG — code traced. Severity: Medium. Confidence: High. Subsystems: LSP code-action transactions.**

- **Locations:** [LspManager.java:2319](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/lsp/LspManager.java#L2319), `finishCodeAction`; [LspManager.java:2334](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/lsp/LspManager.java#L2334), `finishCodeActionAfterEdit`.
- **Execution path / trigger:** A code action has both a workspace edit and a command. The edit is refused because a target is stale/read-only/unavailable or a resource transaction fails. Completion enters `finishCodeActionAfterEdit(..., editOk=false)`.
- **Expected:** Do not execute a command dependent on edits that were not applied.
- **Actual / cause:** The method enters the command branch whenever a command exists; it only combines `editApplied && commandOk` when reporting the eventual result. Reporting false does not undo the already executed command.
- **Consequence / data loss:** Server/project state or follow-up edits can change despite the action reporting failure. Exact side effects depend on the server command; no particular JDT destructive command was reproduced.
- **Concurrency/lifecycle:** Failure of the first step is not an abort boundary for the multi-step operation. Retrying can repeat a command whose first execution already had side effects.
- **Existing coverage:** Workspace tests validate rollback/refusal, and fake-server tests validate execute-command requests. The failure-then-trailing-command sequence is not covered by the selected tests.
- **Regression:** Return false from the workspace edit handler for an action containing a recorded command. Assert no executeCommand call occurs and failure is returned once. Add a resource rollback case.
- **Fix direction:** Return failure immediately when a present edit fails. Execute dependent commands only after the edit transaction commits successfully.

### M12 — Palette and context-menu hunk actions bypass dirty Result protection

**CONFIRMED BUG — command dispatch/reconstruction reproduced. Severity: Medium. Confidence: High. Subsystems: diff Result draft, Git/revert commands.**

- **Locations:** [DiffViewerPane.java:1165](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/DiffViewerPane.java#L1165), disabled toolbar controls; [DiffViewerPane.java:1014](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/DiffViewerPane.java#L1014), palette entry points; [WindowCommandRegistrar.java:2007](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/WindowCommandRegistrar.java#L2007), command registration; [DiffViewerPane.java:1482](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/DiffViewerPane.java#L1482), context menu; [DiffViewerPane.java:1548](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/DiffViewerPane.java#L1548), `performGitAction`; [DiffCoordinator.java:1121](https://github.com/adriandeleon/Editora/blob/85a60dc5568481b4736b60448b8750d8f0033185/src/main/java/com/editora/ui/DiffCoordinator.java#L1121), Git/revert consumer.
- **Execution path / trigger:** Open Result in an unstaged Git diff, type a draft, let its comparison recompute, then invoke Revert Hunk from the palette or the comparison's context menu. Stage/Unstage commands similarly remain callable while their toolbar buttons are disabled.
- **Expected:** While a draft owns editing, competing mutations are blocked or explicitly act inside that draft, without committing unrelated draft edits to the real document/index.
- **Actual / cause:** `updateResultControls` disables toolbar buttons and `showSideBySide` correctly hides gutter arrows, but public command methods and `addGitMenuItem` dispatch without a Result-state check. `performGitAction` only checks row/metadata quality. Reconstruction uses the displayed draft model; Revert's baseline check still compares the original Result baseline with the real document. Probe: `dirty=true toolbar-disabled=true baseline-matches=true`; reverting the first hunk produced `baseA\nsame\ndraftB\n`, carrying an unrelated second-hunk draft into the proposed live replacement.
- **Consequence / data loss:** Reverting one hunk can commit other unapplied draft changes to the live buffer. Stage can publish draft text to the index before Apply Result. Subsequent live-buffer/draft actions can disagree; ordinary buffer undo does not undo an index change.
- **Concurrency/lifecycle:** The relevant text depends on the debounced draft recomputation. Disabling a toolbar button is insufficient when palette and context-menu routes bypass it.
- **Existing coverage:** Editable Result tests check toolbar/side-swap behavior and Apply Result; Git-hunk tests invoke commands without a dirty Result. No test composes those routes.
- **Regression:** With multiple draft changes, invoke palette and context-menu Revert/Stage/Unstage before and after recomputation. Assert no live/index mutation until an explicit supported draft action. Include palette Apply All, which also bypasses its disabled toolbar button.
- **Fix direction:** Enforce mutation ownership in `performGitAction` and other shared mutation handlers, not only button enablement. Preserve the existing gutter guard and make all routes share the same predicate.

## Low

None included. Cosmetic issues and speculative low-impact concerns were excluded.

### Cross-System Failure Scenarios

1. **Immediate Save, then close during ongoing edits — H01/M03.** A is captured and written; B arrives in the nested event loop. B remains dirty, but the close helper returns true and disposes it. LSP can simultaneously receive didSave before A's didChange, or observe B without a corresponding saved version. Fixing only acknowledgment or only LSP order does not prevent loss.
2. **Git branch switch with dirty buffers, then Save — H06/M04.** Git installs another branch's file; the dirty editor remains on the old text. The watcher refreshes repository views but does not resolve the live document. Explicit Save overwrites the new branch file. Old and new diagnostics can cross through the same URI while the buffer/disk disagree.
3. **Git Log hard reset with a staged save — H07/H06.** The history command lacks branch checkout's invalidation, so an old save can commit after reset and restore content from the prior revision. Immediate `runLater` refresh can run before Git changes disk. A later save then compounds the mismatch.
4. **LSP rename over a dirty destination — H11/M01.** Disk B is replaced by A; both tabs still claim B. Canonical lookup, per-URI routing, save sequencing, and close cleanup cannot distinguish the two owners. A save or close in either tab can affect the other owner's effective document.
5. **LSP delete + unsaved document — H02.** A resource-only edit bypasses the text-target checks, then the Project deletion completion hook closes the dirty tab under an assumption of prior consent that is false for this caller. The recoverable disk stage contains only the saved copy, not the unsaved text.
6. **LSP rename + external change to unopened file — H12.** The server computes ranges for B₀; disk becomes B₁; response mapping records B₁ as “expected”; the loader also obtains B₁ and applies B₀'s ranges. All local equality checks succeed while the operation is stale.
7. **Workspace edit + narrowing — H08.** Whole-document identity/text stay equal but the coordinate system changes to a region. The preflight accepts and applies full-file line numbers against region line numbers. This is an interaction that a text-only freshness check cannot detect.
8. **Stale diff + external index change — H05.** The selected occurrence disappears, another repeated occurrence remains, and native Git relocates the patch to it. Passing `--check` is mistaken for proving that the displayed snapshot is current.
9. **Large diff + hunk apply + Save — H03.** A safe-looking degraded presentation remains editable. Baseline freshness succeeds, but the reconstruction input is descriptive metadata. The normal save path then faithfully persists the wrong replacement.
10. **Manual merge work + second conflict choice/close — H10/H13.** Result text exists outside the normal document. A choice regenerates it from the original model; a tab close bypasses dirty-buffer checks. Neither route needs a race to discard the user's resolution.
11. **Two windows editing the same file — H06/H11.** Application-wide tickets serialize committed bytes but do not merge or compare independent buffer baselines. A newer ticket from an older document wins. Existing external-launch deduplication reduces one route to duplicates but ordinary per-window opens and overwrite remaps remain relevant.
12. **Search/replace or programmatic mutation during pending LSP/diff/save work — H01/H08/M04/M05.** Immediate buffer versions help some callbacks, but several consumers check only text, protocol version, active buffer, or nothing. Programmatic changes also bypass keyboard-level read-only enforcement. Multicaret's batch edit notifications do not provide synchronization with any external subsystem by themselves.

### Missing Regression Coverage

The highest-value additions are end-to-end state-transition tests with controlled completion barriers. Existing tests are useful, but many isolate the protected method and replace its dangerous consumer with a recorder.

| Priority | Test family | Required observable invariant |
| --- | --- | --- |
| P0 | Successful save followed by late edit and real close/quit | No current unsaved content is disposed after saving an older snapshot |
| P0 | Resource-only LSP delete/overwrite through real controller callbacks | Dirty owners are preflighted; no duplicate target identity; rollback preserves unsaved and saved copies |
| P0 | Editable metadata/binary diff integration | Presentation surrogates can never become document replacement text |
| P0 | Native Git literal-path tests | Only selected files change, including wildcard/pathspec-magic names |
| P0 | Native Git repeated-context stale hunk | Index identity mismatch rejects even an applicable patch |
| P0 | Every Git mutation with controlled save and Git workers | Post-operation disk, buffer, dirty state, and LSP text agree; no obsolete save commits |
| P0 | Local Save after external/Git/other-window write | Conflict detected at the replacement boundary, without relying on focus changes |
| P0 | Dirty diff/merge drafts on close and subsequent choices | Draft text survives or an explicit decision is required |
| P1 | LSP wire transcript around no-op sync and save | Recorded protocol version equals sent version; save ordering/options are honored |
| P1 | Delivery-time stale-response matrix | Old callbacks cannot update reopened, renamed, changed, disabled, or closed owners |
| P1 | Unopened LSP target changes + charset/BOM | Validation uses the server's preimage and editor's decoder, not a response-time substitute |
| P1 | Narrowed/readonly/loading/disposed mutation targets | Coordinate and mutation eligibility checked at the operation boundary |
| P1 | PatchWriter exact-byte round-trip via Git | All EOF states, empty files, CRLF, repeated final lines, quoted paths |
| P1 | Porcelain-v2 actual conflict output | Conflicted files never disappear or count as clean |
| P1 | Contextual single regex replacement | Same matcher/group semantics as Replace All; arbitrary selections cannot become matches |
| P1 | Thread ownership assertions + slow I/O | UI access occurs only on FX; disk/server work does not block an FX barrier |

Specific limitations observed in the existing suite:

- `LspWorkspaceEditFxTest.FakeOps` records `fileRenamed`/`fileDeleted` without performing real tab remapping/removal. Those assertions cannot detect H02/H11.
- `DiffViewerBehaviorFxTest` asserts degraded quality and independently tests editable rows. It never composes those features, so H03 passes underneath both sets of tests.
- The stale Git patch test chooses a preimage that becomes inapplicable. It cannot detect relocation into another valid block (H05).
- Save failure tests correctly retain late edits after a failed write, but do not ask whether a successful write of an older snapshot authorizes close (H01).
- Version and no-op sync are tested independently. A monotonically increasing internal counter is not sufficient evidence of correct wire synchronization (M02).
- Several manager/navigation tests use immediately completed fake futures. They verify mapping and FX marshaling but cannot expose response inversion or an FX-queue gap.
- Git destructive coverage follows selected branch/stash/discard entry points. It cannot establish a repository-wide guarantee while history actions use a second mutation path (H07).
- Parser/patch tests often inspect generated fields/strings. Exact native Git round-trips are needed for pathspec, conflict, relocation, and EOF contracts.

Useful existing safeguards verified during the review, which should be preserved while fixing the gaps: staged atomic file replacement; byte comparison for bulk replacement/remote save; document-write tickets; post-save dirty acknowledgment of captured content; descending LSP batch edit order; whole-document replacement widening; shared split-view document notifications; completion request version checks; generation-guarded diff refresh; LSP crash restart limits and distinct per-window JDT workspaces; watcher disposal checks. These safeguards are real, but none supplies an implicit transaction spanning all four subsystems.

### Systemic Design Risks

These explain groups of findings rather than adding speculative findings to the count.

1. **A path is not a document owner.** Canonical file keys, URI-string session keys, per-window buffer lists, and path tickets encode different identities. Resource overwrite/Save As/rename are currently mutations of those identities in separate places. A shared document identity registry and explicit identity transitions would address H06/H09/H11/M01 together.
2. **Snapshot persistence is confused with current-state completion.** Saving A does not mean B is saved; an applicable patch does not mean its original target still exists; reading B after a response does not prove the response was computed from B. H01/H05/H12 are different instances of this mistake.
3. **Preflight validates only a subset of an operation.** Text edits get strong checks, resource-only victims do not; branch checkout gets write invalidation, history checkout does not; toolbar buttons get disabled while palette/context-menu actions remain live. Correctness belongs at the shared mutation boundary, not one UI route.
4. **Editable drafts live outside the document lifecycle.** Diff and merge Result editors have text/undo/dirty state but no normal close, persistence, or recovery contract. H10/H13/M12 follow from that split ownership.
5. **Presentation models are accepted as mutation models.** Binary descriptions and metadata rows have the same string/row shape as exact source text. H03 demonstrates why source fidelity must be typed, not inferred from a label or quality badge.
6. **FX marshaling is used without a uniform freshness contract.** `runLater` guarantees thread affinity, not that the initiating state remains current. Different callbacks compare protocol version, local version, text, active-buffer identity, or no token. M02/M04/M08 need a shared request/owner epoch model.
7. **Mutation semantics depend on who calls the low-level editor.** Keyboard read-only enforcement does not protect `replaceText`; whole-file text does not make region-relative LSP coordinates valid. A deliberate user-edit gateway is needed while preserving a separate loader/internal API.
8. **I/O helpers mix live-state selection with disk access.** `worktreeText` can read either JavaFX state or disk, so no single calling thread is safe for both branches. Split snapshot acquisition from filesystem acquisition.
9. **Failure status is not transaction failure.** H09 and M11 report a failure while leaving changed identity or running a dependent command. Every multi-step workflow needs an explicit commit/abort contract, including what survives a failed rollback.

### Recommended Fix Order

**Batch 1 — Stop immediate data-loss paths.** Fix H01, H02, H03, H04, H09, H10, H13 and M05. These can receive targeted containment first: post-save close checks, resource dirty-owner preflight, non-applicable diff types, literal Git paths, failed Save As state, draft close/choice protection, and guarded user mutations. Add the matching P0 integration regressions before broad refactoring.

**Batch 2 — Unify document identity and disk conflict handling.** Address H06/H08/H11/M01 together. Establish one identity transition API and live-owner lookup spanning windows; reconcile resource destinations, narrow-coordinate transitions, Save As, ordinary rename, and expected disk preimages. Preserve atomic writes and captured-content dirty acknowledgment.

**Batch 3 — Centralize repository mutations and index preconditions.** Fix H05/H07/M07/M08/M09. Route history/branch/stash/discard operations through one declared mutation boundary, protect literal paths, validate captured index blob identity, refresh/reload only after completion, and preserve conflict/EOF fidelity. This batch depends on Batch 2's document reconciliation rather than adding more per-action reload code.

**Batch 4 — Make LSP requests and save events versioned end-to-end.** Fix H12/M02/M03/M04/M11. Define sent version versus local version, document/session epochs, verified closed-file preimages, save-notification semantics, and abort-on-edit-failure behavior. Extend controllable-future tests through actual FX delivery and real coordinator consumers.

**Batch 5 — Complete diff draft ownership and background acquisition.** Fix M10/M12 and consolidate the initial H03/H10/H13 containment. Separate exact source snapshots from rendering models, give drafts a full lifecycle, and split FX snapshots from I/O. Then address M06's full-context regex replacement using the existing bounded matcher machinery.

**Release confidence gate:** rerun the focused P0 matrix, then `mvn verify`; exercise a real JDT LS project with delayed responses, Java class rename across files, live Git checkout/reset, same-file multi-window editing, and platform-specific paths/symlinks. The targeted green tests in this review do not establish production readiness in the presence of the reproduced failures above.
