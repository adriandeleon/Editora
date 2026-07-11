package com.editora.dockerfile;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for Dockerfile stage parsing + the per-stage digest. */
class DockerfileTest {

    @Test
    void parsesMultiStageAndDigests() {
        String text = """
                # syntax=docker/dockerfile:1
                ARG NODE_VERSION=20

                FROM node:${NODE_VERSION} AS builder
                WORKDIR /app
                COPY package*.json ./
                RUN npm ci
                COPY . .
                RUN npm run build

                FROM nginx:1.25
                COPY --from=builder /app/dist /usr/share/nginx/html
                EXPOSE 80
                USER nginx
                ENV TZ=UTC
                HEALTHCHECK CMD curl -f http://localhost/ || exit 1
                CMD ["nginx", "-g", "daemon off;"]
                """;
        Dockerfile d = Dockerfile.parse(text);
        assertEquals(1, d.globalArgs().size());
        assertEquals(2, d.stages().size());

        Dockerfile.Stage builder = d.stages().get(0);
        assertEquals("node:${NODE_VERSION}", builder.baseImage());
        assertEquals("builder", builder.name());
        assertEquals("Stage 1 — builder (from node:${NODE_VERSION})", DockerfileDescribe.title(builder, false));
        assertTrue(DockerfileDescribe.summaryLines(builder).contains("Working directory: /app"));
        assertTrue(DockerfileDescribe.summaryLines(builder).stream()
                .anyMatch(s -> s.endsWith("build steps (RUN/COPY/ADD)")));

        Dockerfile.Stage fin = d.stages().get(1);
        assertEquals("Final stage (from nginx:1.25)", DockerfileDescribe.title(fin, true));
        List<String> facts = DockerfileDescribe.summaryLines(fin);
        assertTrue(facts.contains("Exposes port 80"), facts.toString());
        assertTrue(facts.contains("Runs as user: nginx"), facts.toString());
        assertTrue(facts.contains("Sets 1 environment variable"), facts.toString());
        assertTrue(facts.stream().anyMatch(s -> s.startsWith("Runs: [\"nginx\"")), facts.toString());
        assertTrue(facts.contains("Health check: configured"), facts.toString());
    }

    @Test
    void continuationLinesAreJoined() {
        Dockerfile d = Dockerfile.parse("FROM alpine\nRUN apt-get update \\\n && apt-get install -y curl\n");
        assertEquals(1, d.stages().size());
        assertEquals(1, d.stages().get(0).instructions().size()); // the two physical lines are one RUN
    }
}
