package com.editora.dap;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.eclipse.lsp4j.debug.Breakpoint;
import org.eclipse.lsp4j.debug.BreakpointNotVerifiedReason;
import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.ExceptionBreakMode;
import org.eclipse.lsp4j.debug.ExceptionBreakpointsFilter;
import org.eclipse.lsp4j.debug.ExceptionDetails;
import org.eclipse.lsp4j.debug.ExceptionInfoResponse;
import org.eclipse.lsp4j.debug.Module;
import org.eclipse.lsp4j.debug.Source;
import org.eclipse.lsp4j.debug.SourcePresentationHint;
import org.eclipse.lsp4j.debug.StackFrame;
import org.eclipse.lsp4j.debug.StackFramePresentationHint;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DapClient}'s pure readers of what an adapter answers — exception filters, loaded sources and modules,
 * breakpoint statuses, exception details, frame hints, memory addresses, code replace — on the odd and partial
 * answers real adapters give.
 */
class DapClientTest {

    /** A filter as an adapter's JSON gives it: lsp4j's setters refuse the missing fields some adapters leave out. */
    private static ExceptionBreakpointsFilter filter(String id, String label, Boolean on) {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("filter", id);
        o.addProperty("label", label);
        o.addProperty("default", on);
        return new com.google.gson.Gson().fromJson(o, ExceptionBreakpointsFilter.class);
    }

    @Test
    void exceptionFiltersAreReadInTheAdaptersOrderWithTheirLabels() {
        assertEquals(List.of(), DapClient.exceptionFilterOptions(null));
        assertEquals(List.of(), DapClient.exceptionFilterOptions(new Capabilities()));
        Capabilities caps = new Capabilities();
        caps.setExceptionBreakpointFilters(new ExceptionBreakpointsFilter[] {
            filter("raised", "Raised", false),
            null,
            filter(null, "x", true),
            filter("uncaught", " ", true),
            filter("user", null, null)
        });
        List<DapModels.ExceptionFilterOption> options = DapClient.exceptionFilterOptions(caps);
        assertEquals(
                List.of(
                        new DapModels.ExceptionFilterOption("raised", "Raised", false),
                        new DapModels.ExceptionFilterOption("uncaught", "uncaught", true),
                        new DapModels.ExceptionFilterOption("user", "user", false)),
                options);
        assertEquals(List.of("uncaught"), DapClient.defaultExceptionFilters(options));
        assertEquals(List.of("uncaught"), DapClient.withExtras(List.of("uncaught"), List.of(), options));
        assertEquals(List.of("uncaught"), DapClient.withExtras(List.of("uncaught"), List.of("uncaught"), options));
        assertEquals(
                List.of("raised", "uncaught", "other"),
                DapClient.withExtras(List.of("uncaught", "other"), List.of("raised"), options),
                "in the adapter's order, one it did not list last");
    }

    @Test
    void aDataBreakpointsAccessIsWriteUnlessItSaysOtherwise() {
        assertEquals(org.eclipse.lsp4j.debug.DataBreakpointAccessType.READ, DapClient.accessType("read"));
        assertEquals(org.eclipse.lsp4j.debug.DataBreakpointAccessType.READ_WRITE, DapClient.accessType("readWrite"));
        assertEquals(org.eclipse.lsp4j.debug.DataBreakpointAccessType.WRITE, DapClient.accessType(null));
        assertEquals(org.eclipse.lsp4j.debug.DataBreakpointAccessType.WRITE, DapClient.accessType("write"));
    }

    private static Source source(String path, Integer ref, String name) {
        Source s = new Source();
        s.setPath(path);
        s.setSourceReference(ref);
        s.setName(name);
        return s;
    }

    @Test
    void aLoadedSourceIsAFileAReferenceOrNothing() {
        Path file = Path.of("/app/main.js").toAbsolutePath();
        DapModels.LoadedSource onDisk = DapClient.loadedSource(source(file.toString(), null, null), DapPaths.LOCAL);
        assertEquals("main.js", onDisk.name(), "named by its file");
        assertEquals(file, onDisk.file());
        assertEquals(0, onDisk.sourceReference());

        DapModels.LoadedSource internal = DapClient.loadedSource(source("<node_internals>/fs", 0, " "), DapPaths.LOCAL);
        assertNull(internal.file(), "a pseudo-path is no file");
        assertEquals("<node_internals>/fs", internal.name());

        DapModels.LoadedSource relative = DapClient.loadedSource(source("lib/x.js", null, "x"), DapPaths.LOCAL);
        assertNull(relative.file(), "a relative path is no file of this machine");

        DapModels.LoadedSource byRef = DapClient.loadedSource(source(null, 7, "eval"), DapPaths.LOCAL);
        assertEquals(7, byRef.sourceReference());
        assertEquals("", byRef.adapterPath());
        assertEquals(byRef, DapClient.referenceOf(source(null, 7, "eval"), DapPaths.LOCAL));

        assertNull(DapClient.loadedSource(null, DapPaths.LOCAL));
        assertNull(DapClient.loadedSource(source(null, -1, ""), DapPaths.LOCAL), "nothing to name it by");
        assertNull(DapClient.referenceOf(null, DapPaths.LOCAL));
        assertNull(DapClient.referenceOf(source("/a.js", null, null), DapPaths.LOCAL));
        assertNull(DapClient.referenceOf(source("/a.js", 0, null), DapPaths.LOCAL));
    }

