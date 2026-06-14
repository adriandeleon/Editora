import { defineCollection, z } from "astro:content";
import { glob } from "astro/loaders";

// Documentation pages — Markdown, organized by category + order for the sidebar.
const docs = defineCollection({
  loader: glob({ pattern: "**/*.md", base: "./src/content/docs" }),
  schema: z.object({
    title: z.string(),
    description: z.string().optional(),
    category: z.string().default("Guide"),
    order: z.number().default(100),
  }),
});

// Short, release-style announcements.
const news = defineCollection({
  loader: glob({ pattern: "**/*.md", base: "./src/content/news" }),
  schema: z.object({
    title: z.string(),
    description: z.string().optional(),
    date: z.coerce.date(),
    version: z.string().optional(),
    beta: z.boolean().default(false),
  }),
});

// Longer-form articles.
const blog = defineCollection({
  loader: glob({ pattern: "**/*.md", base: "./src/content/blog" }),
  schema: z.object({
    title: z.string(),
    description: z.string().optional(),
    date: z.coerce.date(),
    author: z.string().default("Adrian De Leon"),
    tags: z.array(z.string()).default([]),
    beta: z.boolean().default(false),
  }),
});

// One page per feature — drives both the home grid and /features/<slug>.
const features = defineCollection({
  loader: glob({ pattern: "**/*.md", base: "./src/content/features" }),
  schema: z.object({
    title: z.string(),
    group: z.string(),
    order: z.number().default(100),
    beta: z.boolean().default(false),
    summary: z.string(), // HTML — the card body on the home page
  }),
});

export const collections = { docs, news, blog, features };
