import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));

export type RoadmapItem = { done: boolean; text: string };
export type RoadmapSection = { title: string; items: RoadmapItem[] };

function inline(s: string): string {
  return s
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/`([^`]+)`/g, "<code>$1</code>")
    .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
    .replace(/\[([^\]]+)\]\([^)]+\)/g, "$1");
}

/**
 * Roadmap sections parsed from TODO.md at build time. "Recently shipped" is
 * skipped (the homepage "What's New" covers it); every other section is shown
 * with each item's done/planned state.
 */
export const roadmap: RoadmapSection[] = (() => {
  try {
    const md = readFileSync(resolve(here, "../../../TODO.md"), "utf8");
    const sections: RoadmapSection[] = [];
    let cur: RoadmapSection | null = null;
    let item: RoadmapItem | null = null;

    const pushItem = () => {
      if (cur && item) cur.items.push(item);
      item = null;
    };

    for (const raw of md.split("\n")) {
      const head = raw.match(/^##\s+(.*)$/);
      const li = raw.match(/^- \[([ xX])\]\s+(.*)$/);
      if (head) {
        pushItem();
        if (cur) sections.push(cur);
        cur =
          head[1].trim().toLowerCase() === "recently shipped"
            ? null
            : { title: head[1].trim(), items: [] };
      } else if (li && cur) {
        pushItem();
        item = { done: li[1].toLowerCase() === "x", text: inline(li[2].trim()) };
      } else if (item && /^\s+\S/.test(raw)) {
        item.text += " " + inline(raw.trim()); // wrapped continuation
      }
    }
    pushItem();
    if (cur) sections.push(cur);
    return sections.filter((s) => s.items.length > 0);
  } catch {
    return [];
  }
})();
