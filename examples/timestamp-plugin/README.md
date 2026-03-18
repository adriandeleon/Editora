# Timestamp Plugin Example

This example plugin demonstrates Editora's dynamic plugin loading.

It registers:
- a command palette action: `Insert Timestamp Comment`
- a plugin menu action with the same behavior
- a second command palette action: `Open Workspace README`

It also demonstrates the stronger `EditoraContext` API by:
- inserting text at the active caret
- inspecting the active document path
- opening a file from the current workspace root
- publishing a status-bar message

## Build into `plugins/`

Run from the project root after the main app has been compiled:

```zsh
./scripts/build-example-plugin.sh
```

That script compiles this example against `target/classes` and writes:
- `plugins/timestamp-plugin.jar`