    @Test
    void aModuleNeedsAnIdOrAName() {
        assertNull(DapClient.moduleInfo(null));
        Module none = new Module();
        none.setName("");
        assertNull(DapClient.moduleInfo(none));
        Module empty = new Module();
        empty.setId(Either.forRight(""));
        assertNull(DapClient.moduleInfo(empty));
        Module named = new Module();
        named.setName("libc.so.6");
        assertEquals("libc.so.6", DapClient.moduleInfo(named).id(), "no id: the name is the id");
        Module numbered = new Module();
        numbered.setId(Either.forLeft(3));
        numbered.setPath("/lib/x.so");
        DapModels.ModuleInfo m = DapClient.moduleInfo(numbered);
        assertEquals("3", m.id());
        assertEquals("/lib/x.so", m.path());
    }

    private static Breakpoint answered(
            boolean verified, Integer line, String message, BreakpointNotVerifiedReason why) {
        Breakpoint b = new Breakpoint();
        b.setVerified(verified);
        b.setLine(line);
        b.setMessage(message);
        b.setReason(why);
        return b;
    }

    @Test
    void aBreakpointsStatusAndALinesLambdasFoldedIntoOne() {
        assertEquals(
                new DapModels.BreakpointStatus(4, false, true, "no code", -1),
                DapClient.status(4, answered(false, null, "no code", BreakpointNotVerifiedReason.FAILED)));
        assertEquals(
                new DapModels.BreakpointStatus(4, false, false, "", -1),
                DapClient.status(4, answered(false, 0, null, BreakpointNotVerifiedReason.PENDING)));
        assertEquals(
                new DapModels.BreakpointStatus(4, true, false, "", 6),
                DapClient.status(4, answered(true, 7, "", null)));

        List<DapModels.BreakpointStatus> folded = DapClient.perLine(List.of(
                new DapModels.BreakpointStatus(1, true, false, "", -1),
                new DapModels.BreakpointStatus(1, true, false, "moved", 3),
                new DapModels.BreakpointStatus(2, true, false, "first", 2),
                new DapModels.BreakpointStatus(2, false, true, "lambda refused", -1),
                new DapModels.BreakpointStatus(5, false, false, "pending", -1),
                new DapModels.BreakpointStatus(5, false, false, "", -1)));
        assertEquals(
                List.of(
                        new DapModels.BreakpointStatus(1, true, false, "moved", 3),
                        new DapModels.BreakpointStatus(2, false, true, "lambda refused", 2),
                        new DapModels.BreakpointStatus(5, false, false, "pending", -1)),
                folded);
    }

    private static ExceptionDetails details(String full, String type, String message, ExceptionDetails... inner) {
        ExceptionDetails d = new ExceptionDetails();
        d.setFullTypeName(full);
        d.setTypeName(type);
        d.setMessage(message);
        d.setInnerException(inner.length == 0 ? null : inner);
        return d;
    }

    @Test
    void anExceptionIsNamedByItsFullestTypeAndItsCausesFollow() {
        assertNull(DapClient.exceptionInfo(null));
        assertNull(DapClient.exceptionInfo(new ExceptionInfoResponse()), "an answer that names nothing");

        ExceptionInfoResponse byId = new ExceptionInfoResponse();
        byId.setExceptionId("ValueError");
        byId.setDescription("bad value");
        byId.setBreakMode(ExceptionBreakMode.USER_UNHANDLED);
        DapModels.ExceptionInfo idOnly = DapClient.exceptionInfo(byId);
        assertEquals("ValueError", idOnly.type());
        assertEquals("bad value", idOnly.message());
        assertTrue(idOnly.uncaught());

        ExceptionInfoResponse full = new ExceptionInfoResponse();
        full.setExceptionId("ignored");
        full.setBreakMode(ExceptionBreakMode.ALWAYS);
        ExceptionDetails empty = new ExceptionDetails();
        full.setDetails(details(
                " ",
                "IOException",
                " ",
                details("java.io.EOFException", null, "eof", details(null, "Root", "r")),
                null,
                empty));
        full.setDescription("from the description");
        DapModels.ExceptionInfo info = DapClient.exceptionInfo(full);
        assertEquals("IOException", info.type(), "a blank full type name: the short one");
        assertEquals("from the description", info.message());
        assertTrue(info.caught());
        assertEquals(1, info.causes().size(), "a null and an empty cause are dropped");
        assertEquals("java.io.EOFException", info.causes().get(0).type());
        assertEquals("Root", info.causes().get(0).causes().get(0).type());

        for (ExceptionBreakMode mode : List.of(ExceptionBreakMode.NEVER, ExceptionBreakMode.UNHANDLED)) {
            ExceptionInfoResponse r = new ExceptionInfoResponse();
            r.setExceptionId("E");
            r.setBreakMode(mode);
            assertEquals(
                    mode == ExceptionBreakMode.NEVER ? "never" : "unhandled",
                    DapClient.exceptionInfo(r).breakMode());
        }

        ExceptionDetails deep = details("Deep", null, "0");
        for (int i = 1; i <= 12; i++) {
            deep = details("Deep", null, String.valueOf(i), deep);
        }
        ExceptionInfoResponse chain = new ExceptionInfoResponse();
        chain.setDetails(deep);
        int depth = 0;
        for (DapModels.ExceptionInfo c = DapClient.exceptionInfo(chain);
                !c.causes().isEmpty();
                c = c.causes().get(0)) {
            depth++;
        }
        assertEquals(8, depth, "a long chain is cut");
    }

