// A pre-request script in a file: "< ./scripts/request-id.js" above a request line runs it before the
// request is resolved and sent.

const id = crypto.sha256().updateWithText(String(Date.now())).digest().toHex().substring(0, 16);

// For this request only, as {{requestId}}.
request.variables.set("requestId", id);

// A header for this request and every one after it, until cleared or the environment changes.
client.global.headers.set("X-Sample-Id", id);

client.log("request id", id);
