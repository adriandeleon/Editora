package com.editora.http;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Runs the JavaScript response handlers of a request ({@link HttpFile.Parsed#handlers()}) once its response has
 * arrived, on the run's worker, before the next request of a Run All starts — so a token a handler stores with
 * {@code client.global.set} is what the next request sends. {@link HttpClientService} calls it; the editor passes a
 * {@link NodeResponseScripts} built for the run (its trust answer, its settings), and {@link #OFF} runs nothing.
 */
public interface ResponseScripts {

    /**
     * Runs no script: a request with a handler says it was not run; one with a pre-request script is sent without it,
     * with a warning (the editor never passes this — there an unrun pre-request script keeps the request unsent).
     */
    ResponseScripts OFF = new ResponseScripts() {
        @Override
        public Result run(HttpFile.Parsed request, HttpExchange exchange, Input input) {
            return request.handlers().isEmpty()
                    ? Result.NONE
                    : new Result(HttpTests.NONE, List.of(), List.of(tr("http.warn.handlerIgnored")));
        }

        @Override
        public PreResult runPre(HttpFile.Parsed request, PreInput input) {
            return request.preScripts().isEmpty()
                    ? PreResult.NONE
                    : new PreResult(
                            null,
                            Map.of(),
                            HttpTests.NONE,
                            List.of(),
                            List.of(tr("http.warn.preRequestScriptIgnored")));
        }
    };

    /**
     * Called once per run, on the worker, before its first request is sent, with every request of the run; may block
     * on a question to the user (whether the folder may run scripts). Asks nothing by default.
     */
    default void beforeRun(List<HttpFile.Parsed> requests, BooleanSupplier cancelled) {}

    /** Runs {@code request}'s handlers against {@code exchange}, a response that arrived. Never throws. */
    Result run(HttpFile.Parsed request, HttpExchange exchange, Input input);

    /**
     * Runs {@code request}'s pre-request scripts, before it is resolved and sent. Never throws. A result with a
     * {@code notSent} reason keeps the request from being sent.
     */
    PreResult runPre(HttpFile.Parsed request, PreInput input);

    /**
     * What a pre-request script sees: the environment's variables (as written), the session variables and global
     * headers when it starts, the request as far as it resolves before the script ({@code url}, {@code headers},
     * {@code body}; every secret source kept as written), and whether the run was cancelled.
     */
    record PreInput(
            Map<String, String> environment,
            Map<String, String> globals,
            Map<String, String> globalHeaders,
            String url,
            List<String[]> headers,
            String body,
            BooleanSupplier cancelled) {
        public PreInput {
            environment = environment == null ? Map.of() : environment;
            globals = globals == null ? Map.of() : globals;
            globalHeaders = globalHeaders == null ? Map.of() : globalHeaders;
            headers = headers == null ? List.of() : headers;
            cancelled = cancelled == null ? () -> false : cancelled;
        }
    }

    /**
     * What the pre-request scripts of one request did: why the request must not be sent ({@code notSent}, null when it
     * may — the script ran, or there was none), the {@code request.variables} they set (in force for this request
     * only), their tests and log, their session changes, and warnings.
     */
    record PreResult(
            @Nullable String notSent,
            Map<String, String> variables,
            HttpTests tests,
            List<GlobalChange> changes,
            List<String> warnings) {
        public static final PreResult NONE = new PreResult(null, Map.of(), HttpTests.NONE, List.of(), List.of());

        public PreResult {
            variables = variables == null ? Map.of() : Map.copyOf(variables);
            tests = tests == null ? HttpTests.NONE : tests;
            changes = changes == null ? List.of() : List.copyOf(changes);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    /**
     * What a handler sees beyond the exchange: the selected environment's variables (as written in the environment
     * files), the session variables when it starts, and whether the run was cancelled meanwhile.
     */
    record Input(Map<String, String> environment, Map<String, String> globals, BooleanSupplier cancelled) {
        public Input {
            environment = environment == null ? Map.of() : environment;
            globals = globals == null ? Map.of() : globals;
            cancelled = cancelled == null ? () -> false : cancelled;
        }
    }

    /**
     * A change to the session, in the order the scripts made it: {@code set} {@code name} to {@code value},
     * {@code clear} {@code name}, {@code clearAll}, or a global header's {@code headerSet} / {@code headerClear}.
     */
    record GlobalChange(
            String kind, @Nullable String name, @Nullable String value) {
        public static final String SET = "set";
        public static final String CLEAR = "clear";
        public static final String CLEAR_ALL = "clearAll";
        public static final String HEADER_SET = "headerSet";
        public static final String HEADER_CLEAR = "headerClear";

        /** Applies this change to {@code session}. */
        public void applyTo(CapturedResponses session) {
            switch (kind) {
                case SET -> session.setGlobal(name, value == null ? "" : value);
                case CLEAR -> session.setGlobal(name, null);
                case CLEAR_ALL -> session.clearGlobals();
                case HEADER_SET -> session.setGlobalHeader(name, value == null ? "" : value);
                case HEADER_CLEAR -> session.setGlobalHeader(name, null);
                default -> {}
            }
        }

        /** Applies this change to a plain map of session variables (header changes are not variables). */
        public void applyTo(Map<String, String> globals) {
            switch (kind) {
                case SET -> globals.put(name, value == null ? "" : value);
                case CLEAR -> globals.remove(name);
                case CLEAR_ALL -> globals.clear();
                default -> {}
            }
        }
    }

    /** What the handlers of one request did: their tests and log, their session-variable changes, and warnings. */
    record Result(HttpTests tests, List<GlobalChange> changes, List<String> warnings) {
        public static final Result NONE = new Result(HttpTests.NONE, List.of(), List.of());

        public Result {
            tests = tests == null ? HttpTests.NONE : tests;
            changes = changes == null ? List.of() : List.copyOf(changes);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
}
