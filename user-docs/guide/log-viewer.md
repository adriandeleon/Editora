---
title: Server log viewer
description: Log files with severity colors, a Follow button that survives rotation, huge logs opened at the tail, and live filters by level and text.
category: Tools
order: 2
---

Open a log file and Editora switches into a dedicated log mode built for
reading server output. It's **on by default** (Settings → Editor → Logs; toggle
with *View: Toggle Log Viewer*).

![A stack-trace log open in View Mode: ERROR records tinted red, Java, Python and Node stack frames, and the log control bar with a level filter and a text filter](/screenshots/features/log-viewer.png "A log file in the log viewer, with the control bar's level and text filters.")

## Which files open as logs

A file opens in log mode when its name says it is a log:

- `*.log`.
- Rotated logs: `app.log.1`, `app.log.2026-10-06`, and names ending in `.old` or `.bak`.
- Apache-style names: `access_log`, `error_log`, also with a rotation number.
- `syslog`, `dmesg`, `catalina.out` and `nohup.out`, also with a rotation number
  (`syslog.1`).

A log that logrotate compressed (`app.log.2.gz`, `syslog.3.gz`) opens too,
decompressed and read-only, so you can filter it like any log without unpacking
it first. Up to 16 MB of it is shown; for a bigger one, that is its last 16 MB,
the newest lines. Any other `.gz` file opens decompressed and read-only as well.
Follow is not available for a compressed log, since it no longer grows. Archives
of several files (`.tar.gz`, `.zip`) don't open this way.

A plain-text file whose name doesn't settle it, such as `server.out`,
`worker.err`, or a file with no extension (like most of `/var/log`), is checked
by content: it opens as a log when enough of its first lines carry a level or
start with a timestamp.

For anything else, **Log: View as Log** (`log.viewAsLog`) shows the current file
as a log. It's a toggle: run it again to go back to the plain view. Editora
remembers the choice for that file, also when you turn off a file that was
recognised by its content.

## Severity highlighting

Lines are colored by level (ERROR / WARN / INFO / DEBUG / TRACE, with FATAL
drawn like ERROR), both inline and
as a **left-edge bar** that works even on huge logs. It recognizes common
formats: Logback / Log4j, `java.util.logging`, syslog, nginx, structured / JSON,
zerolog, and access logs.

It also recognizes:

- The **.NET console logger** (`trce:`, `dbug:`, `info:`, `warn:`, `fail:`,
  `crit:`).
- A **lowercase level after a timestamp** (`2026-10-06T12:00:00Z error …`).
- **syslog lines with a message-level prefix** (`… sshd[123]: error: …`).
- **klog** (Kubernetes), where the level is the line's first letter
  (`E1006 12:00:00.000000 …`).
- **pino / bunyan numeric levels** (`"level":50`).
- A **JSON level key anywhere in the line** (`level`, `lvl`, `severity`,
  `levelname`), since a JSON record's keys come in any order.
- **Access logs** by status code: 5xx as an error, 4xx as a warning.

Outside those positions a level word has to be upper case, so the word "error"
inside a message doesn't recolor an INFO line. A line with no level, such as a
stack-trace frame, belongs to the record above it and takes that record's color.

### Warnings and errors on the scrollbar

A thin stripe over the scrollbar marks every WARN line in amber and every
ERROR or FATAL line in red, with the whole log mapped to its height, so you can
see where the trouble is without scrolling. Hover a mark to read its line, and
click it to go there. When a filter is on, the stripe marks the lines the filter
kept. A stack trace is marked once, at its record's first line.

## The control bar

Log mode adds a bar above the text with these controls, all reachable with Tab:

- A labelled **Follow** button.
- A **level list**: *All levels*, *Trace and up*, *Debug and up*, *Info and up*,
  *Warn and up*, *Error and up*, and *Fatal only*.
- A **filter field** for the records to show.
- A **hide field** for the records to hide.
- **Aa**, which makes both fields match case.

While a filter is on, the bar shows how much of the log is visible, for example
*65 of 185 lines*. **Log: Focus Filter** (`log.focusFilter`) puts the keyboard in
the filter field, and Escape returns to the log. While the keyboard is in the
bar, Find's Match Case key turns **Aa** on and off.

The bar and the palette commands show the same state: a level or pattern set
with **Log: Filter by Level**, **Log: Filter by Pattern** or **Log: Hide
Matching Records** appears in the bar.

## Follow and large logs

- **Follow** (`tail -f`): the Follow button in the control bar streams new lines
  as the file grows and auto-scrolls to the bottom. It keeps streaming even while
  the buffer is read-only.
- **Open at the tail**: very large logs open read-only at the end, so you're not
  waiting on a multi-gigabyte file to load from the top.

