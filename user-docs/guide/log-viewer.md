---
title: Server log viewer
description: Log files get severity highlighting, a tail -f Follow button that survives rotation, open-at-the-tail for huge logs, and live level + regex filtering by record.
category: Workspace
order: 5
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

A plain-text file whose name doesn't settle it, such as `server.out`,
`worker.err`, or a file with no extension (like most of `/var/log`), is checked
by content: it opens as a log when enough of its first lines carry a level or
start with a timestamp.

For anything else, **Log: View as Log** (`log.viewAsLog`) shows the current file
as a log. It's a toggle: run it again to go back to the plain view. The choice
lasts for the session.

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

## The control bar

Log mode adds a bar above the text with three controls, all reachable with Tab:

- A labelled **Follow** button.
- A **level list**: *All levels*, *Trace and up*, *Debug and up*, *Info and up*,
  *Warn and up*, *Error and up*, and *Fatal only*.
- A **filter field** for a pattern.

While a filter is on, the bar shows how much of the log is visible, for example
*65 of 185 lines*. **Log: Focus Filter** (`log.focusFilter`) puts the keyboard in
the filter field, and Escape returns to the log.

The bar and the palette commands show the same state: a level or pattern set
with **Log: Filter by Level** or **Log: Filter by Pattern** appears in the bar.

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

## Live filtering

Filter as you type by a **level floor** and a **regex** (or a literal substring
when the query isn't valid regex). A stack trace inherits its record's level, so
an exception stays visible when you filter to `WARN` and above.

The pattern is case-insensitive. When it isn't a valid regular expression, the
field's tooltip says it is being matched as plain text.

### Filtering by record

A filter keeps or hides whole **records**, not single lines. A record is a line
with a level plus the level-less lines after it, such as a stack trace.

- The level floor keeps a record when its first line is at or above the level.
- A pattern that matches **any line** of a record shows all of it. A match inside
  a stack trace brings the line that says what failed, and a matched ERROR line
  keeps its stack trace.

Filtered lines keep their real line numbers in the gutter, and applying or
clearing a filter keeps you on the line you were on. Filtering works together
with Follow: new records are filtered as they arrive.

## Commands

None of the log commands has a default key; run them from the palette or bind
them in [Keymaps](/docs/keymaps).

| Action | Command |
| --- | --- |
| Toggle Follow (tail -f) | `log.toggleFollow` |
| Filter by level | `log.setLevelFilter` |
| Filter by pattern | `log.setRegexFilter` |
| Focus the filter field | `log.focusFilter` |
| Clear the filter | `log.clearFilter` |
| Next / previous line at warning level or higher (**Log: Next Warning or Error**, **Log: Previous Warning or Error**) | `log.nextError` / `log.previousError` |
| Show the file as a log, or stop (toggle) | `log.viewAsLog` |
| Enable/disable the feature | `view.toggleLogViewer` |
