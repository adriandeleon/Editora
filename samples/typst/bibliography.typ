// Bibliographies — Typst formats citations and references from a .bib file (built-in, no package).
// Renders with --root set to this folder, so the sibling refs.bib resolves.
#set page(paper: "a5", margin: 2cm)
#set heading(numbering: "1.")

= Bibliographies

Earlier work noted that spatial distortions can damage documents in transit @kaluza1921,
and later research examined atmospheric channel effects @ricklin2008. Citations are
numbered and the reference list is generated automatically below.

#bibliography("refs.bib", style: "ieee")