Logs open in **View mode** (read-only with an *Enable Editing* banner) by
default, since they're for reading.

### What Follow does while you read

New lines are appended without moving the caret or dropping your selection. The
view only stays pinned to the end while you are at the end; scroll up to read and
it stays where you are. Pausing Follow and turning it back on picks up from where
it stopped, so lines written in between aren't skipped.

A log that is being followed doesn't raise the "File Changed on Disk" prompt, and
followed lines aren't recorded as edits: the tab doesn't become unsaved and Undo
doesn't remove them.

Follow is available for local files only.

### Rotation

Follow continues across log rotation. When the file is renamed away or
truncated, Follow waits while the file is missing, then reads the new file from
its start and shows it in place of the old one. The status bar says the log
rotated.

If you have typed unsaved edits into the log, a rotation doesn't replace them.
Follow stops instead, with a message saying it stopped to keep your edits.

### The 12 MB follow limit

To bound memory, Follow adds at most **12 MB** to what the tab held when Follow
started. The limit counts only what Follow appends, so a large log that was
already open keeps its top. Once the appended text passes the limit, the oldest
lines are dropped and the control bar notes *older lines dropped*.

A tab that has dropped lines holds only the end of the log, so Editora refuses to
save it: saving would truncate the file on disk.

## Live logs from other places

**Log: Open Log Stream…** (`log.openStream`) shows a log that a command
prints as it grows, in a new read-only tab with the log viewer's level colors,
filters and Follow. Pick where it comes from:

| Source | What runs | What you type |
| --- | --- | --- |
| Container… | `docker logs -f --tail 1000` (or the container engine set in Settings) | a running container, picked from a list |
| Kubernetes… | `kubectl logs -f --tail=1000` | `pod/name`, `deploy/api -n shop` |
| systemd Unit… | `journalctl -f -n 1000 -o short-iso -u` | `nginx` |
| File over SSH… | `ssh host tail -n 1000 -F path` | `user@host:/var/log/app.log` |
| Command… | the command you type | `docker compose logs -f api` |

Each one starts with the last 1000 lines. A command and a systemd unit run where
the project's tools run, which is the server for a remote project; the others
run on this computer. File over SSH uses your system's `ssh` with
`BatchMode=yes`, so it needs a key or agent, not a password.

Follow off pauses the tab: the command keeps running and its new lines wait
(up to 12 MB) until you follow again. Closing the tab stops the command. When
the command ends, the status bar says so with its exit code.

## Summary of a log

**Log: Summarize** (`log.summarize`) opens a Markdown report of the active log
in a new tab, of the whole log even while a filter is on:

- How many lines and records it has, and the time it covers.
- How many records there are at each level.
- Records over time: one row per interval (a second up to a day, chosen so that
  there are at most 40 rows), with how many of them were warnings or errors,
  and a bar to see the bursts at a glance.
- The messages that repeat most, counted by their *template*: the line with its
  timestamp dropped and its numbers, hex ids, UUIDs and quoted values masked,
  so `user 41 timed out after 3000 ms` and `user 7 timed out after 2500 ms`
  count as one. Each comes with the line it first appears on.
- The longest silences: the biggest gaps between one timed line and the next,
  with their line numbers.

The report is an ordinary Markdown file: preview it, edit it, or save it to
keep it.

## Merging logs

**Log: Merge Logs…** (`log.mergeLogs`) opens two or more log files as one,
read-only, with their records in the order they were written: an API's log
and its database's, or the same service on two machines. Each line has a
strip in its file's colour at the right edge and the file's name beside it.

Editora places records by their timestamps (see **Times** below), so a stack
trace stays with the line that logged it. Each file keeps its own order: a
line a thread wrote a little late isn't moved ahead of the lines above it.
When two records have the same time, the file chosen first comes first. Lines
above a file's first timestamp have no time and come at the top. A compressed
log (`.gz`) is read decompressed, and of a big file the last 16 MB is used.

The merged tab filters, searches and steps through problems like any log.

## Live filtering

Filter as you type by a **level floor**, a pattern of records to **show** and a
pattern of records to **hide**. Each pattern is a regular expression, or a
literal substring when it isn't a valid one. A stack trace inherits its record's
level, so an exception stays visible when you filter to `WARN` and above.

Patterns ignore case unless **Aa** is on. When a pattern isn't a valid regular
expression, the field gets an amber border and its tooltip says it is being
matched as plain text. To show or hide several things, put them in one pattern
with `|`: `timeout|refused`.

While a filter shows records by a pattern, its matches are highlighted in the
visible lines, as Find highlights its matches.

