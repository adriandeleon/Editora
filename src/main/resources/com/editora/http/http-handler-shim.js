// Editora HTTP Client: runs one JavaScript response handler or pre-request script of a .http request on the user's
// Node.js.
//
// Input: one JSON object on stdin (the mode, the response for a handler, the request, the environment, the session
// variables and headers, and the script). Output: one line on stdout, the run's nonce followed by a JSON object
// (tests, log lines, session changes, the request variables a pre-request script set, an error). Everything else the
// script prints is captured as log lines, so the result line is the only thing written to stdout. The API follows the
// JetBrains HTTP Client's response handler, pre-request and crypto APIs:
// https://www.jetbrains.com/help/idea/http-response-handling-api-reference.html
//
// Started with a minimal environment and, where Node supports it, the permission model (no file writes, no child
// processes, no workers); see com.editora.http.HttpScripts.
'use strict';

const vm = require('node:vm');
const nodeCrypto = require('node:crypto');

const MAX_LOG_LINES = 1000;
const MAX_LINE = 10000;
const MAX_TESTS = 1000;
const SHIM = __filename;

const realWrite = process.stdout.write.bind(process.stdout);
const realExit = process.exit.bind(process);

/** Thrown by client.exit() (and process.exit()) to stop the script; not an error. */
const EXIT = Symbol('client.exit');

class AssertionError extends Error {
  constructor(message) {
    super(message);
    this.name = 'AssertionError';
  }
}

const out = { tests: [], log: [], globals: [], variables: {}, error: null };
let logDropped = 0;

function cut(s) {
  return s.length > MAX_LINE ? s.slice(0, MAX_LINE) + '…' : s;
}

function show(v) {
  if (typeof v === 'string') return v;
  if (v instanceof Error) return v.stack || String(v);
  if (v === undefined) return 'undefined';
  if (typeof v === 'function') return '[function ' + (v.name || 'anonymous') + ']';
  try {
    const json = JSON.stringify(v);
    return json === undefined ? String(v) : json;
  } catch (e) {
    return String(v);
  }
}

function log(...args) {
  const text = args.map(show).join(' ');
  for (const line of text.split(/\r?\n/)) {
    if (out.log.length < MAX_LOG_LINES) {
      out.log.push(cut(line));
    } else {
      logDropped++;
    }
  }
}

/** A stack without the shim's own frames and Node's internals. */
function stackOf(e) {
  if (!(e instanceof Error)) return 'Uncaught ' + show(e);
  const lines = String(e.stack || e.message || e).split('\n');
  const kept = lines.filter((l, i) => i === 0 || !(l.includes(SHIM) || l.includes('node:internal') || l.includes('(node:')));
  return kept.join('\n');
}

function failure(e) {
  if (e instanceof AssertionError) return e.message;
  if (e instanceof Error) return (e.name && e.name !== 'Error' ? e.name + ': ' : '') + e.message;
  return 'Uncaught ' + show(e);
}

function asText(value) {
  if (typeof value === 'string') return value;
  if (value === null || value === undefined) return '';
  if (typeof value === 'object') {
    try {
      return JSON.stringify(value);
    } catch (e) {
      return String(value);
    }
  }
  return String(value);
}

function findHeader(pairs, name) {
  const n = String(name).toLowerCase();
  return pairs.filter((p) => String(p[0]).toLowerCase() === n).map((p) => p[1]);
}

// --- jsonPath: the subset Editora's own references understand ($, .field, ['field'], [0], *) -------------------

function tokenize(path) {
  const out = [];
  const p = String(path).trim();
  let i = 0;
  while (i < p.length) {
    const c = p[i];
    if (c === '$') {
      i++;
    } else if (c === '.') {
      i++;
    } else if (c === '[') {
      const end = p.indexOf(']', i);
      if (end < 0) break;
      let tok = p.slice(i + 1, end).trim();
      if (tok.length >= 2 && ((tok[0] === '"' && tok.endsWith('"')) || (tok[0] === "'" && tok.endsWith("'")))) {
        tok = tok.slice(1, -1);
      }
      out.push(tok);
      i = end + 1;
    } else {
      let j = i;
      while (j < p.length && p[j] !== '.' && p[j] !== '[') j++;
      out.push(p.slice(i, j));
      i = j;
    }
  }
  return out;
}

