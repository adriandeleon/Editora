# Pauta conformance

Editora is one of the apps [Pauta](https://pauta-project.dev/) (the adl desktop apps guidelines) covers, and its
reference implementation. It names the edition it works toward, computes how fully it conforms, and records
every MUST and SHOULD it does not meet. This page says where each piece lives and how to keep them true.

## The pieces

| What | Where |
|---|---|
| The edition | `pauta.version` in `pom.xml`, filtered into `build-info.properties` and read by `AppInfo.PAUTA_EDITION`. No edition number is written anywhere else in the code. |
| The exceptions | `src/main/resources/com/editora/pauta.json`: one entry per unmet MUST or SHOULD, with the rule's `level` and `url` copied from the edition, a `kind`, one plain sentence for the user as its `reason`, and a `link`. |
| The conformance | `PautaConformance.of`: Partial when a MUST is pending, Core when a SHOULD is pending, Complete otherwise. Computed, never stored. |
| In the app | About shows the edition and the conformance as two links and two lines of its environment block. The Pauta window (`ui/PautaWindow`, `help.pauta`, Help → Pauta Conformance) lists every exception. `help.pautaRules` opens the edition's rules, `help.pautaDocs` the user guide's page, and `help.copyPauta` copies the list as Markdown. |
| For readers of the repository | The README's Pauta badge and its "Working toward" line; the Pauta section of `AGENTS.md`. |
| The tests | `PautaConformanceTest` holds `pauta.json` to the edition's rules (`src/test/resources/pauta-rules.json`), and the README and `AGENTS.md` to the edition and the computed conformance. `PautaWindowFxTest` covers the window. |

At Partial the app says *Working toward Pauta x.y.z*, never *Built to*: it does not meet every MUST of that
edition yet.

## Kinds of exception

- `pending`: not done yet. Its `link` is an open issue in this repository that names the rule as
  "MENU-14 (Pauta 6.0.1)". One issue holds the pending rules of one Pauta page.
- `deviation`: a SHOULD the app does differently on purpose. Its `link` is a decision record under
  [`decisions/`](decisions/README.md) that names the rule. A MUST cannot be a deviation.
- `not-applicable`: the rule reads as applying to every app, but its subject does not exist in Editora. No link.

A rule that Pauta limits to apps with a feature Editora lacks (the tray rules, for one) needs no entry.

## When a change touches a rule

- **It meets a rule that has an entry:** remove the entry in the same change, and tick the rule off in its
  issue. When an issue's last rule is met, close it.
- **It stops meeting a rule:** fix it, or add an entry with an issue. `PautaConformanceTest` fails an entry
  whose level or address differs from the edition's, a pending entry with no issue of this repository, and a
  deviation with no decision record.
- **The conformance changes** (the last pending MUST is met, say): the README's badge and line and the
  `AGENTS.md` section change with it. The test names the line it expects.

## Auditing again

`.claude/skills/pauta-audit/SKILL.md` is the audit procedure of the edition `pauta.version` names. Ask a coding
agent to "audit against Pauta": it judges every rule from the code, reads `pauta.json`, and writes
`docs/pauta-audit.json` and `docs/pauta-audit.md`. An unmet rule with no entry is a `fail`, and so is the
audit; a by-hand rule nobody has recorded is `manual`, which is not a failure. A person who checks a by-hand
rule in the running app and finds it met may record it in `pauta.json`'s `checked` list with the edition and
the date.

## Moving to a newer edition

One change: replace `src/test/resources/pauta-rules.json` and the skill copy with the new edition's, remove the
`checked` entries the new rules make stale, audit again and update `pauta.json`, then change `pauta.version`,
the README and `AGENTS.md` together. Pauta's
[Coding agents and LLMs](https://pauta-project.dev/latest/coding-agents/#move-an-app-to-a-newer-edition) page
has the steps and a command that lists what changed between two editions.
