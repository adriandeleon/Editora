---
title: HTTP client
description: Send HTTP requests from .http and .rest files, with environments, variables, and request chaining.
category: Run & debug
order: 4
---

Open a `.http` or `.rest` file and click the green ▶ next to a request to send
it. It uses the JDK's built-in HTTP client, so there's no external tool to
install. The HTTP client is **on by default**; its switch is the HTTP Client
section of **Settings → Web** (or `view.toggleHttpClient`).

The file format is JetBrains' HTTP request format, plus some syntax from the VS
Code REST Client extension (`$dotenv`, `$processEnv`, `$datetime`, `$shared`, and
`{{name.response…}}` references).

## Writing requests

Separate requests with a `###` line. A request is a method and URL followed by
headers and an optional body, separated from the headers by a blank line:

```http
### Get a user
GET https://api.example.com/users/42
Accept: application/json

### Create one
POST https://api.example.com/users
Content-Type: application/json

{ "name": "Ada" }
```

- **The request line.** The method can be any case (`get` is sent as `GET`), and
  custom methods such as `PROPFIND` or `QUERY` are sent as written. A line with
  only a URL is a `GET`. A URL without a scheme gets `http://`, and
  `GET /path` with a `Host:` header takes its host from that header. A query can
  continue on indented lines starting with `?` or `&`. `HTTP/1.1` or `HTTP/2` at
  the end of the line chooses the protocol; without it, `http://` requests use
  HTTP/1.1 and `https://` requests negotiate HTTP/2.
- **Headers.** An indented line under a header continues its value. A `Host`
  header can be set to reach a virtual host by IP.