function jsonPath(obj, path) {
  let cur = obj;
  if (typeof cur === 'string') {
    try {
      cur = JSON.parse(cur);
    } catch (e) {
      return null;
    }
  }
  for (const tok of tokenize(path)) {
    if (tok === '*' || tok === '') continue;
    if (cur === null || cur === undefined) return null;
    if (/^\d+$/.test(tok)) {
      cur = Array.isArray(cur) ? cur[Number(tok)] : cur[tok];
    } else {
      cur = typeof cur === 'object' ? cur[tok] : undefined;
    }
  }
  return cur === undefined ? null : cur;
}

function xpath() {
  throw new Error('xpath() is not supported by Editora yet');
}

// --- the API objects ---------------------------------------------------------------------------------------------

function buildApi(input) {
  const globals = Object.assign(Object.create(null), input.globals || {});
  const pending = [];

  const global = {
    get(name) {
      const v = globals[String(name)];
      return v === undefined ? null : v;
    },
    set(name, value) {
      const n = String(name);
      const v = asText(value);
      globals[n] = v;
      out.globals.push(['set', n, v]);
    },
    clear(name) {
      const n = String(name);
      delete globals[n];
      out.globals.push(['clear', n]);
    },
    clearAll() {
      for (const k of Object.keys(globals)) delete globals[k];
      out.globals.push(['clearAll']);
    },
    isEmpty() {
      return Object.keys(globals).length === 0;
    },
    headers: {
      set(name, value) {
        out.globals.push(['headerSet', String(name), asText(value)]);
      },
      clear(name) {
        out.globals.push(['headerClear', String(name)]);
      },
    },
  };

  const client = {
    global,
    test(name, fn) {
      if (out.tests.length >= MAX_TESTS) return;
      const slot = { name: String(name), passed: true, message: '' };
      out.tests.push(slot);
      const fail = (e) => {
        slot.passed = false;
        slot.message = cut(failure(e));
      };
      try {
        const r = typeof fn === 'function' ? fn() : undefined;
        if (r && typeof r.then === 'function') {
          pending.push(Promise.resolve(r).then(() => {}, (e) => (e === EXIT ? undefined : fail(e))));
        }
      } catch (e) {
        if (e === EXIT) throw e;
        fail(e);
      }
    },
    assert(condition, message) {
      if (!condition) {
        throw new AssertionError(message === undefined ? 'Assertion failed' : show(message));
      }
    },
    log,
    exit() {
      throw EXIT;
    },
  };

  const pre = input.mode === 'pre';
  const res = input.response || {};
  const resHeaders = res.headers || [];
  const mime = (res.contentType && res.contentType.mimeType) || '';
  let body = res.body === undefined || res.body === null ? '' : res.body;
  if (/[/+]json$/i.test(mime.trim()) && body !== '') {
    try {
      body = JSON.parse(body);
    } catch (e) {
      // not JSON after all: the text
    }
  }
  const cookies = () => findHeader(resHeaders, 'set-cookie').map(parseCookie).filter((c) => c !== null);
  const response = pre ? undefined : {
    body,
    status: res.status,
    headers: {
      valueOf(name) {
        const v = findHeader(resHeaders, name);
        return v.length ? v[0] : null;
      },
      valuesOf(name) {
        return findHeader(resHeaders, name);
      },
    },
    contentType: {
      mimeType: mime,
      charset: (res.contentType && res.contentType.charset) || 'utf-8',
    },
    cookies,
    cookiesByName(name) {
      return cookies().filter((c) => c.name === String(name));
    },
  };

  const req = input.request || {};
  const header = (pair, rawPair) => ({
    name: pair[0],
    value() {
      return pair[1];
    },
    getRawValue() {
      return rawPair ? rawPair[1] : pair[1];
    },
    tryGetSubstitutedValue() {
      return pair[1];
    },
  });
  const reqHeaders = req.headers || [];
  const rawHeaders = req.headersRaw || [];
  const rawFor = (name) => rawHeaders.find((p) => String(p[0]).toLowerCase() === String(name).toLowerCase());
  const variables = Object.assign(Object.create(null), input.variables || {});
  const environment = Object.assign(Object.create(null), input.environment || {});
  const request = {
    method: req.method,
    url: {
      getRaw() {
        return req.urlRaw;
      },
      tryGetSubstituted() {
        return req.url;
      },
      toString() {
        return req.url;
      },
    },
    body: {
      getRaw() {
        return req.bodyRaw;
      },
      tryGetSubstituted() {
        return req.body;
      },
      toString() {
        return req.body;
      },
    },
    headers: {
      all() {
        return reqHeaders.map((p) => header(p, rawFor(p[0])));
      },
      findByName(name) {
        const p = reqHeaders.find((h) => String(h[0]).toLowerCase() === String(name).toLowerCase());
        return p ? header(p, rawFor(p[0])) : null;
      },
    },
    environment: {
      get(name) {
        const v = environment[String(name)];
        return v === undefined ? null : v;
      },
    },
    variables: {
      get(name) {
        const v = variables[String(name)];
        return v === undefined ? null : v;
      },
      set(name, value) {
        const n = String(name);
        const v = asText(value);
        variables[n] = v;
        if (pre) {
          out.variables[n] = v; // in force for this request
        } else {
          log('request.variables.set only affects this script in a response handler');
        }
      },
    },
  };

  return { client, response, request, pending };
}

