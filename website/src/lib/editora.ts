import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));

/**
 * The Editora release version, read from the project's pom.xml at build time so
 * the site never drifts from the app. Falls back gracefully if the file moves.
 */
export const version: string = (() => {
  try {
    // website/src/lib -> repo root
    const pom = readFileSync(resolve(here, "../../../pom.xml"), "utf8");
    // The project's own <version> is the first one declared in the POM.
    const match = pom.match(/<version>([^<]+)<\/version>/);
    return match?.[1].trim() ?? "1.0.0";
  } catch {
    return "1.0.0";
  }
})();

export const repo = "https://github.com/adriandeleon/Editora";
export const latestRelease = `${repo}/releases/latest`;
