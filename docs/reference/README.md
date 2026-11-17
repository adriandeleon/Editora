# Detailed reference catalogs

These files preserve the exhaustive implementation notes and architecture history that once made
the root `AGENTS.md` difficult to use as an operational guide. They are reference material, not a
required start-to-finish read.

- [commands.md](commands.md) — expanded run, package, fat-jar, and release commands.
- [release-pipeline.md](release-pipeline.md) — CI matrix and platform packaging behavior.
- [architecture-catalog.md](architecture-catalog.md) — exhaustive subsystem implementation notes.
- [conventions-catalog.md](conventions-catalog.md) — detailed coding and maintenance conventions.
- [packaging-notes.md](packaging-notes.md) — dependency, jlink, and shaded-jar details.
- [navigation-roadmap.md](navigation-roadmap.md) — how code navigation was planned and built: the four
  gaps, the order they were closed in, and the reasoning behind it.
- [native-image-staticfx-results.md](native-image-staticfx-results.md) — the StaticFX / GraalVM experiment's
  measured results, acceptance checklist and the architecture it started from.

Start with the focused guides in the [developer documentation index](../README.md). When a catalog
and the code disagree, the code wins; update the relevant focused guide and catalog together.
