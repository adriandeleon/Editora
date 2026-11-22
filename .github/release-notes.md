## Install

Download the file for your platform below. The native packages bundle their own Java runtime.

- **macOS**: the `.dmg` for Apple Silicon (`macos-arm64`) or Intel (`macos-x64`). Open it and drag Editora to
  Applications. It is signed and notarized by Apple, so it opens on first launch like any other app.
- **Windows**: the `.msi` (`windows-x64`). It puts Editora on the PATH, so `editora-cli --version` works from a
  new terminal. The installer is not code-signed yet, so SmartScreen stops the first launch until you allow it.
- **Linux**: the `.deb`, `.rpm` or `.AppImage` (`linux-x64`), or the portable `.tar.gz` with an `install.sh`
  (`linux-x64` and `linux-arm64`).
- **Any OS with a JDK 25**: the fat jar for your platform, run with `java -jar`.

Check a download against `checksums_sha256.txt`. Every Linux file has an OpenPGP signature beside it
(`<file>.asc`) by the key attached as `editora-signing-key.asc`: `gpg --import editora-signing-key.asc`, then
`gpg --verify <file>.asc <file>`. Every file also has a build provenance attestation:
`gh attestation verify <file> --repo adriandeleon/Editora`.
