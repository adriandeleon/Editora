import { getCollection, type CollectionEntry } from "astro:content";

export const GROUP_ORDER = [
  "Keyboard & commands",
  "Editing",
  "Code intelligence",
  "Run & debug",
  "Git & diff",
  "Docs & diagrams",
  "Workspace & files",
  "Customization & extensibility",
];

export type FeatureGroup = {
  title: string;
  items: CollectionEntry<"features">[];
};

/** All features grouped (in GROUP_ORDER) and ordered within each group. */
export async function groupedFeatures(): Promise<FeatureGroup[]> {
  const all = await getCollection("features");
  const groupNames = [
    ...new Set([...GROUP_ORDER, ...all.map((f) => f.data.group)]),
  ];
  return groupNames
    .map((title) => ({
      title,
      items: all
        .filter((f) => f.data.group === title)
        .sort(
          (a, b) =>
            a.data.order - b.data.order ||
            a.data.title.localeCompare(b.data.title),
        ),
    }))
    .filter((g) => g.items.length > 0);
}