function parseCookie(text) {
  const parts = String(text).split(';');
  const first = parts.shift();
  const eq = first.indexOf('=');
  if (eq <= 0) return null;
  const cookie = {
    name: first.slice(0, eq).trim(),
    value: first.slice(eq + 1).trim(),
    domain: null,
    path: null,
    expires: null,
    maxAge: null,
    secure: false,
    httpOnly: false,
    sameSite: null,
  };
  for (const part of parts) {
    const i = part.indexOf('=');
    const key = (i < 0 ? part : part.slice(0, i)).trim().toLowerCase();
    const value = i < 0 ? '' : part.slice(i + 1).trim();
    if (key === 'domain') cookie.domain = value;
    else if (key === 'path') cookie.path = value;
    else if (key === 'expires') cookie.expires = value;
    else if (key === 'max-age') cookie.maxAge = Number(value);
    else if (key === 'secure') cookie.secure = true;
    else if (key === 'httponly') cookie.httpOnly = true;
    else if (key === 'samesite') cookie.sameSite = value;
  }
  return cookie;
}

// --- crypto: the JetBrains HTTP Client crypto API on Node's crypto -----------------------------------------------

function encodingOf(e) {
  const n = String(e || 'utf8').toLowerCase().replace(/[-_]/g, '');
  return { utf8: 'utf8', ascii: 'ascii', latin1: 'latin1', iso88591: 'latin1', utf16le: 'utf16le', ucs2: 'utf16le' }[n] || 'utf8';
}

function base64Bytes(text, urlSafe) {
  return Buffer.from(String(text), urlSafe ? 'base64url' : 'base64');
}

class Digest {
  constructor(bytes) {
    this.bytes = bytes;
  }
  toHex() {
    return this.bytes.toString('hex');
  }
  toBase64(urlSafe) {
    return this.bytes.toString(urlSafe ? 'base64url' : 'base64');
  }
}

function hasher(algorithm, secret) {
  const chunks = [];
  const api = {
    updateWithText(text, encoding) {
      chunks.push(Buffer.from(String(text), encodingOf(encoding)));
      return api;
    },
    updateWithHex(hex) {
      chunks.push(Buffer.from(String(hex), 'hex'));
      return api;
    },
    updateWithBase64(text, urlSafe) {
      chunks.push(base64Bytes(text, urlSafe));
      return api;
    },
    digest() {
      const h = secret ? nodeCrypto.createHmac(algorithm, secret) : nodeCrypto.createHash(algorithm);
      for (const c of chunks) h.update(c);
      return new Digest(h.digest());
    },
  };
  return api;
}

function hmacOf(algorithm) {
  return {
    withTextSecret(secret, encoding) {
      return hasher(algorithm, Buffer.from(String(secret), encodingOf(encoding)));
    },
    withHexSecret(secret) {
      return hasher(algorithm, Buffer.from(String(secret), 'hex'));
    },
    withBase64Secret(secret, urlSafe) {
      return hasher(algorithm, base64Bytes(secret, urlSafe));
    },
  };
}

const HASHES = { md5: 'md5', sha1: 'sha1', sha256: 'sha256', sha384: 'sha384', sha512: 'sha512' };