Each log keeps its level floor and patterns: when you open it again, in this
session or the next one, it opens filtered the way you left it. Clear the
filter and it opens in full again.

### Filtering by record

A filter keeps or hides whole **records**, not single lines. A record is a line
with a level plus the level-less lines after it, such as a stack trace.

- The level floor keeps a record when its first line is at or above the level.
- A pattern that matches **any line** of a record shows all of it. A match inside
  a stack trace brings the line that says what failed, and a matched ERROR line
  keeps its stack trace.

The hide pattern works on records too:

- When a record's **first line** matches, the whole record is hidden, stack
  trace and all. `HealthCheck` hides the health-check records.
- Any **other line** that matches is hidden on its own. `at org.springframework`
  shortens a stack trace and leaves the error that it belongs to.
- Hiding wins: a record that matches both patterns is hidden.

A record is judged by its first line, not by a line further down, so that a
followed log can show a new record as soon as it arrives.

### A pattern that takes too long

Some patterns, such as `(a+)+$` or `(.*a){20}`, can take minutes on a line they
almost match. Editora stops matching a line after 50 ms and counts it as not
matching. After three such lines it stops using the pattern, and the bar says
*pattern too slow on some lines*. Change the pattern to filter again.

Filtered lines keep their real line numbers in the gutter, and applying or
clearing a filter keeps you on the line you were on. Filtering works together
with Follow: new records are filtered as they arrive.

## Times

Editora reads the time each line was written from its timestamp: ISO and
Logback dates (`2026-11-24 10:05:30,123`, `2026-11-24T10:05:30Z`), Tomcat's
`24-Nov-2026 10:05:30.123`, syslog's `Nov 24 10:05:30`, klog's
`I1124 10:05:30.000000`, an access log's `[24/Nov/2026:10:05:30 +0000]`, and a
JSON line's `time`, `timestamp` or `ts`, as text or as an epoch number. A line
without a timestamp, such as a stack-trace frame, was written at the time of the
line above it. A time with no zone is in your computer's zone, and a syslog date
with no year is in the current year.

- **Log: Go to Time…** asks for a time and moves to the first line written then
  or later. Type a time of day (`10:05`, `10:05:30.250`), which is on the caret
  line's date, or a full date and time.
- **Log: Filter by Time…** shows only the records written between two times:
  `10:05 - 10:20`, `2026-11-24 22:00 .. 2026-11-25 02:00`. Leave a side empty
  for no limit (`10:05 ..`). The end covers all of what it names, so `10:20`
  runs to 10:20:59. The control bar shows the range next to the line count.
- **Log: Show From Caret Line** and **Log: Show Up To Caret Line** set one end
  of the range to the time of the line at the caret.
- **Log: Clear Time Range** removes the range and keeps the rest of the filter;
  **Log: Clear Filter** removes everything.

The time range works with the level floor and the patterns: a record has to
pass all of them. Lines above the first timestamp have no time, so a range
hides them.

## Commands

In a log, **Next Problem** and **Previous Problem** step through the lines at
warning level or higher, since a log has no language server to report problems.
The keys are F8 and Shift+F8, F2 and Shift+F2 in the IntelliJ keymaps, and
`M-g M-n` and `M-g M-p` in Emacs.

| Action | Command | Default key |
| --- | --- | --- |
| Toggle Follow (tail -f) | `log.toggleFollow` | Ctrl+Alt+Shift+L (macOS: ⌥⌘⇧L; Emacs: `C-c C-l t`) |
| Focus the filter field | `log.focusFilter` | Ctrl+Alt+Shift+F (macOS: ⌥⌘⇧F; Emacs: `C-c C-l f`) |
| Filter by level | `log.setLevelFilter` | |
| Filter by pattern | `log.setRegexFilter` | |
| Hide records matching a pattern | `log.setHideFilter` | |
| Clear the filter | `log.clearFilter` | |
| Go to the first line at a time | `log.goToTime` | |
| Filter by time range | `log.setTimeRange` | |
| Show from / up to the caret line's time | `log.showFromCaret` / `log.showUpToCaret` | |
| Clear the time range | `log.clearTimeRange` | |
| Open a live log from a container, pod, unit, SSH or a command | `log.openStream` | |
| Summarize the log (levels, over time, repeated messages, silences) | `log.summarize` | |
| Merge several logs into one, by time | `log.mergeLogs` | |
| Next / previous line at warning level or higher (**Log: Next Warning or Error**, **Log: Previous Warning or Error**) | `log.nextError` / `log.previousError` | Next / Previous Problem |
| Show the file as a log, or stop (toggle) | `log.viewAsLog` | |
| Enable/disable the feature | `view.toggleLogViewer` | |
