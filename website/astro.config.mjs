// @ts-check
import { defineConfig } from "astro/config";

// Served at the custom apex domain editora-project.dev (www. redirects to it).
// No `base` — the site lives at the domain root, so asset/link paths are "/".
export default defineConfig({
  site: "https://editora-project.dev",
});
