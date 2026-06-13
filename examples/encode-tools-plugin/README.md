# Encode Tools plugin

Encode/decode the selection (or whole document) as palette commands:

`Base64 Encode/Decode` · `URL Encode/Decode` · `HTML Entities Encode/Decode` · `ROT13` · `Hex Encode/Decode`

Pure codecs in `Codecs`; decoders that fail on malformed input report the error in the status bar and leave
the buffer unchanged. Build with `./build.sh`; install via *Settings → Plugins → Install from file…* or copy
into `<configDir>/plugins/encode-tools/`. See [`../../docs/plugins.md`](../../docs/plugins.md).
