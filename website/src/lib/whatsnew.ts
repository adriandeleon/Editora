import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));

export type NewsItem = { title: string; detail: string };

// Minimal inline-markdown → safe HTML (code spans kept; bold/links flattened).
function inline(s: string): string {
  return s
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/`([^`]+)`/g, "<code>$1</code>")
    .replace(/\*\*([^*]+)\*\*/g, "$1")
    .replace(/\[([^\]]+)\]\([^)]+\)/g, "$1");
}

function truncate(s: string, n: number): string {
  s = s.trim();
  return s.length > n ? s.slice(0, n).replace(/\s+\S*$/, "") + "…" : s;
}

/**
 * The most recent entries from CHANGELOG.md's [Unreleased] section, read at
 * build time so the "What's New" block never drifts from the changelog.
 */
export const whatsNew: NewsItem[] = (() => {
  try {
    const md = readFileSync(resolve(here, "../../../CHANGELOG.md"), "utf8");
    const start = md.indexOf("## [Unreleased]");
    const body = start >= 0 ? md.slice(start) : md;
    const items: NewsItem[] = [];
    let cur: string | null = null;

    const flush = () => {
      if (!cur) return;
      const m = cur.match(/^\*\*(.+?)\*\*\s*(?:[—–-]+\s*)?(.*)$/);
      if (m) {
        items.push({ title: inline(m[1]), detail: truncate(inline(m[2]), 150) });
      } else {
        items.push({ title: truncate(inline(cur), 90), detail: "" });
      }
      cur = null;
    };

    for (const raw of body.split("\n")) {
      if (/^- /.test(raw)) {
        flush();
        cur = raw.replace(/^- /, "").trim();
      } else if (cur && /^\s+\S/.test(raw) && !/^\s*[-*]/.test(raw)) {
        cur += " " + raw.trim(); // wrapped continuation line
      } else if (/^#{2,3}\s/.test(raw) || raw.startsWith("[Unreleased]:")) {
        flush();
      }
      if (items.length >= 8) break;
    }
    flush();
    return items.slice(0, 8);
  } catch {
    return [];
  }
})();
