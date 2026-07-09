// Sample JavaScript — highlighted by the bundled TypeScript grammar (source.ts tokenizes plain JS)
// and served by typescript-language-server.
const greet = (name) => `Hello, ${name}!`;

async function fetchThings(ids) {
  const results = [];
  for (const id of ids) {
    results.push(await Promise.resolve({ id, ok: true }));
  }
  return results;
}

class Counter {
  #count = 0; // private field
  increment() {
    return ++this.#count;
  }
}

const nums = [1, 2, 3].map((n) => n * 2).filter((n) => n > 2);
console.log(greet("Editora"), nums, new Counter().increment());