const cryptoApi = { hmac: {} };
for (const [name, alg] of Object.entries(HASHES)) {
  cryptoApi[name] = () => hasher(alg);
  cryptoApi.hmac[name] = () => hmacOf(alg);
}
cryptoApi.sha3 = (bits) => hasher('sha3-' + String(bits || 256));
cryptoApi.hmac.sha3 = (bits) => hmacOf('sha3-' + String(bits || 256));
Object.defineProperty(cryptoApi, 'subtle', {
  get() {
    throw new Error('crypto.subtle is not supported by Editora yet');
  },
});

// --- jwt: sign, verify and decode with HMAC secrets or PEM keys ------------------------------------------------

const JWT_ALGORITHMS = {
  HS256: ['hmac', 'sha256'], HS384: ['hmac', 'sha384'], HS512: ['hmac', 'sha512'],
  RS256: ['rsa', 'sha256'], RS384: ['rsa', 'sha384'], RS512: ['rsa', 'sha512'],
  PS256: ['pss', 'sha256'], PS384: ['pss', 'sha384'], PS512: ['pss', 'sha512'],
  ES256: ['ec', 'sha256'], ES384: ['ec', 'sha384'], ES512: ['ec', 'sha512'],
};

function b64url(value) {
  return Buffer.from(typeof value === 'string' ? value : JSON.stringify(value)).toString('base64url');
}

function jwtAlgorithm(name) {
  const a = JWT_ALGORITHMS[String(name || 'HS256').toUpperCase()];
  if (!a) throw new Error('unsupported JWT algorithm: ' + name);
  return a;
}

function keyText(key) {
  if (typeof key === 'string' || Buffer.isBuffer(key)) return key;
  throw new Error('a JWT key is a secret or a PEM key (CryptoKey objects are not supported by Editora yet)');
}

function jwtSign(payload, key, options) {
  const o = options || {};
  const algorithm = String(o.algorithm || 'HS256').toUpperCase();
  const [kind, hash] = jwtAlgorithm(algorithm);
  const now = Math.floor(Date.now() / 1000);
  const claims = Object.assign({}, typeof payload === 'object' && payload !== null ? payload : { data: payload });
  if (claims.iat === undefined && o.noTimestamp !== true) claims.iat = now;
  if (o.expiresIn !== undefined) claims.exp = now + Number(o.expiresIn);
  if (o.notBefore !== undefined) claims.nbf = now + Number(o.notBefore);
  if (o.audience !== undefined) claims.aud = o.audience;
  if (o.issuer !== undefined) claims.iss = o.issuer;
  if (o.subject !== undefined) claims.sub = o.subject;
  if (o.jwtid !== undefined) claims.jti = o.jwtid;
  const header = Object.assign({ alg: algorithm, typ: 'JWT' }, o.header || {});
  const input = b64url(header) + '.' + b64url(claims);
  return input + '.' + jwtSignature(kind, hash, input, keyText(key)).toString('base64url');
}

function jwtSignature(kind, hash, input, key) {
  if (kind === 'hmac') return nodeCrypto.createHmac(hash, key).update(input).digest();
  if (kind === 'rsa') return nodeCrypto.sign(hash, Buffer.from(input), key);
  if (kind === 'pss') {
    return nodeCrypto.sign(hash, Buffer.from(input), {
      key, padding: nodeCrypto.constants.RSA_PKCS1_PSS_PADDING, saltLength: nodeCrypto.constants.RSA_PSS_SALTLEN_DIGEST,
    });
  }
  return nodeCrypto.sign(hash, Buffer.from(input), { key, dsaEncoding: 'ieee-p1363' });
}

function jwtParts(token) {
  const parts = String(token).split('.');
  if (parts.length !== 3) throw new Error('not a JWT: it has ' + parts.length + ' parts');
  const header = JSON.parse(Buffer.from(parts[0], 'base64url').toString('utf8'));
  const payload = JSON.parse(Buffer.from(parts[1], 'base64url').toString('utf8'));
  return { header, payload, signature: parts[2], input: parts[0] + '.' + parts[1] };
}

