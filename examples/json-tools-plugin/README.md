# JSON / XML Tools plugin

Tidy structured text in place (selection or whole document):

`JSON: Pretty Print` · `JSON: Minify` · `XML: Pretty Print`

JSON uses a dependency-free, string-aware reformatter (`JsonFormat`); XML uses the JDK's `java.xml`
(`XmlFormat`, secure-processing on, no external entities). Malformed input is reported in the status bar.
Build with `./build.sh`; install via *Settings → Plugins → Install from file…*. See
[`../../docs/plugins.md`](../../docs/plugins.md).
