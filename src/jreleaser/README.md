# JReleaser templates

JReleaser reads a packager's templates from `distributions/<distribution>/<packager>/` here before its own
(`jreleaser.yml` names the distributions).

- `editora/brew/cask.rb.tpl`: the Homebrew cask, JReleaser's stock one with two changes.
  - No `auto_updates true`. The stock template always writes it, and it tells `brew upgrade` to skip the
    cask; Editora only says when a newer version exists, so Homebrew does the updating.
  - `depends_on arch: :arm64`. The cask takes one DMG, the Apple Silicon one; Intel Macs use the
    `macos-x64` DMG from the release.