    @Test
    void aFramesHintComesFromItselfOrItsSource() {
        StackFrame label = new StackFrame();
        label.setPresentationHint(StackFramePresentationHint.LABEL);
        assertEquals(DapModels.HINT_LABEL, DapClient.frameHint(label));
        StackFrame subtle = new StackFrame();
        subtle.setPresentationHint(StackFramePresentationHint.SUBTLE);
        assertEquals(DapModels.HINT_SUBTLE, DapClient.frameHint(subtle));
        StackFrame normal = new StackFrame();
        normal.setPresentationHint(StackFramePresentationHint.NORMAL);
        Source deemphasized = new Source();
        deemphasized.setPresentationHint(SourcePresentationHint.DEEMPHASIZE);
        normal.setSource(deemphasized);
        assertEquals(DapModels.HINT_SUBTLE, DapClient.frameHint(normal));
        Source emphasized = new Source();
        emphasized.setPresentationHint(SourcePresentationHint.EMPHASIZE);
        StackFrame plain = new StackFrame();
        plain.setSource(emphasized);
        assertEquals("", DapClient.frameHint(plain));
        assertEquals("", DapClient.frameHint(new StackFrame()));
        assertNull(DapClient.sourcePath(null, DapPaths.LOCAL));
        assertNull(DapClient.sourcePath(new Source()));
        assertNull(DapClient.sourceUri(null));
        assertNull(DapClient.sourceUri(source("/a.java", null, null)));
        assertEquals("jdt://x", DapClient.sourceUri(source("jdt://x", null, null)));
    }

    @Test
    void instructionBreakpointsWithoutAnAnswerAreSet() {
        Breakpoint refused = answered(false, null, null, null);
        Breakpoint said = answered(false, null, "no code there", null);
        assertEquals(
                List.of(
                        new DapModels.InstructionBreakpoint("0x1", false, ""),
                        new DapModels.InstructionBreakpoint("0x2", false, "no code there"),
                        new DapModels.InstructionBreakpoint("0x3", true, "")),
                DapClient.instructionResults(List.of("0x1", "0x2", "0x3"), new Breakpoint[] {refused, said}));
        assertEquals(
                List.of(new DapModels.InstructionBreakpoint("0x1", true, "")),
                DapClient.instructionResults(List.of("0x1"), null));
    }

    @Test
    void aFarOffsetIsAddedToAnAddressReference() {
        assertEquals(new DapClient.MemoryAddress("ref", -5), DapClient.memoryAddress("ref", -5));
        long far = 1L << 40;
        assertEquals(new DapClient.MemoryAddress("0x10000000010", 0), DapClient.memoryAddress("0x10", far));
        assertEquals(new DapClient.MemoryAddress("0x10000000010", 0), DapClient.memoryAddress(" 16 ", far));
        assertEquals(new DapClient.MemoryAddress("0x10000000010", 0), DapClient.memoryAddress("0X10", far));
        assertNull(DapClient.memoryAddress("opaque", far));
        assertNull(DapClient.memoryAddress(null, far));
        assertNull(DapClient.memoryAddress("0x10", -far), "before address zero");
    }

    @Test
    void aCodeReplaceAnswerIsReadInEveryShape() {
        assertEquals(new DapModels.CodeReplace(List.of(), ""), DapClient.codeReplace(null));
        assertEquals(new DapModels.CodeReplace(List.of(), ""), DapClient.codeReplace("not an object"));
        assertEquals(
                new DapModels.CodeReplace(List.of("a.B"), "too late"),
                DapClient.codeReplace(Map.of("changedClasses", List.of("a.B", " "), "errorMessage", "too late")));
        assertEquals(
                new DapModels.CodeReplace(List.of(), ""),
                DapClient.codeReplace(
                        com.google.gson.JsonParser.parseString("{\"changedClasses\":{},\"errorMessage\":[1]}")));
        assertEquals(
                new DapModels.CodeReplace(List.of(), ""),
                DapClient.codeReplace(com.google.gson.JsonParser.parseString("{\"changedClasses\":[null,[1]]}")));
        assertEquals(
                new DapModels.CodeReplace(List.of(), ""), DapClient.codeReplace(new DapClient.RedefineClassesBody()));
    }
}
