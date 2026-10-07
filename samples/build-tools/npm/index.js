// The line this script prints; index.test.js checks it.
function greeting(who) {
  return `Hello from ${who}.`;
}

module.exports = { greeting };

if (require.main === module) {
  console.log(greeting("the npm sample project"));
}
