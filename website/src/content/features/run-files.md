---
title: "Run files"
group: "Run & debug"
order: 1
beta: false
summary: "Run a Java compact source file, Python, or shell script from a gutter \u25b6 \u2014 output and <code>stdin</code> in the Run console, with clickable stack traces and per-file arguments."
---

A green ▶ in the gutter runs the current file — a **Java compact source file** (JEP 512), a **Python** script, or a **shell** script.

Output streams to the Run console, which also accepts **stdin** so `readln`-style programs work, and **stack-trace lines are clickable** (Java, Python, and Node frames) to jump to the file and line. Pass per-file **program arguments** (remembered across runs), and re-run the last file with *Rerun Last Run*. Bind it to `C-c r` or run from the palette.
