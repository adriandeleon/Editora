# 0005 — In-scene overlays (`OverlayHost`), not `javafx.stage.Popup`

**Status:** Accepted

## Context

The command palette and every keyboard picker (file finder, switcher, quick-open lists, branch
dropdown, message log, input prompts) need a focused, modal-ish surface that takes keyboard input.
The obvious JavaFX primitive is `javafx.stage.Popup` — a separate native window.

## Decision

Render all of them as **in-scene overlays** over a single shared `ui/OverlayHost` installed into
the scene-root `StackPane`, not as `Popup`s. The host draws one "card" at a time over a dim,
click-to-dismiss backdrop and owns focus capture/restore, `Esc`/`C-g` dismissal, and a
`justHidden()` toggle guard.

## Context behind it

A `Popup` is a separate native window. On Windows it doesn't reliably take OS keyboard focus, so
calling `input.requestFocus()` orphaned focus between the popup's scene and the main window and
the keyboard went dead app-wide (mouse still worked). In-scene overlays share the main window's
scene, so focus never leaves it.

## Consequences

- Every picker supplies a card node (which sets `editora.ownsKeys` so its `C-n`/`C-p`/arrow nav
  isn't hijacked by the global `KeyDispatcher`), an `onShown` hook (`requestFocus`), and an
  `onHidden` hook. `MainController.wireOverlayHost()` injects the host into all of them.
- Input dialogs use the same mechanism via `ui/OverlayInput` (callback-driven; `onAccept` runs
  after the card hides, so focus is already back in the editor — no blocking `showAndWait`).
- The one exception is `editor/CompletionPopup`, which stays a `Popup` — it never calls
  `requestFocus()`, so it's safe.
- Deliberately left native: simple `Alert` confirmations and `ChoiceDialog` selectors.
