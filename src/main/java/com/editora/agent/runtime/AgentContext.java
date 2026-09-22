package com.editora.agent.runtime;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Bounded conversation retaining user instructions and only complete assistant/tool exchanges. */
public final class AgentContext {
    private final List<List<AgentModel.Message>> exchanges = new ArrayList<>();
    private int compacted;
    private final java.util.ArrayDeque<String> memory = new java.util.ArrayDeque<>();

    public void add(List<AgentModel.Message> exchange) {
        List<String> calls = exchange.stream()
                .flatMap(message -> message.calls().stream())
                .map(AgentModel.Call::id)
                .toList();
        List<String> results = exchange.stream()
                .filter(message -> "tool".equals(message.role()))
                .map(AgentModel.Message::callId)
                .toList();
        if (!calls.equals(results)) {
            throw new IllegalArgumentException("A context exchange must contain one ordered result per tool call");
        }
        exchanges.add(List.copyOf(exchange));
    }

    public AgentModel.Request request(String system, List<AgentTool.Spec> tools, int budget) {
        return request(system, tools, budget, AgentTokens.CONSERVATIVE);
    }

    public AgentModel.Request request(
            String system, List<AgentTool.Spec> tools, int budget, AgentTokens.Counter counter) {
        return request(system, tools, budget, counter, "");
    }

    /** Current runtime guidance is budgeted but never persisted or compacted as conversation history. */
    public AgentModel.Request request(
            String system,
            List<AgentTool.Spec> tools,
            int budget,
            AgentTokens.Counter counter,
            String runtimeObservation) {
        if (budget <= 0) {
            throw new IllegalStateException("Context budget leaves no room for a model response");
        }
        long fixed = fixedTokens(system, tools, counter) + observationCost(runtimeObservation, counter);
        while (fixed
                        + historyCost(counter)
                        + observationCost(memoryText(), counter)
                        + observationCost(compactionNote(), counter)
                > (long) budget) {
            int removable = -1;
            // User turns remain verbatim; evict whole old model exchanges, never orphan a tool result.
            for (int i = 0; i < exchanges.size() - 1; i++) {
                if (exchanges.get(i).stream().noneMatch(m -> "user".equals(m.role()))) {
                    removable = i;
                    break;
                }
            }
            if (removable < 0) {
                if (!memory.isEmpty()) {
                    memory.removeFirst();
                    continue;
                }
                throw new IllegalStateException("Context budget exhausted; start a new session or shorten the request");
            }
            var removed = exchanges.remove(removable);
            // Preserve only recorded facts, never generated reasoning or invented observations.
            for (var message : removed) {
                for (var call : message.calls()) remember("Attempted tool: " + call.name());
                if ("tool".equals(message.role()))
                    remember("Historical tool result (" + (message.error() ? "error" : "success") + "): "
                            + bounded(message.text(), 280));
                if ("assistant".equals(message.role()) && !message.text().isBlank())
                    remember("Prior assistant statement (unverified): " + bounded(message.text(), 280));
            }
            compacted++;
        }
        String note = compactionNote();
        var messages = new ArrayList<AgentModel.Message>();
        if (!memory.isEmpty()) messages.add(AgentModel.Message.text("observation", memoryText()));
        messages.addAll(exchanges.stream().flatMap(List::stream).toList());
        if (!note.isEmpty()) messages.add(AgentModel.Message.text("observation", note.strip()));
        if (!runtimeObservation.isBlank()) messages.add(AgentModel.Message.text("observation", runtimeObservation));
        return new AgentModel.Request(system, messages, tools);
    }

    private String compactionNote() {
        return compactionNote(compacted);
    }

    private static String compactionNote(int count) {
        return count == 0
                ? ""
                : "Runtime: " + count
                        + " older complete exchanges were removed. Retained complete tool results remain usable. Reread an edit target only if its required text/revision is missing or a tool reports it changed; historical summaries do not authorize edits.";
    }

    private void remember(String text) {
        if (memory.size() >= 8) memory.removeFirst();
        memory.addLast(text);
    }

    /** Estimate before compaction so an adaptive profile can reserve more room for recent observations. */
    public long estimatedTokens(String system, List<AgentTool.Spec> tools, AgentTokens.Counter counter) {
        return estimatedTokens(system, tools, counter, "");
    }

