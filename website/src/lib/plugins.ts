// Plugin registry data. The plugins page fetches the live registry index at
// build time and falls back to this snapshot if the network is unavailable.
// Snapshot mirrors https://github.com/adriandeleon/editora-plugins (index.json).

export const REGISTRY_REPO = "https://github.com/adriandeleon/editora-plugins";
export const REGISTRY_INDEX_URL =
  "https://raw.githubusercontent.com/adriandeleon/editora-plugins/main/index.json";

export type Plugin = {
  id: string;
  name: string;
  version: string;
  description: string;
};

/** Source folder for a plugin in the registry repo. */
export const sourceUrl = (id: string) =>
  `${REGISTRY_REPO}/tree/main/plugins/${id}`;

export const fallbackPlugins: Plugin[] = [
  { id: "example", name: "Example Plugin", version: "1.0.0", description: "Demonstrates every Editora extension point (command, keybinding, tool window, editor menu item, status-bar segment, snippet)." },
  { id: "lorem-ipsum", name: "Lorem Ipsum", version: "1.0.0", description: "Generate lorem-ipsum placeholder text: insert a paragraph at the caret, or replace the selection." },
  { id: "text-tools", name: "Text Tools", version: "1.1.0", description: "Transform the selection or whole document: case convert, sort/unique/reverse lines, trim trailing whitespace, squeeze blank lines." },
  { id: "insert-tools", name: "Insert Tools", version: "1.0.0", description: "Insert a UUID or the current date/time at the caret in common formats." },
  { id: "scratchpad", name: "Scratchpad", version: "1.0.0", description: "A persistent scratchpad tool window; auto-saves to the plugin's data dir." },
  { id: "format-runner", name: "Format Runner", version: "1.0.0", description: "Format the active file with an external formatter (prettier/black/gofmt/rustfmt/clang-format), chosen by extension." },
  { id: "encode-tools", name: "Encode Tools", version: "1.0.0", description: "Encode/decode the selection or document: Base64, URL, HTML entities, ROT13, hex." },
  { id: "hash-tools", name: "Hash Tools", version: "1.0.0", description: "Hash text to a hex digest (MD5/SHA-1/SHA-256): replace the selection, or insert the document's hash." },
  { id: "markdown-toc", name: "Markdown TOC", version: "1.0.0", description: "Insert a table of contents (linked bullet list) built from the document's Markdown headings." },
  { id: "regex-tester", name: "Regex Tester", version: "1.0.0", description: "A live regex tester tool window: pattern + flags + test string, with match spans and capture groups." },
  { id: "json-tools", name: "JSON / XML Tools", version: "1.0.0", description: "Tidy structured text: JSON pretty-print/minify and XML pretty-print." },
  { id: "slug-tools", name: "Slug & Sequence", version: "1.0.0", description: "Slugify text, number lines, or fill a column with 1..N." },
  { id: "box-banner", name: "Box Banner", version: "1.0.0", description: "Wrap the selection in an ASCII box banner." },
  { id: "color-picker", name: "Color Picker", version: "1.0.0", description: "Pick a color and insert it as HEX / rgb() / rgba() (tool window)." },
  { id: "word-count", name: "Word Count", version: "1.0.0", description: "Live word/line/character count + reading time (tool window)." },
  { id: "calculator", name: "Calculator", version: "1.0.0", description: "Evaluate arithmetic expressions and insert the result (tool window)." },
  { id: "open-on-github", name: "Open on GitHub", version: "1.0.0", description: "Open the active file at the caret line on its remote's web UI." },
  { id: "reveal", name: "Reveal & Terminal", version: "1.0.0", description: "Reveal the file in the OS file manager, or open a terminal at its folder." },
  { id: "run-task", name: "Task Runner", version: "1.0.0", description: "Run a shell task (npm/make/…) in the file's directory and stream output (tool window)." },
];

/** Fetch the live registry at build time; fall back to the snapshot offline. */
export async function loadPlugins(): Promise<Plugin[]> {
  try {
    const res = await fetch(REGISTRY_INDEX_URL);
    if (res.ok) {
      const data = await res.json();
      if (Array.isArray(data?.plugins) && data.plugins.length) {
        return data.plugins.map((p: any) => ({
          id: p.id,
          name: p.name ?? p.id,
          version: p.version ?? "",
          description: p.description ?? "",
        }));
      }
    }
  } catch {
    /* offline — use the snapshot */
  }
  return fallbackPlugins;
}
