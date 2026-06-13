# Hash Tools plugin

Hashes text to a lowercase-hex digest — palette commands `Hash: MD5` · `Hash: SHA-1` · `Hash: SHA-256`.

With a selection it **replaces** the selection with its hash; with no selection it **inserts** the hash of
the whole document at the caret (so the document isn't clobbered). Pure helper in `Hashes` (JDK
`MessageDigest`). Build with `./build.sh`; install via *Settings → Plugins → Install from file…* or copy into
`<configDir>/plugins/hash-tools/`. See [`../../docs/plugins.md`](../../docs/plugins.md).
