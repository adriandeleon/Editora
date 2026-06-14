---
title: "Spell checking"
group: "Editing"
order: 5
beta: false
summary: "Red wavy underlines with right-click suggestions, Add-to-Dictionary, and Ignore \u2014 full text for prose, comments &amp; strings for code. Pure-Java Hunspell; English, Spanish, French."
---

Misspelled words get a red wavy underline; right-click for **suggestions** (click one to replace), **Add to Dictionary**, or **Ignore**. In source files only comments and string literals are checked (identifiers aren't flagged); plaintext and Markdown are checked in full.

It's powered by Apache Lucene's pure-Java **Hunspell** engine — no native dependency. Ships **English (en_US, en_GB)**, **Spanish**, and **French**; pick a dictionary per file with *Spell Check: Set Language…*, or set a default in Settings. Your added words live in `dictionary.txt`.
