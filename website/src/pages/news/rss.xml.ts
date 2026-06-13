import rss from "@astrojs/rss";
import { getCollection } from "astro:content";
import type { APIContext } from "astro";

export async function GET(context: APIContext) {
  const items = (await getCollection("news")).sort(
    (a, b) => b.data.date.valueOf() - a.data.date.valueOf(),
  );
  return rss({
    title: "Editora News",
    description: "Release announcements and updates from the Editora project.",
    site: context.site ?? "https://editora-project.dev",
    items: items.map((p) => ({
      title: p.data.title,
      description: p.data.description ?? "",
      pubDate: p.data.date,
      link: `/news/${p.id}/`,
    })),
  });
}
