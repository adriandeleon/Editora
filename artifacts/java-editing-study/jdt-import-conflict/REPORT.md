# JDT LS completion imports a type that conflicts with a declaration in the same file

Prepared on 2026-09-19. This report has **not** been submitted upstream.

## Environment

- JDT LS reports `JDT Language Server (Standard)`, `1.61.0-SNAPSHOT`.
- Installed bundle: `org.eclipse.jdt.ls.core_1.61.0.202607301809.jar`.
- JDT core: `org.eclipse.jdt.core_3.47.0.v20260728-1818.jar`.
- Temurin JDK 25.0.4, Linux; an isolated Maven project and a sibling Eclipse workspace.
- `-Djava.lsp.joinOnCompletion=true` is enabled to isolate this semantic conflict from pending document lifecycle work.
- Reproduction uses Python's standard library and JSON-RPC directly. It does not load Editora code.

## Steps

Run with a supported Java runtime on PATH:

```sh
python3 reproduce.py --jdtls /path/to/jdtls --output /tmp/jdt-import-conflict
```

The script creates a temporary Maven project containing:

```java
package demo;
class ArrayList {}
class Conflict { void run() { new ArrayLi } }
```

It opens the file, invokes completion after `ArrayLi`, selects the external
`java.util.ArrayList` constructor, resolves it, and applies its replacement plus
`additionalTextEdits` against the original document. It makes no intervening change
or competing completion request between obtaining the item and resolving it.

## Actual result

```java
package demo;

import java.util.ArrayList;

class ArrayList {}
class Conflict { void run() { new ArrayList<>() } }
```

The missing semicolon is normal for this unfinished expression. Adding **only** the
semicolon and invoking JDK 25 `javac` produces:

```text
Conflict.java:3: error: ArrayList is already defined in this compilation unit
import java.util.ArrayList;
^
1 error
```

The portable [protocol transcript](protocol.json) contains the initialization
capabilities, exact completion request, item and resolved item. The unmodified
result is [Conflict.java.txt](Conflict.java.txt).

## Expected result

Selecting the external constructor should produce a qualified expression such as
`new java.util.ArrayList<>()`, without adding a conflicting single-type import.
The local `ArrayList` proposal should remain a separate choice.

## Reproducibility

The standalone run reproduced the conflict. Earlier Editora protocol probes also
observed it in 50/50 Maven and 25/25 Gradle rounds. Ordinary unimported types,
existing imports and wildcard imports passed after joining lifecycle work.

No client-side symbol-name heuristic is proposed: import conflict resolution needs
the server's Java binding information, especially in incomplete source.

## Separate finding: proposal lifetime across requests

The same standalone client can test an additional issue:

```sh
python3 reproduce.py --jdtls /path/to/jdtls --output /tmp/jdt-proposal-lifetime \
  --supersede-before-resolve
```

It requests completion twice at the **same** position in an unchanged document,
then resolves the first item. The installed server returns JSON-RPC error `-32603`
with `IllegalStateException: Invalid completion proposal`, from
`CompletionResolveHandler.resolve:104`. The exact first item and error are in
[proposal-lifetime.json](proposal-lifetime.json). It then resolves a fresh item to
run the independent same-file import check.

Expected: an earlier item should remain resolvable across a subsequent request,
particularly when the document has not changed. This matters when a client keeps
a usable popup visible while refreshing an incomplete list or fetching documentation.
The installed `CompletionHandler.computeContentAssist` clears its proposal cache at
the start of each request, consistent with this reproduction.

Editora rejects stale ranges and safely translates still-valid text edits, but cannot
recover missing server-side bindings from an invalidated token. Fetching an arbitrary
same-label replacement is unsafe for overloaded or ambiguous symbols. This finding
is separate from the unexplained long-run `String` popup timeouts; it does not establish
their cause. Neither report has been submitted upstream.
