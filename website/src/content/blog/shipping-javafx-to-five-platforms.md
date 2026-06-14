---
title: "Shipping a JavaFX app to five platforms"
description: The release pipeline behind Editora — native installers and fat jars for five targets, jpackage, moditect, and the joys of automatic modules.
date: 2026-06-12
author: Adrian De Leon
tags: [packaging, release]
---

Pushing a `vX.Y.Z` tag to Editora produces native installers and runnable jars
for five targets: Linux x64/arm64, macOS x64/arm64, and Windows x64. Getting
there involved more sharp edges than the feature work did. Here's the shape of
it.

## No cross-building

jpackage and JavaFX are host-specific: you build a macOS DMG on macOS, a Windows
MSI on Windows, and so on. So the release workflow is a **five-way matrix**, each
target on its own GitHub-hosted runner, each building for *itself* with the same
`-Pdist` profile. There's no clever cross-compilation; there's five machines.

Each runner also builds a **per-platform fat jar**. You might expect one
portable jar, but that's impossible: JavaFX's macOS/Linux x64 and arm64 native
libraries share filenames and would collide in a single archive. So the fat jar
bundles natives **for the build host only**, and the release ships one per
platform.

## Automatic modules vs. jlink

Editora is a proper JPMS module, and the `dist` build uses `jlink` to produce a
trimmed runtime. The problem: several key dependencies: RichTextFX (and its
reactfx/flowless/undofx/wellbehavedfx friends), tm4e, PDFBox, lsp4j, are
**automatic modules**, which jlink refuses to link.

The fix is the `moditect` plugin, which injects generated `module-info`
descriptors into those jars at build time so jlink will accept them. Bump one of
those dependencies and you sometimes have to adjust the injected `requires` —
that's the tax for living on automatic modules.

There's an even sharper edge with tm4e: it ships as a NetBeans repackaging
that's **code-signed**, and jlink rejects signed modular jars. So the build
strips `META-INF/*.SF,*.RSA,*.DSA,*.EC` from it before linking.

## The one platform that's missing

Windows-on-ARM gets the x64 installer (run under emulation), not a native ARM64
build. A hosted `windows-11-arm` runner exists now, but OpenJFX 25 publishes no
`win-aarch64` native jar on Maven Central ([JDK-8314064]), so a native ARM64
build literally can't link. It's on the list for whenever JavaFX ships those
natives.

## Assembling the release

Every runner uploads its installer (renamed to a consistent
`Editora-<version>-<target>.<ext>`) and its fat jar. A final job hands them all
to [JReleaser](https://jreleaser.org), which creates the GitHub release with the
installers, jars, a `checksums.txt`, and a changelog. JReleaser only
*orchestrates* — it doesn't build, and there's no `pom.xml` change for it, so the
normal build is unaffected.

## What I'd tell someone starting out

If you're packaging a JVM desktop app: expect the matrix (no cross-building),
budget time for the automatic-module/jlink dance, and check early whether your UI
toolkit even *has* natives for every arch you want to ship — because "we'll add
ARM later" can turn out to mean "we can't, yet," and it's better to know that
before you promise it.
