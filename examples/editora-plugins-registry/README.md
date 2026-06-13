# Example plugin registry (`index.json`)

This is a ready-to-host **plugin registry index** for Editora's *Settings → Plugins → Browse plugins…*.
Editora fetches a single `index.json` over HTTPS and lists each entry for one-click install.

## Hosting it

1. Create a public GitHub repo (the baked-in default is `adriandeleon/editora-plugins`, overridable in
   *Settings → Plugins → Registry URL*).
2. Commit this `index.json` at the repo root. Its raw URL is then:
   `https://raw.githubusercontent.com/<you>/editora-plugins/main/index.json`
3. For each plugin, publish a **GitHub Release** whose asset is the plugin `.zip` (top level = the plugin
   folder contents: `plugin.json` + jar + `snippets/`…). Point the entry's `download` at that asset and set
   `sha256` to the archive's SHA-256.

## Filling in the example entry

Build the example archive and read its hash:

```sh
cd ../example-plugin && ./build.sh        # prints: sha-256(example.zip) = <hash>
```

Upload `example.zip` as a release asset, then replace `REPLACE_WITH_SHA256_OF_example.zip` in `index.json`
with that hash. (The zip stores file timestamps, so its hash changes each rebuild — always copy the value
the build just printed for the asset you actually upload.)

## Entry fields

| Field | Required | Notes |
| --- | --- | --- |
| `id` | yes | Stable id = the install folder name. |
| `name` / `version` / `description` / `author` / `homepage` | — | Shown in the browser + install dialog. |
| `download` | yes | **HTTPS** URL of the `.zip`. |
| `sha256` | yes | Lowercase hex; verified before unpacking (a mismatch aborts). |
| `minEditoraVersion` | — | Blank = any. Entries needing a newer Editora are shown but not installable. |

Unknown fields are ignored, so you can add your own metadata.