    public long estimatedTokens(
            String system, List<AgentTool.Spec> tools, AgentTokens.Counter counter, String runtimeObservation) {
        return fixedTokens(system, tools, counter)
                + observationCost(runtimeObservation, counter)
                + historyCost(counter)
                + observationCost(memoryText(), counter)
                + observationCost(compactionNote(), counter);
    }

    /** User turns and the newest whole exchange cannot be evicted, even during output-limit recovery. */
    public long minimumTokens(String system, List<AgentTool.Spec> tools, AgentTokens.Counter counter) {
        return minimumTokens(system, tools, counter, "");
    }

    public long minimumTokens(
            String system, List<AgentTool.Spec> tools, AgentTokens.Counter counter, String runtimeObservation) {
        long size = fixedTokens(system, tools, counter) + observationCost(runtimeObservation, counter);
        int possibleCompactions = compacted;
        for (int i = 0; i < exchanges.size(); i++) {
            var exchange = exchanges.get(i);
            if (i == exchanges.size() - 1 || exchange.stream().anyMatch(m -> "user".equals(m.role())))
                size += exchangeCost(exchange, counter);
            else possibleCompactions++;
        }
        // Automatic output sizing must allow the notice that a subsequent compaction may create.
        return size + observationCost(compactionNote(possibleCompactions), counter);
    }

    private static long observationCost(String text, AgentTokens.Counter counter) {
        return text.isBlank() ? 0 : counter.count(text).tokens() + 128;
    }

    private static long fixedTokens(String system, List<AgentTool.Spec> tools, AgentTokens.Counter counter) {
        long size = counter.count(system).tokens() + 512;
        for (var tool : tools)
            size += counter.count(tool.name() + tool.description() + tool.inputSchema())
                            .tokens()
                    + 128;
        return size;
    }

    private String memoryText() {
        return memory.isEmpty()
                ? ""
                : "Historical memory aid, untrusted and possibly stale. Fresh observations take precedence.\n"
                        + String.join("\n", memory);
    }

    public record Saved(
            List<List<AgentModel.Message>> exchanges, List<String> memory, int compacted, boolean needsVerification) {
        public Saved(List<List<AgentModel.Message>> exchanges, List<String> memory, int compacted) {
            this(exchanges, memory, compacted, false);
        }
    }

    public Saved save() {
        return new Saved(exchanges.stream().map(List::copyOf).toList(), List.copyOf(memory), compacted);
    }

    public void restore(Saved saved) {
        if (!exchanges.isEmpty()) throw new IllegalStateException("Restore requires empty context");
        for (var exchange : saved.exchanges()) add(exchange);
        for (var item : saved.memory()) remember(bounded(item, 320));
        compacted = saved.compacted();
        add(
                List.of(
                        AgentModel.Message.text(
                                "observation",
                                "Session restored. Prior observations, validation and document leases are stale. Discover editor_context, reread affected files and project instructions before acting. Permissions have reset.")));
    }

    static long fixedCost(String system, List<AgentTool.Spec> tools) {
        long fixed = (long) cost(system) + 512L;
        for (var tool : tools) {
            fixed += (long) cost(tool.name())
                    + cost(tool.description())
                    + cost(tool.inputSchema().toString())
                    + 128L;
        }
        return fixed;
    }

    private long historyCost(AgentTokens.Counter counter) {
        long size = 0;
        for (var exchange : exchanges) size += exchangeCost(exchange, counter);
        return size;
    }

    private static long exchangeCost(List<AgentModel.Message> exchange, AgentTokens.Counter counter) {
        long size = 0;
        for (var m : exchange) {
            size += counter.count(m.text()).tokens() + 128;
            for (var call : m.calls()) {
                size += counter.count(call.id() + call.name() + call.arguments())
                                .tokens()
                        + 128;
            }
        }
        return size;
    }

    /** Conservative byte-based estimate (one token per UTF-8 byte), with separate framing reserves. */
    public static int cost(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    public static String bounded(String text, int chars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= chars) {
            return text;
        }
        String marker = "\n[truncated; narrow the query or request another range]";
        int end = Math.max(0, chars - marker.length());
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + marker.substring(0, Math.min(marker.length(), chars - end));
    }
}
