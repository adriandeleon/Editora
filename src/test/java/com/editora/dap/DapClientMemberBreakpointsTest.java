package com.editora.dap;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments;
import org.eclipse.lsp4j.debug.DataBreakpointAccessType;
import org.eclipse.lsp4j.debug.DataBreakpointInfoArguments;
import org.eclipse.lsp4j.debug.DataBreakpointInfoResponse;
import org.eclipse.lsp4j.debug.SetDataBreakpointsArguments;
import org.eclipse.lsp4j.debug.SetDataBreakpointsResponse;
import org.eclipse.lsp4j.debug.SetFunctionBreakpointsArguments;
import org.eclipse.lsp4j.debug.SetFunctionBreakpointsResponse;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Method breakpoints and field watchpoints go to the adapter as DAP function and data breakpoints: installed by the
 * {@code initialized} event, sent again on every change after it, and never to an adapter that does not offer them.
 */
class DapClientMemberBreakpointsTest {

    private static final class RecordingServer implements IDebugProtocolServer {
        final List<List<String>> functionCalls = new ArrayList<>();
        final List<List<String>> dataCalls = new ArrayList<>();
        SetFunctionBreakpointsArguments lastFunctions;
        SetDataBreakpointsArguments lastData;
        DataBreakpointInfoArguments infoAsked;

        @Override
        public CompletableFuture<SetFunctionBreakpointsResponse> setFunctionBreakpoints(
                SetFunctionBreakpointsArguments args) {
            lastFunctions = args;
            List<String> names = new ArrayList<>();
            for (var b : args.getBreakpoints()) {
                names.add(b.getName());
            }
            functionCalls.add(names);
            return CompletableFuture.completedFuture(new SetFunctionBreakpointsResponse());
        }

        @Override
        public CompletableFuture<SetDataBreakpointsResponse> setDataBreakpoints(SetDataBreakpointsArguments args) {
            lastData = args;
            List<String> ids = new ArrayList<>();
            for (var b : args.getBreakpoints()) {
                ids.add(b.getDataId());
            }
            dataCalls.add(ids);
            return CompletableFuture.completedFuture(new SetDataBreakpointsResponse());
        }

        @Override
        public CompletableFuture<DataBreakpointInfoResponse> dataBreakpointInfo(DataBreakpointInfoArguments args) {
            infoAsked = args;
            DataBreakpointInfoResponse r = new DataBreakpointInfoResponse();
            if ("count".equals(args.getName())) {
                r.setDataId("demo.Counter#count");
                r.setDescription("Counter.count : int");
                r.setAccessTypes(new DataBreakpointAccessType[] {
                    DataBreakpointAccessType.READ, DataBreakpointAccessType.WRITE, DataBreakpointAccessType.READ_WRITE
                });
            } else {
                r.setDescription("not a field");
            }
            return CompletableFuture.completedFuture(r);
        }

        @Override
        public CompletableFuture<Void> configurationDone(ConfigurationDoneArguments args) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final DapClient.Host SILENT_HOST = new DapClient.Host() {
        @Override
        public void onStopped(int threadId, String reason) {}

        @Override
        public void onContinued() {}

        @Override
        public void onOutput(String text, String category) {}

        @Override
        public void onTerminated() {}

        @Override
        public void onError(String message) {}
    };

    private static DapClient connected(RecordingServer server, boolean functions, boolean data) throws Exception {
        DapClient c = new DapClient(SILENT_HOST);
        var f = DapClient.class.getDeclaredField("server");
        f.setAccessible(true);
        f.set(c, server);
        Capabilities caps = new Capabilities();
        caps.setSupportsFunctionBreakpoints(functions);
        caps.setSupportsDataBreakpoints(data);
        var cf = DapClient.class.getDeclaredField("capabilities");
        cf.setAccessible(true);
        cf.set(c, caps);
        return c;
    }

    private static final DapModels.MemberBreakpoint TOTAL =
            new DapModels.MemberBreakpoint(false, "demo.Order#total", "", "n > 2", "");
    private static final DapModels.MemberBreakpoint COUNT =
            new DapModels.MemberBreakpoint(true, "demo.Counter#count", "readWrite", "", "3");

    @Test
    void theInitializedEventInstallsThemAndALaterChangeIsSentAtOnce() throws Exception {
        RecordingServer server = new RecordingServer();
        DapClient c = connected(server, true, true);
        c.setMemberBreakpoints(List.of(TOTAL, COUNT));
        assertTrue(server.functionCalls.isEmpty(), "only after the initialized event");

        c.initialized();
        assertEquals(List.of(List.of("demo.Order#total")), server.functionCalls);
        assertEquals(List.of(List.of("demo.Counter#count")), server.dataCalls);
        assertEquals("n > 2", server.lastFunctions.getBreakpoints()[0].getCondition());
        assertNull(server.lastFunctions.getBreakpoints()[0].getHitCondition(), "blank is not sent");
        assertEquals(DataBreakpointAccessType.READ_WRITE, server.lastData.getBreakpoints()[0].getAccessType());
        assertEquals("3", server.lastData.getBreakpoints()[0].getHitCondition());

        c.setMemberBreakpoints(List.of(COUNT)); // the method breakpoint removed while running
        assertEquals(List.of(), server.functionCalls.get(1), "an empty set clears the adapter's");
        assertEquals(List.of("demo.Counter#count"), server.dataCalls.get(1));
    }

    @Test
    void anAdapterWithoutThemIsSentNeither() throws Exception {
        RecordingServer server = new RecordingServer();
        DapClient c = connected(server, false, false);
        c.setMemberBreakpoints(List.of(TOTAL, COUNT));
        c.initialized();
        c.setMemberBreakpoints(List.of());
        assertTrue(server.functionCalls.isEmpty());
        assertTrue(server.dataCalls.isEmpty());
    }

    @Test
    void noneSetMeansNoRequestsAtTheStart() throws Exception {
        RecordingServer server = new RecordingServer();
        DapClient c = connected(server, true, true);
        c.initialized();
        assertTrue(server.functionCalls.isEmpty() && server.dataCalls.isEmpty());
    }

    @Test
    void dataBreakpointInfoNamesTheFieldOrNothing() throws Exception {
        RecordingServer server = new RecordingServer();
        DapClient c = connected(server, true, true);
        var field = c.dataBreakpointInfo(7, "count").get();
        assertEquals(7, server.infoAsked.getVariablesReference());
        assertTrue(field.isPresent());
        assertEquals("demo.Counter#count", field.get().dataId());
        assertEquals(List.of("read", "write", "readWrite"), field.get().accessTypes());
        assertTrue(c.dataBreakpointInfo(7, "local").get().isEmpty(), "no dataId: nothing to watch");
    }

    @Test
    void accessTypesDefaultToWrite() {
        assertEquals(DataBreakpointAccessType.WRITE, DapClient.accessType("write"));
        assertEquals(DataBreakpointAccessType.WRITE, DapClient.accessType(""));
        assertEquals(DataBreakpointAccessType.READ, DapClient.accessType("read"));
    }
}