- **Comments.** Lines starting with `#` or `//` are comments, inside a body too:
  they are not sent. To send such lines, put the body in a file and use
  `< ./file` (see [Chaining, bodies, and auth](#chaining-bodies-and-auth)).
- **A literal `{{`.** Write `\{{` to send `{{` as text (a template body, say);
  Import cURL writes every `{{` of a pasted command this way, so pasted text
  never reads your variables.
- **Not supported.** `GRAPHQL`, `WEBSOCKET` and `GRPC` requests are reported as
  not supported and not sent. JavaScript pre-request scripts (`< {% … %}`) and
  response handlers (`> {% … %}`) run: see
  [Tests and response handlers](#tests-and-response-handlers).

### Directives

Comment lines above the request line set options for that request:

| Directive | Effect |
| --- | --- |
| `# @name login` | Names the request, for [chaining](#naming-a-request) |
| `# @no-redirect` | Don't follow redirects |
| `# @no-cookie-jar` | Don't send or keep cookies |
| `# @no-auto-encoding` | Send the URL exactly as written |
| `# @no-log` | Keep the response out of the saved [history](#history) |
| `# @timeout 5 s` | Give up when the server is silent this long |
| `# @connection-timeout 2 s` | Give up connecting after this long |
| `# @assert status == 200` | Test the response (see [Checks without JavaScript](#checks-without-javascript)) |
| `# @capture token = $.token` | Keep a value of the response as `{{token}}` |

Timeouts take `ms`, `s` or `m`; a number on its own is seconds. Without them a
connection gets 15 s, the response headers 60 s, and the whole body 5 minutes.

## Variables and environments

`{{name}}` is replaced by a variable's value, in the URL, headers, body, and
`>>` paths. Variables come from:

- **`@name = value` lines** in the file. A declaration applies to the requests
  below it; a later one with the same name takes over from there.
- **Environments**, defined in `http-client.env.json`, with secrets in
  `http-client.private.env.json` (keep that one out of Git). Editora reads them
  from the request file's folder, or the nearest folder above it, up to the open
  project's root, else the file's Git (or Mercurial, Subversion) repository root;
  outside a repository only the file's own folder counts. Pick one as described
  in [Choosing an environment](#choosing-an-environment); the list includes
  environments defined only in the private file. A `$shared` section applies
  to every environment. The private file wins
  over the public one, and a named environment wins over `$shared`. Nested
  objects are reached with dots (`{{client.host.url}}`) or indexes
  (`{{users[0].name}}`), and a value may refer to another environment value
  (not to the file's `@var` lines). The files may hold `//` comments and trailing
  commas; one that can't be read is named in the environment menu, the
  response's warnings and the editor marks.
- **Dynamic variables**: `{{$uuid}}`, `{{$timestamp}}`, `{{$isoTimestamp}}`,
  `{{$randomInt}}` (or `{{$randomInt 1 10}}`), `{{$random.integer(1, 100)}}`,
  `{{$random.float}}`, `{{$random.alphabetic(8)}}`,
  `{{$random.alphanumeric(8)}}`, `{{$random.hexadecimal(8)}}`,
  `{{$random.email}}`, and `{{$datetime iso8601}}` / `{{$localDatetime
  "DD-MM-YYYY" -1 d}}` with a format (`iso8601`, `rfc1123`, or a Day.js or Java
  pattern; in Day.js, put literal text in `[brackets]`, as an unknown letter is
  reported) and an optional offset (`y`, `M`, `w`, `d`, `h`, `m`, `s`, `ms`).
  `{{$timestamp}}`, `{{$isoTimestamp}}` and `{{$datetime}}` are in UTC wherever
  you are; `{{$localDatetime}}` uses your time zone.
- **Your system**: `{{$env.NAME}}` or `{{$processEnv.NAME}}` reads an
  environment variable, and `{{$dotenv.NAME}}` reads a `.env` file beside the
  request file (a regular file of at most 1 MB inside that folder; a link that
  leads elsewhere is not read).

A variable that can't be resolved (a typo, no environment selected, a request
the file doesn't have) is underlined as you type; hover it for the reason.
When one of the file's environments defines it, a **Use environment dev** quick
fix selects that environment: it is in the right-click menu and in the Code
Actions popup (`lsp.codeActions`), which serves a `.http` file without a
language server. With another environment selected, the reason says which
environments define the variable. If you send it anyway it goes out as an empty
value, and the response lists it under its warnings, naming those environments
too. Typing `{{` offers the variables available at that point, the dynamic
variables, the keys of the `.env` file, and `name.response.` for each request in
the file.

### Choosing an environment

The environment is chosen per `.http` file, from the moment the file opens, so
the first run of a file already goes out in it:

- **The Env pill** sits beside the Editor / Split / Preview toggle in every view
  mode: "● Env: dev", or a muted "No environment". Click it for its menu: the
  file's environments, **No environment**, any problem reading the env files,
  and **Open http-client.env.json** (and the private file, when there is one).
  In a folder without an env file, the menu says that no `http-client.env.json`
  was found beside the file.
- **The status bar** shows the same choice ("Env: dev", or "No environment") for
  the active `.http` file; click it for the same menu.
- **HTTP: Select Environment** (`http.selectEnvironment`) opens the menu from the
  keyboard, before any run too. The arrow keys and Enter pick; Escape goes back
  to the editor.

![environments.http open with the environment menu dropped from its Env pill: dev, prod and local, No environment ticked, and Open http-client.env.json and Open http-client.private.env.json](/screenshots/docs/http-environments.png "The Env pill's menu: pick an environment, or open the files that define them.")

The choice is remembered with the file's history, so it comes back when you
reopen the file. Each response records the environment it was sent in: the
response view shows it beside the time and size ("env dev"), and History keeps
it.

### Secrets and trusted folders

A request that reads `$processEnv`, `$env` or `$dotenv` (in any form: directly,
through an `@var` line or an environment value, in a header, a `<@` body file or
a multipart `<@` part) only runs from a **trusted** folder. In a folder you
haven't trusted, Editora asks first, naming the variables as written (never their
values) and the hosts the request goes to, or that a host is chosen by an earlier
response; a redirect may lead to another host. **Cancel** is the default button.
If you decline, nothing that reads a secret is sent; in a Run All, the requests
that read none still go out.
This is the same trust that lets a project's build scripts run, so a project
you've trusted is not asked again. Requests that read no secrets are never
asked about.

## Chaining, bodies, and auth

- **Request chaining** references an earlier request's response, so a login can
  feed a token into the next call. See [Naming a request](#naming-a-request).
- **Bodies** can be inline, or come from a file: `< ./body.json` sends the file
  as is, and `<@ ./body.json` (or `<@latin1 ./body.json`) fills in its
  `{{variables}}` first. The path may use variables too (`< ./fixtures/{{env}}.json`). Text bodies are encoded in the charset the
  `Content-Type` header names (UTF-8 if none).
- **Multipart** form bodies take inline parts and `< ./file` parts, and fill in
  variables in part headers and file paths. File parts are streamed from disk,
  so a large upload isn't read into memory. URLs are percent-encoded automatically, emoji and
  non-ASCII host names included. Body files and `>>` targets stay inside the
  request's folder; a symlink that leads out of it is refused.
- **Basic / Digest auth** shorthand: `Authorization: Basic user password` is
  encoded for you, and `Authorization: Digest user password` answers the
  server's challenge (MD5, SHA-256 or SHA-512-256). The password may contain
  spaces.

### Naming a request

A later request refers to an earlier one by name, as
`{{name.response.body.$.path}}`, `{{name.response.headers.Header-Name}}` or
`{{name.response.status}}`. A request gets its name in one of two ways:

- A `# @name login` comment (or `// @name login`) anywhere in the request's
  section before the request line, whether or not the `###` separator above it
  has a title.
- Its `### Title`, when the request has no `@name`. A `@name` takes precedence
  over the title.

```http
### Sign in
# @name login
POST https://api.example.com/login
Content-Type: application/json

{ "user": "ada", "password": "{{password}}" }

### Fetch the profile
GET https://api.example.com/me
Authorization: Bearer {{login.response.body.$.token}}
```

Each `.http` file keeps the responses of its runs while it is open, so you can
run `Sign in` once and then run `Fetch the profile` on its own. Running the
whole file (`http.runFile`) sends the requests in order, so each one can refer
to those before it. The captured responses are cleared when you pick another
environment or close the file. A reference to a request that hasn't run, or that
failed, is listed in the response's warnings.

### Saving a response to a file

A `>>` line after a request writes the response body to a file in the request's
folder; the path can use `{{variables}}`. The two forms differ in what they do
when the file already exists:

- `>> file.json` never touches an existing file: it writes `file-1.json`, then
  `file-2.json`, and so on.
- `>>! file` replaces an existing file, but only with a complete, successful
  response. An error status, or a body that was cut short, leaves the file as it
  is. Before replacing, Editora records the previous content in
  [Local History](/docs/workspace#local-file-history) (*Before HTTP response*), and it leaves the file alone when the file
  is open in the editor with unsaved changes.

The response's **Notes** tab reports what happened to every target: which files
were written or replaced, and which were not and why.

## Tests and response handlers

A request can test its response and keep values from it for later requests,
with `# @assert` and `# @capture` lines or with a JavaScript response handler.
Each test shows in the response view's **Tests** tab, with ✓ or ✗ and why it
failed. The tab's count reads passed of total (`2/3`), red when a test failed,
and a run with a failed test opens on that tab. The request's line in the editor
shows the result too (see [Results on the request lines](#results-on-the-request-lines)).

### Checks without JavaScript

`# @assert` and `# @capture` lines go above the request line, with the other
[directives](#directives). They need neither Node.js nor a trusted folder:

```http
### Sign in
# @assert status == 200
# @assert header Content-Type contains json
# @assert $.user.id == 7
# @assert $.token exists
# @capture token = $.token
POST https://api.example.com/login
Content-Type: application/json

{ "user": "ada", "password": "{{password}}" }

### Fetch the profile
GET https://api.example.com/me
Authorization: Bearer {{token}}
```

An `@assert` reads one of these from the response:

- `status`, or `time` (milliseconds);
- `header Name` (the first value; names are case-insensitive);
- `body` (the text);
- a JSONPath into a JSON body: `$.user.id`, `$.items[0].name`, `$["a b"]`.

It compares it with `==`, `!=`, `<`, `<=`, `>` or `>=` (as numbers when both
sides are numbers, otherwise as text), `contains` (text, or an element of an
array), `matches` (a regular expression found anywhere; anchor it with `^…$`),
or checks `exists` / `!exists`. The value is written as it is: `200`, `true`,
`null`, `"quoted text"` or bare text, never a `{{variable}}`.

`# @capture name = subject` keeps `status`, `header Name`, `body` or a JSONPath
value as `{{name}}`. A capture that finds nothing clears the name and says so in
the warnings.

### Response handlers

A JavaScript response handler runs after the response arrives, on your own
[Node.js](https://nodejs.org). Write it after the request, inline or in a file
next to the `.http` file:

```http
### Sign in
POST https://api.example.com/login
Content-Type: application/json

{ "user": "ada", "password": "{{password}}" }

> {%
  client.test("signed in", () => {
    client.assert(response.status === 200, "status " + response.status);
    client.assert(response.body.token, "no token");
  });
  client.global.set("token", response.body.token);
  client.log("token for", response.body.user.name);
%}

> ./checks/login.js
```

Handlers use the JetBrains API:

| Object | What it has |
| --- | --- |
| `client` | `test(name, fn)`, `assert(condition, message)`, `log(…)` (also `console.log`), `exit()`, `global.get/set/clear/clearAll/isEmpty` |
| `response` | `body` (parsed when the response is JSON, otherwise text), `status`, `headers.valueOf(name)` / `valuesOf(name)`, `contentType.mimeType` / `.charset`, `cookies()`, `cookiesByName(name)` |
| `request` | `method`, `url`, `body` and `headers` as sent (`tryGetSubstituted()`, and `getRaw()` as written), `environment.get(name)`, `variables.get(name)` |
| `jsonPath(value, path)` | A value from an object or JSON text: `jsonPath(response.body, "$.items[0].id")` |

A test whose function returns a promise is waited for, so `fetch` can be used
with `await`. An error stops the handler and shows in the Tests tab with its
line in the `.http` file (or the handler file); with no test to count, the
tab's heading reads **Response handler error** and its count is a red ✗.
`client.log` lines show there too, under **Script output** when they are all
there is. `xpath`, streamed responses and
`crypto.subtle` are not supported yet.

### Pre-request scripts

A pre-request script runs before its request is resolved and sent. Write it
above the request line, inline or in a file:

```http
### Signed upload
< {%
  const body = request.body.tryGetSubstituted();
  const signature = crypto.hmac.sha256()
    .withTextSecret(request.environment.get("signingKey"))
    .updateWithText(body)
    .digest().toHex();
  request.variables.set("signature", signature);
  request.variables.set("token", jwt.sign({ sub: "ada" }, request.environment.get("signingKey"), { expiresIn: 300 }));
%}
POST https://api.example.com/upload
X-Signature: {{signature}}
Authorization: Bearer {{token}}
Content-Type: application/json

{ "name": "report", "size": {{size}} }
```

A pre-request script has `client` (as in a handler), `request` (`url`, `headers`
and `body` with `tryGetSubstituted()` for the request as far as it resolves
before the script and `getRaw()` as written, `method`, `environment.get`,
`variables.get`) and:

| Helper | What it does |
| --- | --- |
| `request.variables.set(name, value)` | A variable for this request only, used as `{{name}}`. It wins over every other variable, including the file's `@name = value` lines |
| `crypto.sha256()`, `crypto.md5()`, `crypto.sha1()`, `crypto.sha384()`, `crypto.sha512()`, `crypto.sha3("256")` | A hash: `.updateWithText(text)`, `.updateWithHex(hex)` or `.updateWithBase64(text)`, then `.digest().toHex()` or `.digest().toBase64(urlSafe)` |
| `crypto.hmac.sha256()` (and the others) | An HMAC: `.withTextSecret(secret)`, `.withHexSecret(hex)` or `.withBase64Secret(text)` first, then as a hash |
| `jwt.sign(payload, key, options)` / `jwt.verify(token, key, options)` / `jwt.decode(token)` | JSON Web Tokens with a secret (HS256/384/512) or a PEM key (RS, PS and ES algorithms); options include `algorithm`, `expiresIn`, `audience`, `issuer`, `subject` |

`crypto` and `jwt` work in response handlers too. If a pre-request script can't
run (an untrusted folder, no Node.js, an error, a timeout), its request is
**not sent**, and the response view opens on a **Pre-request script did not
run** card that says why (see [When a request fails](#when-a-request-fails));
the other requests of a Run All still run. A script that stopped with an error
shows it in the Tests tab, under **Pre-request script error**; the card's
**Show script output** button opens it. `client.exit()` ends the script and
sends the request.

### Debugging scripts

**HTTP: Debug Request at Caret** (`http.debugRequest`) sends the request at the
caret with its pre-request scripts and response handlers under the
[JavaScript debugger](/docs/javascript-typescript#debugging). It is also **Debug Request** in
a `.http` file's right-click menu, and what Debug does in a `.http` file. A
script stops at its breakpoints with the call stack, variables, watches and
stepping of any JavaScript session. When you continue, the request goes on.

- Set breakpoints on the lines of a `{% %}` block in the `.http` file itself,
  or in a `./check.js` script file. Elsewhere in a `.http` file there is
  nothing to stop at, so no breakpoint is set there.
- It needs JavaScript debugging, as for a `.js` file:
  [install vscode-js-debug](/docs/debugging#installing-adapters), and keep
  **Enable debugging (DAP)** on in **Settings → Debugging** (it is on by default).
- A paused script is not stopped by the script timeout. If the debugger hasn't
  attached after 30 seconds, the script is stopped.
- **Restart** doesn't send the request again; run Debug Request again.
- The script runs as usual: only from a trusted folder, without your
  environment variables, under Node's permission model. Node's inspector is
  allowed only for Debug Request, on a random port on this machine. Node can
  let a debugger in under its permission model from Node.js 24.12 on; on an
  older Node, a script being debugged runs without the permission model, and
  the Debug console, the status bar and the response's warnings say so.

### Session variables

What `client.global.set` or `@capture` keeps is used by later requests as
`{{name}}`: by the next requests of a **Run All**, or when you run them one by
one, for as long as the file is open. It takes precedence over an environment
variable of the same name, and an `@name = value` line in the file takes
precedence over it. Its value is sent as it is: a `{{…}}` inside it is not
filled in. Session variables are cleared when you pick another environment or
close the file. A `{{name}}` the file sets this way (with `@capture`, or with
`client.global.set` or `request.variables.set` in an inline script or a script
file the file runs) is not underlined as unresolved. After you edit a script
file, the underlines catch up the next time the `.http` file is checked: when
you edit it or pick another environment.

`client.global.headers.set(name, value)` adds a header to the requests that
follow, and to its own request when a pre-request script sets it. A request's
own header of that name wins, a `Host` header is never set, and
`client.global.headers.clear(name)` removes one. Global headers are cleared with
the session variables.

### What a handler may do

Handlers and pre-request scripts are code that came with the folder, so they
only run from a **trusted** folder: Editora asks once per run, with **Cancel** as
the default button, and the answer is the same trust that
[secrets](#secrets-and-trusted-folders) and build scripts use. In a folder you
don't trust, handlers are skipped with a warning while `@assert` and `@capture`
still run, and a request with a pre-request script is not sent. A script file
must be in a trusted folder too, and at most 1 MB.

Node.js is started without your environment variables, so a handler can't read a
token from `process.env`. On Node.js 20 or later it runs under Node's permission
model: it can't write files, start processes or use worker threads. A value the
request read from `$processEnv`, `$env` or `$dotenv` reaches the handler masked.
A script **may** make network requests. It is stopped after 10 seconds. Scripts
in a remote (SFTP) file don't run.

Scripts never see secrets: `request.environment.get` returns environment values
as written, so `{{$dotenv KEY}}` stays that text. Keep a signing key in
`http-client.private.env.json` as a plain value. A value a script sets is sent as
it is, never filled in, so a script can't make a request read `$env` or
`$dotenv`. When a request that reads a secret goes to a host built from a
script's value (or an earlier response's), the secrets prompt says the host is
chosen by an earlier response or a script.

**Settings → Web** turns scripts off, picks the Node.js to use and sets the
timeout. Left empty, scripts run on the same Node.js as Run: a project's
`.nvmrc` or `.node-version`, then the **Node.js** setting, then `node` on the
`PATH` (see [Which Node.js runs](/docs/javascript-typescript#running-nodejs-scripts)). A Detected line
under **Node.js for HTTP scripts** says whether that node is there (*node: found
(/usr/bin/node)* or *node: not found*), and the script rows are greyed out while
the HTTP client or its scripts are off. The
[Doctor](/docs/troubleshooting#doctor) shows which Node.js runs them and whether the permission
model is on.

## Running and the response

| Action | Command | Emacs key | Other keymaps |
| --- | --- | --- | --- |
| Run the request at the caret | `http.runRequest` | `C-c C-c` (or the gutter ▶) | CUA, Sublime Text, VS Code, IntelliJ: Ctrl+Alt+R |
| Run every request in the file | `http.runFile` | `C-c C-r` | CUA, Sublime Text, VS Code, IntelliJ: Ctrl+Alt+Shift+Enter |
| Cancel the running request | `http.cancelRequest` | `C-c C-k` | CUA, Sublime Text, VS Code, IntelliJ: Ctrl+Alt+K |
| Select environment | `http.selectEnvironment` | (palette, Env pill or status bar) | — |
| Show or hide the results after request lines | `http.toggleInlineResults` | (palette) | — |
| Copy the request as a curl command | `http.copyAsCurl` | (Copy as cURL icon) | — |
| Turn a curl command on the clipboard into a request | `http.importCurl` | (palette) | — |
| Open the response body in its own tab | `http.openResponseInTab` | (Open in tab icon) | — |

The **Emacs key** column is the default Emacs keymap; **Other keymaps** gives
the chords the CUA, Sublime Text, VS Code and IntelliJ IDEA keymaps bind (— where
none does). On macOS those mostly use ⌘ for Ctrl; the
[keybindings reference](/keybindings) lists every keymap.

![An .http file with a GET request and its response handler, and the response pane beside it: a Run button, a 200 OK status pill, the response picker, Body, Headers, Request and Notes tabs, and the formatted JSON body](/screenshots/docs/http-response.png "Running a request: the response opens beside the file.")

In the other keymaps, Run Request is `Ctrl+Alt+R`, Run All is
`Ctrl+Alt+Shift+Enter` and Cancel is `Ctrl+Alt+K` (`Option+Cmd+R`,
`Option+Cmd+Shift+Enter` and `Option+Cmd+K` on macOS in the CUA, Sublime and VS
Code keymaps). The caret can be anywhere in a request's section, its
`###` line included. **Run File** and the editor's right-click menu on a `.http`
file run its requests too. While a request runs, its ▶ turns into a ■; click it
to cancel.

A `.http` file doesn't have to be saved to send requests. An untitled one uses
the project's environment files, and its responses are kept when you save it. A
request that needs the file's folder (a body file, a `>>` target, `$dotenv`) is
not sent until the file is saved. Requests from a remote (SFTP) file can't run
from this machine yet; Editora says so instead of sending them.

The response appears as the `.http` file's own **preview**, in the same
Editor / Split / Preview view every other rich file type uses, so the response
sits beside the request that produced it. Running a request from the gutter ▶
opens the split automatically, and the view mode is remembered per file. Switch
views with `view.togglePreview` and `view.toggleSplitPreview`, or the floating
3-mode toggle at the top right. Escape in the response view goes back to the
editor, and the body, headers and Request text answer your keymap's movement
and copy keys (`M-w`, `C-n`, `M->` in Emacs).

### Results on the request lines

After a request runs, its method and URL line shows how it went, after the
text: `200 OK · 83 ms · ✗ 1 of 3 tests`, `404 Not Found · 1 ms`,
`Connection refused` or `Not sent`. The status is in the outcome's colour,
failed tests and script errors are red, and passing tests show quietly
(`✓ 3 tests`). The gutter ▶ takes the colour of the outcome too: amber for a
4xx, red for a 5xx, a failure or failed tests, and the accent colour for a 3xx;
a success keeps the green ▶.

- Click a result to show that response (an Editor view switches to Split);
  hover it to see when it ran.
- The text is drawn after the line, never added to it: it isn't copied,
  searched, saved or undone.
- The results come from the file's [history](#history), so they are back when
  you reopen the file. A result stays while the method and URL line reads as it
  did when the request ran; edit that line and the result goes. Editing the
  headers or the body keeps it.
- A cancelled request leaves the previous result, since it isn't kept in
  History.

**Show results after each request line**, in the HTTP Client section of
**Settings → Web**, turns them off (on by default), as does
**HTTP: Toggle Results After Request Lines**.

### The response view

The response view has one header row, a row of tabs, and the tab's content.

**The header row** holds **Run**, with the Run key beside it, then the status
as a coloured pill (`200 OK`), the time and size (`32 ms · 44 B`), the
environment the request was sent in, and the **History** picker. Hover the
status for the full status line and when the request was sent. **Run** sends
the request the shown response came from again, found by its name (or its
method and URL) even if the file changed since; it turns into **Cancel** while
a request runs.

**The tabs** are:

| Tab | What it shows |
| --- | --- |
| **Body** | The response body (the default tab) |
| **Headers** | The protocol and status (`HTTP/2 200 OK`), then the headers as a name and value table, with their count on the tab |
| **Tests** | The request's [tests](#tests-and-response-handlers), the scripts' log and any error; the count reads passed of total, red when one failed |
| **Request** | The request as it was sent, variables filled in, and the address a redirect ended at |
| **Notes** | The warnings (unresolved variables, skipped scripts, headers that couldn't be sent, a redirect to another host that carried custom headers) and the files `>>` wrote; the count is amber when there is a warning |

Headers, Tests and Notes show only when they have something in them. A run
opens on **Tests** when a test or a script failed, and on **Body** when the
request got no response; warnings never switch tabs. Otherwise a tab you picked
stays across runs while the response has it. The arrow keys, Home and End move
between tabs. In the Headers table, right-click to copy a value, a header or all
of them; the copy key copies the selected rows (`http.copyHeaderValue`,
`http.copyAllHeaders`).

**The actions** sit at the end of the tab strip as icons: **Wrap** (long body
lines, `http.toggleResponseWrap`; the Request tab always wraps), **Copy as cURL**, **Open in tab** and
**Save response**. Their tooltips show their keys. **Clear** is in the **⋯**
menu. In a narrow panel the History picker narrows first, then the time and size
hide, then History moves to a second row; the icons fold into the ⋯ menu, and
then the tabs show only their counts.

**The body** is decoded from gzip or deflate and laid out by its type:

- JSON is pretty-printed with a 2-space indent, one field or array element per
  line (`"id": 42`), and `[]` / `{}` for empty ones. NDJSON shows one value per
  line, and **Save response** and **Open in tab** give a body sent as
  `application/x-ndjson` the `.jsonl` extension, so it opens as
  [JSON Lines](/docs/languages#supported-languages).
- One-line HTML is indented, one block element per line, with inline text kept
  on its block's line (`<h1>Not Found</h1>`). `pre`, `script`, `style` and
  comments are kept exactly; HTML the server already indented is left as it is.
- One-line XML is indented.
- An image is shown as a picture, with a caption (`PNG · 160 × 160 · 858 B`), a
  checkerboard under transparent pixels, and **Fit** / **100%**
  (`http.imageFit`, `http.imageActualSize`). Fit, the default, only ever
  scales a picture down.
- Other binary bodies are summarised.

The layout is only for the view: **Save response** and **Open in tab** have the
body exactly as received. Responses over 50 MB are cut, and the view says so.

**Copy as cURL** produces a command for bash or zsh that is safe to paste and
reproduces what was sent, including file bodies, forms, Digest auth and
redirects, multipart part types and the HTTP version, also for a response
restored from history. **Import cURL** reads a curl command from the clipboard
(a browser's "Copy as cURL", `curl.exe` or a `$ curl` line from a README) into the active `.http` file, or into a new tab when
no `.http` file is active; body lines the format would not send (from a `>`,
`<>` or `###` line on) become comments, so a pasted command never adds a
handler. **Save response** writes the body exactly as
received (an image saves as the image); the status-and-headers text is the
second choice in the dialog.

### Before, during and after a run

- **Before the first run**, and after **Clear**, the Body tab says what Run will
  send ("Run will send **Get a user**", the request at the caret), shows the Run
  key, and lists the file's requests. Click one, or move to it and press Enter,
  to run it.
- **While a request runs**, the status reads "Sending Get a user… 1.2 s"
  ("Sending 3 of 10: …" in a Run All), with a thin progress line under the
  header row. The Body tab shows a **Previous** row ("Previous: 200 OK ·
  16:42:31 · Get a user") instead of the old body; click it to see that response.
- **After Cancel**, the status reads "Cancelled after 4.2 s", the Previous row
  comes back, and History shows that response selected.
- **While a trust prompt waits** (for [secrets](#secrets-and-trusted-folders) or
  [scripts](#what-a-handler-may-do)), the panel says "Waiting for your answer…",
  with no clock: nothing has been sent yet, and the time starts when you answer.
  Cancelling while the prompt is open goes back to what was shown before.

### When a request fails

A request that got no response opens on the Body tab with a card:

- a title naming what went wrong: **Connection refused**, **Host not found**,
  **Host unreachable**, **Timed out**, **Certificate problem**, **Secure
  connection failed**, **Connection closed**, **Not an HTTP server**, **Invalid
  URL**, **Not sent** or **Pre-request script did not run**;
- the explanation, as a sentence you can select and copy, such as
  "Connection refused — is a server listening on …?";
- **Run again** and **Copy as cURL** (off for a request that was never sent),
  plus **Show script output** when a pre-request script did not run.

### Run All results

After a Run All, a list of its requests sits between the header row and the
tabs.

- **The title line** gives the outcome as a coloured pill ("3 of 10 failed",
  "All 10 succeeded", "Cancelled · 2 of 4 ran"), "tests failed in 2 requests" in
  red when any did, and how long the run took. **Only failures** keeps the
  requests that failed, weren't sent or had a failed test
  (`http.toggleOnlyFailures`), and the chevron collapses the list.
- **Each row** shows the status (the code, "failed" or "not sent"), the
  request's name with its method and path, its time, and its tests (`✗ 2/3`,
  `✓ 3` or `—`).
- **Selecting a row** shows that response in the tabs. The first failed
  request, else the first whose tests failed, is selected when the run ends.
  Enter or a double-click goes to the request in the editor; right-click to
  **Run again**, **Copy as cURL** or **Go to request**
  (`http.runSelectedAgain`, `http.goToSelectedRequest`).

The list takes at most about 40% of the panel and scrolls on its own. It goes
away at the next run and on **Clear**. A cancelled Run All keeps and lists the
requests that had finished.

### History

The **History** picker in the header row holds the file's recent responses,
each with its request's name and time. The last 10 are saved in Editora's config
folder and come back when you reopen the file. Saved bodies are capped at 1 MB,
and the values of credential headers (Authorization, cookies, and headers named
like tokens, secrets, API keys and passwords) are masked, and so is any value a
run read from `$processEnv`, `$env` or `$dotenv`. A request marked
`# @no-log` is never saved. **HTTP: Show Response History…**
(`http.showHistory`) opens the picker from the keyboard, and **HTTP: Run Shown
Request Again** (`http.runShownAgain`) sends the request of the past response
shown. **Clear**, in the ⋯ menu (`http.clearHistory`), empties the file's
history, saved copy included. The history follows a renamed or moved file, is
removed with a deleted one, and is shared by every window showing the file.
**Export Configuration** includes it only when you tick "Include HTTP response
history".

Cookies last for one run: Run All shares them between its requests, and a
request run on its own starts without any.

## From the command line

`editora http api.http` runs a file's requests without a window, as Run All
does, and prints each response and test result, for CI and scripts. Its
options choose the environment, set variables, and write a JUnit XML report;
it exits with 1 when a request or a test fails. See
[Running .http files](/docs/cli#running-http-files).
