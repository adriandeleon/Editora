/**
 * A small TypeScript sample for syntax highlighting and folding.
 * Covers interfaces, generics, a discriminated union, an enum, a class with
 * accessors, async/await, template literals, and a regular expression.
 */

interface Named {
  readonly name: string;
}

type Item =
  | (Named & { kind: "book"; pages: number })
  | (Named & { kind: "tool"; weightKg: number });

enum Level {
  Info = "info",
  Warn = "warn",
}

const MAX_ITEMS = 0x40; // hex literal
const WORD = /[A-Za-z_]\w*/g;

export class Shelf<T extends Named> {
  #items: T[] = [];

  get size(): number {
    return this.#items.length;
  }

  add(item: T): boolean {
    if (this.size >= MAX_ITEMS) {
      return false;
    }
    this.#items.push(item);
    return true;
  }

  find(name: string): T | undefined {
    return this.#items.find((item) => item.name === name);
  }
}

/* A block comment: the switch is exhaustive, so `never` is unreachable. */
export function describe(item: Item): string {
  switch (item.kind) {
    case "book":
      return `a book: "${item.name}" (${item.pages} pages)`;
    case "tool":
      return `a tool: ${item.name}, ${item.weightKg.toFixed(1)} kg`;
    default: {
      const unreachable: never = item;
      return unreachable;
    }
  }
}

async function load(names: string[]): Promise<Shelf<Item>> {
  const shelf = new Shelf<Item>();
  for (const name of names) {
    const words = name.match(WORD) ?? [];
    await Promise.resolve();
    shelf.add({ kind: "book", name, pages: words.length * 100 });
  }
  return shelf;
}

load(["Structure and Interpretation", "Dune"]).then((shelf) => {
  console.log(Level.Info, shelf.size, describe(shelf.find("Dune")!));
});
