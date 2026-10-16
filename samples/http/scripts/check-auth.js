// A response handler in a file: "> ./scripts/check-auth.js" after a request runs it once the response
// arrives. It has the same client, response and request objects as an inline {% %} block. Under
// HTTP: Debug Request at Caret, a breakpoint here stops the script.

client.test("status is 2xx", () => {
  client.assert(response.status >= 200 && response.status < 300, "status " + response.status);
});

client.test("the token was accepted", () => {
  client.assert(response.body.authenticated === true, JSON.stringify(response.body));
  client.assert(response.body.token === client.global.get("token"), "token " + response.body.token);
});
