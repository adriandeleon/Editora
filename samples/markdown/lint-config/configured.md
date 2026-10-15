# Configured lint

The `.markdownlint.jsonc` beside this file changes the rules for this folder: line length is on at 100
columns, so the long line below is flagged (MD013), and a repeated heading is not.

This line is deliberately longer than one hundred characters so that the configured line-length rule flags it.

## Repeated

## Repeated

Edit the config and save it: open files in this folder are linted again. *Markdown Lint: Disable Rule
for This Project* on an issue here adds the rule to that file.
