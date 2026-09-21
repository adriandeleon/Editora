package com.editora.ui;

import com.fasterxml.jackson.databind.*;

import static com.editora.i18n.Messages.tr;

/** Small presentation boundary: structured evidence remains unchanged for the runtime and model. */
final class AgentToolPresentation {
    private AgentToolPresentation() {}

    record View(String title, String details) {}

    static View result(String tool, String result) {
        if (tool.equals("task_contract") || tool.equals("completion_claims")) {
            try {
                var data = new ObjectMapper().readTree(result);
                var details = new StringBuilder();
                for (var requirement : data.path("requirements"))
                    details.append(requirement.path("id").asText())
                            .append(" · ")
                            .append(tr("agent.acceptance.check."
                                    + requirement.path("check").asText()))
                            .append(" · ")
                            .append(requirement.path("state").asText())
                            .append('\n')
                            .append(requirement.path("reason").asText())
                            .append("\n\n");
                for (var claim : data.path("claims"))
                    details.append(claim.path("kind").asText())
                            .append(" · ")
                            .append(claim.path("support").asText())
                            .append('\n');
                if (data.has("nextOffset")) details.append(tr("agent.acceptance.more"));
                return new View(tr("agent.acceptance.title"), details.toString());
            } catch (Exception invalid) {
                return new View(tool, result);
            }
        }
        if (!"run_validation".equals(tool)) return new View(tool, result);
        try {
            JsonNode data = new ObjectMapper().readTree(result);
            if (data == null || !data.has("passed") || !data.path("tests").isObject()) return new View(tool, result);
            var tests = data.path("tests");
            String title = tests.path("tests").asInt() > 0
                    ? tr(
                            "agent.validation.counts",
                            tests.path("tests").asInt(),
                            tests.path("failed").asInt(),
                            tests.path("skipped").asInt())
                    : tr("agent.validation.noTests", data.path("operation").asText());
            var details = new StringBuilder(title).append('\n');
            details.append(tr(
                            "agent.validation.scope",
                            data.path("operation").asText(),
                            data.path("module").asText("."),
                            data.path("isolation").asText()))
                    .append('\n');
            if (tests.path("unreadableReports").asInt() > 0
                    || tests.path("scanTruncated").asBoolean())
                details.append(tr(
                                "agent.validation.incomplete",
                                tests.path("unreadableReports").asInt(),
                                tests.path("scanTruncated").asBoolean()))
                        .append('\n');
            for (var failure : tests.path("failures"))
                details.append('\n')
                        .append(failure.path("class").asText())
                        .append(" · ")
                        .append(failure.path("test").asText())
                        .append('\n')
                        .append(failure.path("message").asText())
                        .append('\n');
            if (data.has("nextAction"))
                details.append('\n').append(data.path("nextAction").asText()).append('\n');
            details.append('\n').append(data.path("output").asText());
            return new View(title, details.toString());
        } catch (Exception invalid) {
            return new View(tool, result);
        }
    }

    static String permission(String tool, String arguments) {
        try {
            JsonNode data = new ObjectMapper().readTree(arguments);
            if (tool.equals("run_validation"))
                return tr(
                                "agent.validation.scope",
                                data.path("type").asText(),
                                data.path("module").asText("."),
                                data.path("isolation").asText())
                        + (data.has("test") ? "\n" + data.path("test").asText() : "");
            if (tool.equals("apply_edits") && data.path("edits").isArray()) {
                var paths = new java.util.LinkedHashSet<String>();
                data.path("edits").forEach(e -> paths.add(e.path("path").asText()));
                return tr("agent.permission.files", paths.size()) + "\n"
                        + String.join("\n", paths.stream().limit(32).toList());
            }
        } catch (Exception ignored) {
        }
        return tool;
    }
}
