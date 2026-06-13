import rss from "@astrojs/rss";
import { getCollection } from "astro:content";
import type { APIContext } from "astro";

export async function GET(context: APIContext) {
  const posts = (await getCollection("blog")).sort(
    (a, b) => b.data.date.valueOf() - a.data.date.valueOf(),
  );
  return rss({
    title: "Editora Blog",
    description: "Articles and deep-dives about Editora.",
    site: context.site ?? "https://editora-project.dev",
    items: posts.map((p) => ({
      title: p.data.title,
      description: p.data.description ?? "",
      pubDate: p.data.date,
      link: `/blog/${p.id}/`,
    })),
  });
}
