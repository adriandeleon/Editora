package com.editora.dap;

import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * What Editora knows about debugging one language: which debug adapter serves it and how it is reached, the launch
 * {@code type} and {@code initialize} adapter id, the exception stops it offers, and the traits the debugger's code
 * otherwise asked by comparing language names — whether it is debugged on a remote file's own host, whether its
 * adapter starts child sessions, what to say when it is unavailable. One profile per <em>adapter language</em>
 * ({@code javascript} serves TypeScript too); {@link DebugLanguages} holds them all.
 *
 * <p>Adding a language's debugger is adding a profile: a {@link DapServerRegistry.Kind#EXTERNAL} one names the
 * adapter's command and how it talks ({@link ExternalAdapter}) and is started by the generic path, while Java,
 * Python and JavaScript keep the flows their adapters need.
 *
 * @param id the adapter language ({@code java}, {@code python}, {@code javascript}, …): settings, exception
 *     choices and messages are kept under it
 * @param displayName the language's name as the UI shows it
 * @param editorLanguages the editor language ids it serves
 * @param kind how the adapter is reached
 * @param launchType the DAP {@code launch} request's {@code type}
 * @param adapterId the {@code initialize} request's {@code adapterID}
 * @param defaultInterpreter what runs a standalone adapter when no command is configured ({@code python3},
 *     {@code node}); empty when none does
 * @param adapterArgs the fixed arguments after that interpreter ({@code -m debugpy.adapter})
 * @param fallbackKind the transport for a file the {@code kind} cannot debug (a Scala script outside a build), or
 *     null
 * @param availability what decides that the adapter is there
 * @param exceptionFilters the exception stops the adapter is known to offer, before a session has said
 * @param caughtFilter the id of its "stop on caught exceptions too" filter, or null when it has none
 * @param hostedStartCommand the command that asks the language server for an adapter
 *     ({@link DapServerRegistry.Kind#LANGUAGE_SERVER}), or null
 * @param debuggableOnHost whether a remote file is debugged on its own host
 * @param unavailableKey the message key for "this language's debugger is not available here"
 * @param external how an {@link DapServerRegistry.Kind#EXTERNAL} adapter is started and launched; null otherwise
 */
public record DebugLanguage(
        String id,
        String displayName,
        Set<String> editorLanguages,
        DapServerRegistry.Kind kind,
        String launchType,
        String adapterId,
        String defaultInterpreter,
        List<String> adapterArgs,
        DapServerRegistry.@Nullable Kind fallbackKind,
        Availability availability,
        List<DapModels.ExceptionFilterOption> exceptionFilters,
        @Nullable String caughtFilter,
        @Nullable String hostedStartCommand,
        boolean debuggableOnHost,
        String unavailableKey,
        @Nullable ExternalAdapter external) {

    /** What decides that a language's adapter is there. */
    public enum Availability {
        /** The java-debug plugin is located, or the Java server ships it. */
        JAVA_DEBUG_PLUGIN,
        /** The adapter (or what runs it) was found by detection: debugpy, vscode-js-debug, an external command. */
        LOCATED,
        /** Editora's own adapter, or the language server's: nothing to install, only the debugging switch. */
        BUILT_IN
    }

    public DebugLanguage {
        editorLanguages = Set.copyOf(editorLanguages);
        adapterArgs = List.copyOf(adapterArgs);
        exceptionFilters = List.copyOf(exceptionFilters);
        if ((kind == DapServerRegistry.Kind.EXTERNAL) != (external != null)) {
            throw new IllegalArgumentException(id + ": an EXTERNAL adapter, and only one, says how it starts");
        }
    }

    /** The spec {@link DapServerRegistry#specFor} answers for this language. */
    public DapServerRegistry.DapServerSpec spec() {
        return new DapServerRegistry.DapServerSpec(id, kind, launchType, adapterId, defaultInterpreter, adapterArgs);
    }

    /** The id of the "stop on uncaught exceptions" filter, or null when the adapter has no caught one either. */
    public @Nullable String uncaughtFilter() {
        return caughtFilter == null ? null : "uncaught";
    }
}
