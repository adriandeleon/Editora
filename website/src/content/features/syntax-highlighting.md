---
title: "Syntax highlighting"
group: "Code intelligence"
order: 1
beta: false
summary: "TextMate grammars (via tm4e) for 21 languages: Java, Python, Rust, Go, Kotlin, C/C++, C#, Ruby, SQL, Markdown, and more."
---

Highlighting uses **TextMate grammars** (via tm4e) for 21 languages — Java, XML, shell, PowerShell, DOS batch, Python, Groovy, Kotlin, Ruby, C, C++, Rust, Go, C#, Markdown, JSON, CSS, HTML, YAML, INI, and SQL — plus TypeScript/JavaScript, PHP, Lua, Dockerfile, Terraform, TOML, and more added alongside their language servers.

Tokenization is **stateful** (it carries grammar state across lines, so block comments and heredocs highlight correctly) and **incremental** — an edit re-tokenizes only from the changed line, off the UI thread. Token colors are themed per editor theme.