function jwtVerify(token, key, options) {
  const o = options || {};
  const p = jwtParts(token);
  const algorithm = String(p.header.alg || '').toUpperCase();
  if (o.algorithm && String(o.algorithm).toUpperCase() !== algorithm) {
    throw new Error('JWT algorithm ' + algorithm + ' is not ' + o.algorithm);
  }
  const [kind, hash] = jwtAlgorithm(algorithm);
  const signature = Buffer.from(p.signature, 'base64url');
  const k = keyText(key);
  let ok;
  if (kind === 'hmac') {
    const expected = nodeCrypto.createHmac(hash, k).update(p.input).digest();
    ok = expected.length === signature.length && nodeCrypto.timingSafeEqual(expected, signature);
  } else if (kind === 'rsa') {
    ok = nodeCrypto.verify(hash, Buffer.from(p.input), k, signature);
  } else if (kind === 'pss') {
    ok = nodeCrypto.verify(hash, Buffer.from(p.input), {
      key: k, padding: nodeCrypto.constants.RSA_PKCS1_PSS_PADDING, saltLength: nodeCrypto.constants.RSA_PSS_SALTLEN_DIGEST,
    }, signature);
  } else {
    ok = nodeCrypto.verify(hash, Buffer.from(p.input), { key: k, dsaEncoding: 'ieee-p1363' }, signature);
  }
  if (!ok) throw new Error('invalid JWT signature');
  const now = Math.floor(Date.now() / 1000);
  if (typeof p.payload.exp === 'number' && now >= p.payload.exp) throw new Error('JWT expired');
  if (typeof p.payload.nbf === 'number' && now < p.payload.nbf) throw new Error('JWT not active yet');
  if (o.audience !== undefined) {
    const aud = [].concat(p.payload.aud === undefined ? [] : p.payload.aud);
    const wanted = [].concat(o.audience);
    if (!wanted.some((a) => aud.includes(a))) throw new Error('JWT audience is not ' + wanted.join(', '));
  }
  if (o.issuer !== undefined && p.payload.iss !== o.issuer) throw new Error('JWT issuer is not ' + o.issuer);
  return p.payload;
}

const jwtApi = {
  sign: jwtSign,
  verify: jwtVerify,
  decode(token, options) {
    const p = jwtParts(token);
    return options && options.complete ? { header: p.header, payload: p.payload, signature: p.signature } : p.payload;
  },
};

// --- running -----------------------------------------------------------------------------------------------------

function finish(nonce) {
  if (logDropped > 0) {
    out.log.push('… ' + logDropped + ' more log lines were dropped');
  }
  realWrite(nonce + JSON.stringify(out) + '\n', () => realExit(0));
}

async function run(input) {
  const api = buildApi(input);
  const consoleLike = { log, info: log, warn: log, error: log, debug: log, trace: log };
  // Anything printed goes to the log: stdout carries only the result line.
  console.log = console.info = console.warn = console.error = console.debug = log;
  process.stdout.write = (chunk) => {
    log(String(chunk).replace(/\r?\n$/, ''));
    return true;
  };
  process.exit = () => {
    throw EXIT;
  };
  process.on('unhandledRejection', (e) => log('Unhandled promise rejection: ' + stackOf(e)));

  const s = input.script || {};
  const head = '(function (client, response, request, jsonPath, xpath, console, crypto, jwt) {';
  try {
    const script = new vm.Script(head + String(s.source || '') + '\n})', {
      filename: s.filename || 'handler.js',
      lineOffset: s.line || 0,
      columnOffset: (s.column || 0) - head.length,
    });
    const fn = script.runInThisContext();
    const r = fn.call(
      undefined, api.client, api.response, api.request, jsonPath, xpath, consoleLike, cryptoApi, jwtApi);
    if (r && typeof r.then === 'function') {
      await r;
    }
  } catch (e) {
    if (e !== EXIT) {
      out.error = cut(stackOf(e));
    }
  }
  await Promise.all(api.pending);
}

let raw = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', (chunk) => {
  raw += chunk;
});
process.stdin.on('end', () => {
  let input;
  try {
    input = JSON.parse(raw);
  } catch (e) {
    realWrite('editora-shim: unreadable input\n');
    realExit(2);
    return;
  }
  run(input).then(
    () => finish(input.nonce || ''),
    (e) => {
      out.error = out.error || cut(stackOf(e));
      finish(input.nonce || '');
    },
  );
});
