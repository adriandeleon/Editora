# Insert Tools plugin

Inserts generated text at the caret, as palette commands:

`Insert: UUID` · `Insert: Timestamp (ISO-8601)` · `Insert: Date (yyyy-MM-dd)` · `Insert: Time (HH:mm:ss)` ·
`Insert: Date & Time`

Each command pulls a fresh value at invocation and calls `ActiveEditor.insertAtCaret`. Build with
`./build.sh`; install via *Settings → Plugins → Install from file…* or copy into
`<configDir>/plugins/insert-tools/`. See [`../../docs/plugins.md`](../../docs/plugins.md).
