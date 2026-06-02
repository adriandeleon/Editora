// @ts-check
import { defineConfig } from "astro/config";

// Project page served at https://adriandeleon.github.io/Editora/
// `site` + `base` make Astro emit correct absolute/relative URLs and asset paths.
export default defineConfig({
  site: "https://adriandeleon.github.io",
  base: "/Editora",
});
