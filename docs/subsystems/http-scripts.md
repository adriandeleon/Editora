# HTTP Client scripts and checks

What runs around an HTTP Client request: its JavaScript pre-request scripts before it is resolved, then, after its
response arrives, `# @assert` / `# @capture` checks and JavaScript response handlers, all on the user's Node.js.
Everything here runs on the run's worker, one request at a time, so a value one request stores is what the next one
sends.

## Pieces

- **Parsing** — `http/HttpFile`: `# @assert …` and `# @capture name = …` comment lines above the request line become
  `Directives.checks()` (`http/HttpChecks.parse`); `> {% … %}` and `> ./file.js` become `Parsed.handlers()`
  (`HttpFile.Handler`: inline source with its file line and column, or a path); `< {% … %}` / `< ./pre.js` above the
  request line become `Request.preScripts()` / `Parsed.preScripts()`. An unclosed `< {%` takes the rest of its
  section, so that section has no request.
- **Checks** — `http/HttpChecks.evaluate`: pure Java on `JsonPath`; literals only (no `{{…}}`), so a check never
  reads a secret and needs no trust. A `matches` regex runs against a deadline (`Deadline` char sequence).
- **Session variables** — `CapturedResponses` keeps a globals map beside the captured responses (same lifetime and
  environment-reset rule). `HttpVars.scope` layers it over the environment and under the file's `@var` lines, and
  inserts each value escaped (`HttpVars.escape`), so a response can never make a request resolve `{{$env …}}`.
- **Service seam** — `HttpClientService.sendOne` runs one request: `preRequest` (the request's dry resolution as
  `PreInput`, `ResponseScripts.runPre`, its `GlobalChange`s applied); a `PreResult.notSent()` makes the result an
  unsent report (`SecretApproval.unsentReport`, so no secret value); otherwise the request is sent as
  `withScriptVariables(…)` (escaped pairs with a third "script" element, so `HttpVars` records `sessionReads`) and
  `withGlobalHeaders(…)` (the request's own header wins), then `afterResponse` runs checks and
  `ResponseScripts.run`, applies the `GlobalChange`s, and attaches an `HttpTests` (pre-request tests first). `ResponseScripts.beforeRun` is
  called once per run before the first send (the trust question). `ResponseScripts.OFF` keeps the old "not run"
  warning.
- **Node runner** — `http/NodeResponseScripts` (one per run, built by `ui/HttpScriptGate`) and the pure
  `http/HttpScripts` (stdin JSON, result line, flags, environment). The shim is the resource
  `com/editora/http/http-handler-shim.js`, copied to `<config>/cache/http-scripts/http-handler-shim-<hash>.js`
  (excluded from config export).
- **UI** — `ui/HttpClientPanel` Tests block and badge, `HttpStatusText.testsBadge` / `testsFailedSuffix`,
  `HttpHistoryCell` mark, `HttpHistoryEntry.storedTests` / `restoredTests` (history schema 4).

- **Hosts the prompt can't know** — `HttpVars.Unresolved.sessionReads()` follows session variables and script
  variables through `@var` lines and environment values; `SecretApproval.preview` treats a host made of one (or, for
  a request with a pre-request script, of a name nothing defines yet) like one chosen by a response: named as a
  dynamic host, approved for any host at the send.
- **Global headers** — `CapturedResponses.setGlobalHeader` (never `Host`), cleared with the session.

## Protocol with the shim

One process per handler: `node [--permission --allow-fs-read=<shim>] <shim>` in the `.http` folder. Stdin is one
JSON object: `nonce`, `script` (`source`, `filename`, `line`, `column`), `response`, `request` (as sent, secrets
masked with `SecretMask`, plus `urlRaw`/`headersRaw`/`bodyRaw` as written), `environment` (env-file values as
written), `variables` (the request's `@var` lines as written), `globals`. A pre-request script gets `"mode": "pre"`, no
`response`, and the request as its dry resolution (`HttpScripts.preInput`). Stdout's last line starting with the
nonce is the result: `tests`, `log`, `globals` (ordered `["set",k,v]` / `["clear",k]` / `["clearAll"]` /
`["headerSet",k,v]` / `["headerClear",k]`), `variables` (a pre-request script's `request.variables`), `error`. The
shim also implements the JetBrains `crypto` API and `jwt` on Node's `crypto` module. The shim
redirects `console.*` and `process.stdout.write` into the log, so nothing else reaches stdout as a result.

Inline scripts are compiled with `vm.Script` using the `.http` path as `filename` and the handler's line/column as
offsets, so stack traces name the `.http` line. Phase 3 (debugging) builds on this: external files keep their
own path; inline blocks will need a generated file with a source map.

## Safety rules (adl, 2026-10-15 and 2026-10-16)

- A pre-request script that cannot run (off, untrusted, no folder, no Node, a file problem, an error, a timeout, a
  crash) keeps its request unsent: `status.http.preScriptNotRun` with an `http.preScript.why.*` reason.
  `client.exit()` is not an error. Scripts never see secrets (`request.environment.get` is the env file as written).
- Every path handed to Node is a real path: under the permission model Node may not read a link on the way
  (`/var` on macOS).

- Trust: the same `TrustStore` as build scripts and secrets; asked once per run (`TrustPrompts.httpScripts`,
  Cancel default). Refused or no folder → each handler is skipped with a warning; checks still run. A handler
  file must be in a trusted folder (real path), a regular file of at most 1 MB. Remote `.http` runs are blocked
  before any of this.
- Environment: minimal (`HttpScripts.environment`: PATH, LANG/LC_*, TZ, Windows system folders). No
  `NODE_OPTIONS`, no tokens.
- Permission model by version (`HttpScripts.guardFor`): `--permission` on 22.13+, `--experimental-permission` on
  20–22.12, none before (warning + Doctor). Network is allowed and documented.
- Timeout: `Settings.httpScriptTimeoutSeconds` (10 s); the process tree is killed on timeout or cancel.

## Testing

`ResponseHandlersIntegrationTest` and `PreRequestScriptsIntegrationTest` (127.0.0.1 server + real `node`, skipped
without it) check what the server receives and what a script may not do; `HttpResponseHandlersFxTest` checks the panel, the coordinator flow, the
saved history on disk and the trust prompt. Pure tests: `HttpChecksTest`, `HttpScriptsTest`,
`HttpResponseHandlerParseTest`, `HttpSessionVariablesTest`.
