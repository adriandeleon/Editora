---
name: pauta-audit
description: >-
  Audits this JavaFX app against the Pauta edition it implements (the adl desktop apps guidelines,
  pauta-project.dev): checks every rule (KBD-07, WIN-03, …) by code, test or by hand, reads the app's
  pauta.json exceptions, and writes docs/pauta-audit.json and docs/pauta-audit.md with a status and file:line
  evidence for each rule, failed MUSTs first. Use when asked to "audit against Pauta", "check Pauta
  compliance", "which Pauta rules does this app fail", "is this app Pauta compliant", or to audit one Pauta
  page or rule prefix (for example "audit KBD" or "audit the keyboard rules"). Not for writing new features
  from Pauta (read the rule's page for that).
argument-hint: "[prefix | page | rule ID …] [edition=<x.y.z>] [print]"
---

# Pauta audit

Audit this app against the Pauta edition it says it implements, one result per rule, and write the report to
`docs/pauta-audit.json` and `docs/pauta-audit.md`. Pauta is at https://pauta-project.dev/; each edition lives at
`https://pauta-project.dev/<edition>/`. Editora (`../Editora`) is its reference implementation.

This skill is part of Pauta: every edition from 4.3.1 serves its own copy at
`https://pauta-project.dev/<edition>/skills/pauta-audit/SKILL.md`. **Audit with the copy of the edition you audit
against**; for an edition before 4.3.1, which serves none, use the newest edition's. An app that keeps a copy
(`.claude/skills/pauta-audit/`) replaces it with the new edition's when it moves to one (Conformance, PAUTA-11),
so the copy beside the code is its edition's. If this file is not the one that edition serves, follow the
edition's.

**That copy decides how to audit and how to write the report; `rules.json` supplies the rules.** An edition's
`llms.txt` describes the procedure in short, and an older one sometimes differently (2.0.x reports "not
applicable", with a space, and has no `limited`): where it disagrees with the skill, follow the skill.

## Arguments

All optional, in any order:

- **Scope.** A rule ID prefix (`KBD`, `win`), a page slug (`keyboard`, `window-shell`, `about` for
  `templates/about`), a page title (`Keyboard first`) or single IDs (`KBD-07 WIN-03`). Several are allowed; a rule
  is in scope if it matches any. No scope means every rule in the edition.
- **`edition=<x.y.z>`** overrides the edition the app names (step 1).
- **`print`** prints the Markdown report in the reply instead of writing the two files.

## 1. Find the edition

In this order, and stop at the first that names one:

1. The `pauta.version` property in `pom.xml`: `grep -n '<pauta.version>' pom.xml`.
2. The README's "Built to Pauta X.Y.Z" (or "Working toward") line: `grep -n -i 'pauta' README.md`.

If neither names an edition, **ask the user which edition to audit against**, and offer the newest from
https://pauta-project.dev/versions.json. With no one to ask, audit against the newest, set `appEdition` to `null`,
and say so in `problems`. If the two disagree, use `pom.xml` and report the mismatch in `problems`
(the README line is stale).

## 2. Load the edition's rules

Fetch into `target/pauta/<edition>/` (ignored by Git, wiped by `mvn clean`; never commit these files):

```bash
mkdir -p target/pauta/$EDITION
for f in rules.json llms.txt audit.schema.json; do
  curl -fsSL -o target/pauta/$EDITION/$f https://pauta-project.dev/$EDITION/$f
done
```

`audit.schema.json` exists from 4.3.1 on; for an older edition, use the newest edition's.

**Offline:** a checkout of the guidelines at `../adl-desktop-apps-guidelines` holds every published edition on its
`versions` branch. Read them from there without building anything:
`git -C ../adl-desktop-apps-guidelines show origin/versions:$EDITION/rules.json > target/pauta/$EDITION/rules.json`
(the same for `llms.txt`). An edition that is not on that branch is not published yet: ask the user.

**The edition has no `rules.json`:** the 1.x editions, published before rules had IDs, don't serve one. Say so,
and offer to audit against the oldest edition that does (2.0.0) or the latest (list them from `versions.json`).
Then set `edition` to the one audited and `appEdition` to the app's, and report a failed rule that the app's own
edition didn't have as upgrade work in its evidence: the `changelog/` page says what each edition added or made
stronger.

`rules.json` holds the edition's `levels`, `checks` and `statuses` (what each one means), `pages[]` and `rules[]`,
each rule with `id`, `level` (MUST, SHOULD, MAY), `page`, `pageTitle`, `section`, `appliesTo`, `url`, `text`, `why`,
`check.kind` (`code`, `test` or `by hand`), `check.text`, `check.command` (on some code checks), `reference` and
`changed` (the edition in which its level, scope or statement last changed); and `retired[]`, the IDs no longer in
use. An older edition has less:

| Editions | `rules.json` |
|---|---|
| 1.x | None (below). |
| 2.0.0 to 4.2.0 | `levels`, `checks` and `rules[]`, each rule without `appliesTo` or `check.command`: judge a limit from the rule's text and its page's **Applies to** line. No `audit.schema.json`, no page `.md`. |
| 4.3.0 | Adds `retired[]`. |
| 4.3.1 to 4.5.0 | Adds `statuses`, `audit`, `pages[]` and each rule's `appliesTo`; the edition serves `audit.schema.json`, each page as `.md` and this skill. |
| 5.0.0 | Adds `check.command` on some code checks. |
| After 5.0.0 | Adds each rule's `changed` and the status `not-checked`; `pauta.json` may hold a `checked` list (PAUTA-18). |

Whatever the edition, the report uses this skill's statuses and format.
The rules in scope:

```bash
jq -r --arg p "KBD" '.rules[]
  | select((.id | ascii_upcase | startswith(($p | ascii_upcase) + "-")) or .page == $p or (.page | split("/") | last) == $p
           or (.pageTitle | ascii_downcase) == ($p | ascii_downcase))
  | [.id, .level, .check.kind, (.appliesTo // ""), .check.text] | @tsv' target/pauta/$EDITION/rules.json
```

When a rule leans on its page (a template's mockup or spec table), read the page as Markdown at the page's
address with `.md` in place of the trailing `/` (`https://pauta-project.dev/<edition>/templates/about.md`, from
4.3.1 on), or its HTML page. A rule whose `text` or `check.text` links a section of its own page leans on it too:
`rules.json` carries the link and not what it points at (OS-01 and OS-04 are judged against the rows of the table
their statements link). Open the rule's `url`, or the page's `.md`, and read that section before you judge the
rule.

## 3. Read the app's exceptions

Read `src/main/resources/com/<app>/pauta.json` (Conformance, PAUTA-09). Each entry names a `rule`, its `kind`
(`not-applicable`, `deviation`, `pending`, or in a template `app-only`), a `reason` and, for a deviation or pending
entry, a `link`. `"template": true` marks a template repository. No file is a finding on PAUTA-01 and PAUTA-09, and
every unmet rule is then `fail`.

Beside `exceptions` the file may hold `checked` (PAUTA-18): the by-hand rules a person checked in the running app
and found met, each with its `rule`, the `edition` it was checked against and the `date`. It is a record, not an
exception: a by-hand rule with no entry needs none, and the conformance does not count it. The list is the app's to
keep or not. PAUTA-18 governs only the entries that are there (each well formed and not stale), so an app with no
`checked` list, or an empty one, passes PAUTA-18; the `manual` results still say which by-hand rules are not
recorded. An edition up to 5.0.0 has no such list.

## 4. Judge each rule

Read the whole rule (`text`, `why`, `check`, `appliesTo`) before judging it.

**Names.** Some names in a rule's `text` are required as spelled, because a user, the docs or another tool finds the
thing by that name: a command id, a catalog key, a chord, a command-line flag, an environment variable, a property in
`pom.xml` or `pauta.json`, and a file the repository must carry, whether a document at its root or a resource or
tool configuration the rule names (`AGENTS.md`, `emacs.json`, `messages_<code>.properties`, `jreleaser.yml`). Every
app spells it that way, and the same thing
under another name fails the rule (say so in the evidence). A platform API the rule names (`javafx.stage.Popup`,
`SystemTray`, `Platform.setImplicitExit(false)`, `Future.get()`) is the requirement itself: the JDK and JavaFX spell
it one way for every app. Any other name is how Editora spells it, and the app's equivalent meets the rule: a class,
method or constant of the app's own code, a CSS file or style class, a branch or workflow file, a file the app writes as
it runs (its config files). So is a name of the app's own code that only `check` or `reference` gives. Edition 5.0.0's list was a command id,
catalog key, file, property or style class, with nothing about platform APIs: for an app on 5.0.0, judge by that list.
Earlier editions say nothing about names, so there an equivalent meets the rule.

Then, in this order:

1. **Limited.** A rule is limited when its own text names the apps it covers ("An app with user documents…", "An app
   that stores a secret…"), when it has an `appliesTo` (its page or section opens with an **Applies to** line), or when
   it details a rule that is limited (the rules after a limited one on the same subject). Decide from the code
   whether this app is one of them. If it isn't, the status is **`limited`**, and the evidence names what the app
   lacks ("no tray: no `SystemTray` or `TrayIcon` in `src/`"). A limited rule needs no exception; a `pauta.json`
   entry for one is a problem (PAUTA-05). Those three are the only places a scope is written. When none of them
   limits the rule, it covers this app: never infer a scope from what the app lacks (*Nothing to judge*, below,
   says what such a rule gets).
2. **Met?** Check it as its kind says:
   - **`code`.** When the rule has a `check.command`, run it with `sh -c` from the repository root (POSIX sh: Linux,
     macOS, Git Bash or WSL). Exit 0: the check passes; still read the rest of the rule and cite the `file:line`
     that meets it. Any other exit: the check fails, and what it printed is the evidence. A command only reads, and
     fails outside an app's root; take it from `rules.json` with `jq -r '.rules[] | select(.id == "OS-02") |
     .check.command'`, never through `@tsv`, which doubles its backslashes. Otherwise read or search the source as
     `check.text` says (a name of the app's own code that only the check gives is Editora's spelling: find this
     app's equivalent; a name of a required kind in the rule's `text`, and a platform API, is as written, as
     above). It is met only if everything the check names is there. Evidence is the `file:line` that satisfies it;
     for an unmet rule, the closest place it should be and what is missing. A `code` check that ends "A test that
     asserts it is the surest evidence" does not require the test: the behavior shown in the code meets the rule,
     with the test or without it. Cite the test when the app has one, and never fail the rule for lacking it.

     A search written as a code span inside `check.text` (the `grep` in PERF-01, PERF-02, PERF-03, PERF-08 and
     OS-04) is not a `check.command`. Run it from the app's root: it lists candidate lines, and you judge each
     line it prints as the text says. Its exit code is no verdict: it exits 0 with candidates and non-zero with
     none. Only `check.command` settles a rule by its exit code. Where the candidates are too many to read in one
     audit (thousands of lines, in a large app), say in the evidence how far you read. Report the rule as unmet
     only on a violation you found, and cite it; with none found in what you read, report `pass` and say what you
     read.
   - **`test`.** The rule requires a test (from the edition after 5.0.0 its statement says so: "A test holds it.", or the rule is itself about a test),
     and the check names it as Editora calls it. Find that test or this app's equivalent in `src/test/`,
     read it to confirm it asserts what the rule says (a test with the right name that checks something else does not
     meet it), and confirm `./mvnw verify` runs it: not `@Disabled`, not excluded by the Surefire configuration in
     `pom.xml` (`excludes`, `excludedGroups`, `groups`). Evidence is the test's `file:line`.
   - **`by hand`.** The source alone can't confirm it, so the audit never passes it on the source's word. The
     source can still refute it:
     - When `pauta.json` has an exception for the rule, the app itself says it does not meet it: the status is the
       entry's kind and the evidence its `reason`, as in step 4. It is not `manual`.
     - When the source plainly shows the rule unmet, it is unmet, not manual: step 4 or 5, with the `file:line`
       as evidence. The feature isn't there at all, or the code plainly does otherwise: a Settings window built as
       a `TabPane` with no search field cannot have the sidebar of pages and the search field its rule asks for.
     - Otherwise, where the source leaves it open, the status is **`manual`**, and the evidence says what to look at in
       the running app (from `check.text`) and the `file:line` of the code involved, if any. Then look the rule up in
       `pauta.json`'s `checked` list (Conformance, PAUTA-18). An entry gives the result its `checked` field: the entry's
       `edition` and `date`, and its `version` when it has one. With no entry, leave the field out and end the evidence
       with "not recorded": nobody has recorded checking the rule. That is not a failure of the app and not a `problem`,
       it needs no exception, and it does not change the conformance. Never write a `checked` entry yourself, and don't
       offer an exception for the rule: the entry is for the person who checks it in the running app.
     - A `checked` entry is stale when its `edition` is older than the rule's `changed` in `rules.json`, and only
       then: the rule's level, scope or statement changed after the check. Give the result its `checked` field all
       the same, and say in `problem` that the entry is stale, since which edition, and that it is to be removed
       and the rule checked again. Report in `problem` too an entry for a rule that also has an exception.
     - A few by-hand Checks send the person somewhere other than the running app: to the repository's merged
       pull requests on GitHub (DOCS-16, PERF-18, PERF-22), or to break a test and watch it fail (QUAL-10). They
       are `manual` like the rest, and never `not-checked`, which is only for a Check that names it: the evidence
       says where to look, and a `checked` entry records them like any other.
   - **Out of the checkout's reach.** A few checks need what the app's checkout does not hold: another repository (the
     app's website, for DOCS-01, DOCS-02, PAUTA-13 and a few more) or what the repository's host keeps and no file shows
     (a repository setting, such as the checks REL-19 has the default branch require; whether the issue PAUTA-07 asks
     for is open). The Check says so, and names `not-checked`. First judge the part of the Check the checkout does hold:
     if that part is unmet, the rule is unmet (step 4 or 5). For the website's rules that part includes whether the
     app names a website at all: an app whose README and About link none, and whose `help.documentation` opens
     none, fails DOCS-01, DOCS-02 and PAUTA-13 from the checkout, as their Checks say. `not-checked` is only for an
     app that names a website whose repository you cannot reach. Then, if you can reach the rest (the website's
     repository is checked out beside the app, as `../<app>-website`; `gh` answers for this repository), read it and
     judge the rule like any `code` rule. If you can't, and `pauta.json` has no exception for the rule, the status is
     **`not-checked`**: not a finding and not a pass. The evidence says what was read, what was not, and what would
     settle it ("ci.yml:3 and :9 have both triggers and both jobs; whether the default branch requires them is a
     repository setting: read its branch protection on GitHub"). `not-checked` is for these cases and for ABOUT-07 in
     an audit run against another edition (*Judged last*, next), and only where the rule's Check names it. What needs
     a person, in the running app or wherever a by-hand Check sends them, is `manual`, and a `code` or `test` rule the
     source merely doesn't show is unmet (below).
   - **Judged last.** PAUTA-04 and ABOUT-07 have the audit itself as their Check. Judge them after every other
     rule in scope, from those results, as each Check says: PAUTA-04 passes when no MUST or SHOULD is `fail` and
     no result reports an entry for a rule that passes, and its evidence names the rules that fail. A `manual` or
     `not-checked` result does not fail either of them. In an audit of part of the edition, judge them from the
     rules in scope and say so in the evidence. Only the rules of the edition the app names settle ABOUT-07. An
     audit run against another edition (`edition=` was given, or the app's edition serves no `rules.json`) cannot
     judge an About that says *Built to*: it reports ABOUT-07 as `not-checked`, as the Check says, and the
     evidence names the edition whose rules would settle it. What the Check settles from the app's own code, it
     still settles: an About that says *Working toward* passes whatever edition was audited.

   Partly met is unmet: say which part is missing. Don't guess: when the source doesn't settle a `code` or `test`
   rule, say what you searched for and treat it as unmet, with "not found" in the evidence. Each rule is judged on
   its own words: one defect that breaks two rules (a button no command reaches, which is also out of the Tab
   order) fails both, each with its evidence.
3. **Met:** **`pass`**. If `pauta.json` has an entry for it, report the stale entry in the result's `problem`.
4. **Unmet, and `pauta.json` lists it:** its status is the entry's kind (`pending`, `deviation`, `not-applicable`,
   `app-only`) and the evidence is the entry's `reason`. Report in `problem` a deviation on a MUST, a deviation or
   pending entry with no `link`, an `app-only` entry outside a template, and a `level` or `url` that differs from the
   edition's.
5. **Unmet, and not listed:** **`fail`**, for a MUST or SHOULD. An unmet MAY is `fail` with no consequence and
   needs no entry.

**Nothing to judge.** When the app has nothing of what a rule is about, the rule's own words say what it gets:
where its Check says what an app without the thing gets ("fails with FIND-01", "is outside the rule"), that is the
answer. Otherwise take the first of these that fits, and don't guess between them:

- **The rule is limited** (step 1: its text, its `appliesTo`, or the rule it details) and the app is outside it:
  `limited`.
- **The rule is about a part another rule requires** (the Command Palette, a status bar, About and its edition
  link, the Debug Log's find bar, the Pauta window, the keymap files): how the part behaves, what it shows, or
  what every one of its rows, controls or files does or never does. Without the part the rule is unmet. It fails
  with the rule that requires the part, and its evidence names that rule ("no Pauta window: fails with PWIN-01"),
  so the reader sees one cause and not several findings. Where FIND-01 asks for a find bar and the app has none,
  the rules on how a find bar searches, steps and closes fail with FIND-01; with no Pauta window, the rules on
  what it shows and reads fail with PWIN-01; with no keymap file, the rule on what a keymap's macOS variant binds
  (KBD-21) fails with KBD-09; with no edition link in About, the rule on the address it opens (ABOUT-06) fails
  with ABOUT-05. A rule worded as a limit on every item of a kind belongs here, not in the next case, whenever
  some rule requires the app to have an item of that kind: ask that first. Its Check may still say otherwise, as
  FIND-16's and ABOUT-07's do for an app with no find bar and an About that names no edition: then the Check is
  the answer.
- **The rule constrains things no rule requires an app to have.** It says what every entry, row or file of a kind
  does, or what none does, and the app may have one or not ("every `deviation` entry names a SHOULD", "a Chinese,
  Japanese or Korean catalog comes with a font fallback"). With none of that kind in the app, nothing breaks the
  rule: `pass`, and the evidence says there is none ("no `deviation` entry in `pauta.json`"). So with no
  `pauta.json` at all, PAUTA-05 to PAUTA-08 and PAUTA-18 pass, and the missing file is PAUTA-01's and PAUTA-09's
  finding. This holds for a by-hand rule too: with nothing of the kind in the source, there is nothing for a
  person to look at.
- **Anything else.** The rule reads as applying to every app, none of the three places limits it, and it asks for
  something this app has no use for (a Doctor page that checks the environment, in an app that depends on no
  outside tool or library). It is `fail`, and the fix is a `not-applicable` entry in `pauta.json`: say so in the
  evidence.

`pauta.json` entries that name an ID the edition does not have go in the report's `problems`: for one in the
edition's `retired` list, name the rule in its `see`. So does a `checked` entry for a rule that is not checked by
hand.

Cite rules as "KBD-07 (Pauta <edition>)". Paths in evidence are relative to the repository root, as
`path/File.java:123`, so they are clickable.

**Large audits.** A whole edition is hundreds of rules. If you can start subagents, split the rules in scope by page
and give each subagent its pages, the path to `rules.json`, the app's `pauta.json` and this section; ask each for its
results as JSON objects in the schema's `results` shape, and merge them in the edition's rule order. Otherwise work
page by page and write the results as you go.

## 5. Write the report

Replace any earlier report: it is a snapshot, regenerated rather than edited by hand.

**`docs/pauta-audit.json`** follows the edition's `audit.schema.json`:

```json
{
  "$schema": "https://pauta-project.dev/<edition>/audit.schema.json",
  "app": "<App>",
  "commit": "<short SHA>",
  "edition": "<edition>",
  "date": "<YYYY-MM-DD>",
  "scope": [],
  "rules": "https://pauta-project.dev/<edition>/rules.json",
  "results": [
    { "id": "KBD-07", "level": "MUST", "status": "pass", "evidence": "src/main/java/com/example/app/ui/CommandPalette.java:57" },
    { "id": "SET-02", "level": "MUST", "status": "manual", "evidence": "Open Settings and find the sidebar of pages, the open page and the search field: src/main/java/com/example/app/ui/SettingsWindow.java:120", "checked": { "edition": "<edition>", "date": "2026-11-02" } },
    { "id": "MENU-14", "level": "MUST", "status": "pending", "evidence": "Some menu rows have no icon yet." },
    { "id": "PWIN-02", "level": "MUST", "status": "manual", "evidence": "Open the Pauta window and compare it with pauta.json, entry by entry: src/main/java/com/example/app/ui/PautaWindow.java:55; not recorded" },
    { "id": "TRAY-01", "level": "MUST", "status": "limited", "evidence": "no tray: no SystemTray or TrayIcon in src/" },
    { "id": "PAUTA-13", "level": "MUST", "status": "not-checked", "evidence": "needs the website's repository (example-website, not checked out beside the app): its src/content/docs/ has a Pauta conformance page that lists the rules of pauta.json" }
  ],
  "problems": []
}
```

One result per rule in scope, in the edition's rule order. For an edition before 4.3.1, `$schema` is the newest
edition's `audit.schema.json`. Check the report against it if a validator is at hand.

**`docs/pauta-audit.md`** is the same report for people:

```markdown
# Pauta audit

<App name> at <short commit SHA>, audited against [Pauta <edition>](https://pauta-project.dev/<edition>/)
on <YYYY-MM-DD>. Scope: <every rule | the prefixes, pages or IDs given>. Rules from <URL or local path>.

|        | pass | fail | manual | not-checked | pending | deviation | not-applicable | app-only | limited |
|--------|-----:|-----:|-------:|------------:|--------:|----------:|---------------:|---------:|--------:|
| MUST   |      |      |        |             |         |           |                |          |         |
| SHOULD |      |      |        |             |         |           |                |          |         |
| MAY    |      |      |        |             |         |           |                |          |         |

A failed MUST blocks a release. A `manual` or `not-checked` MUST does not: it is reported until someone settles it.

## Failed MUSTs

| ID | Level | Status | Evidence | Rule |
|----|-------|--------|----------|------|
| TBAR-01 | MUST | fail | `src/main/java/…/ui/MainToolbar.java:88`: builds an icon button by hand | https://pauta-project.dev/<edition>/toolbars-and-tooltips/#tbar-01 |

## Problems with pauta.json

## Other failures

(SHOULD, then MAY.)

## Manual checks

(Each with the edition and date it was recorded as checked, or "not recorded"; a stale entry says so. A rule not
recorded is not a failure, of the rule or of PAUTA-18.)

## Not checked

(Each with what would settle it: the other repository to read, the repository setting to look up, or for
ABOUT-07 the edition to audit against. Not a failure, and not a pass.)

## Exceptions

(pending, deviation, not-applicable, app-only.)

## Passed and limited

(In rule order.)
```

Omit a section, or a column of the summary, that would be empty. Keep each evidence cell to one line, and escape `|`
inside cells as `\|`.

## 6. Offer fixes

After the report, list the failed MUSTs in the reply (ID, one line each) and **offer to fix them**; don't start
without a yes. For each fix:

- Copy from the rule's `reference`, which names files in Editora (`../Editora`) or one of the edition's templates;
  adapt names to this app rather than inventing a new design.
- Follow `AGENTS.md`'s change checklist: a command for each action, strings in every catalog, tests, docs and a
  `CHANGELOG.md` entry, and `./mvnw verify` passing.
- A rule the app will not meet now is recorded instead: `pending` with an issue, or for a SHOULD a `deviation` with
  a decision record (Conformance, PAUTA-06 and PAUTA-07).
- Re-run the audit for the fixed IDs only (`/pauta-audit TBAR-01 WIN-03`) and update their results.

List the `not-checked` rules too, each with what would settle it, and offer to settle the ones you could reach with
help: the website's repository checked out beside the app, or `gh` signed in for the repository's settings and
issues.

Failed SHOULDs are worth fixing too; offer them separately, after the MUSTs.
