package com.editora.dap;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.debug.ExceptionBreakMode;
import org.eclipse.lsp4j.debug.ExceptionOptions;
import org.eclipse.lsp4j.debug.SetExceptionBreakpointsArguments;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Exception breakpoints of Python types told to debugpy as {@code exceptionOptions} (perf review 2026-11-23, D1). */
class PythonExceptionTypesTest {

    private static final DapClient.Host NO_HOST = new DapClient.Host() {
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

    @Test
    void onlyBuiltinTypesNamedPlainlyAreTold() {
        assertEquals(List.of("ValueError"), PythonExceptionTypes.typesOf("ValueError"));
        assertEquals(List.of("KeyError", "TypeError"), PythonExceptionTypes.typesOf(" keyerror , builtins.TypeError "));
        assertEquals(List.of("LookupError"), PythonExceptionTypes.typesOf("LookupError, !KeyError"), "excluded later");
        assertNull(PythonExceptionTypes.typesOf(""), "any type");
        assertNull(PythonExceptionTypes.typesOf("!KeyError"), "any type but one");
        assertNull(PythonExceptionTypes.typesOf("*Error"), "a glob");
        assertNull(PythonExceptionTypes.typesOf("ValueError, MyError"), "a type debugpy would have to import");
        assertNull(PythonExceptionTypes.typesOf("ExceptionGroup"), "not in every Python 3");
    }

    private static List<String> describe(ExceptionOptions[] options) {
        return Arrays.stream(options)
                .map(o -> o.getBreakMode() + " "
                        + Arrays.stream(o.getPath())
                                .map(p -> String.join("|", p.getNames()))
                                .toList())
                .toList();
    }

    @Test
    void theUsersOwnChoiceIsToldAsOptionsToo() {
        assertEquals(
                List.of(
                        ExceptionBreakMode.UNHANDLED + " [Python Exceptions]",
                        ExceptionBreakMode.ALWAYS + " [Python Exceptions, ValueError|KeyError]"),
                describe(PythonExceptionTypes.options(List.of("uncaught"), List.of("ValueError", "KeyError"))));
        assertEquals(
                List.of(ExceptionBreakMode.ALWAYS + " [Python Exceptions, ValueError]"),
                describe(PythonExceptionTypes.options(List.of(), List.of("ValueError"))));
        assertEquals(
                List.of(
                        ExceptionBreakMode.ALWAYS + " [Python Exceptions]",
                        ExceptionBreakMode.USER_UNHANDLED + " [Python Exceptions]"),
                describe(PythonExceptionTypes.options(List.of("raised", "userUnhandled"), List.of("ValueError"))),
                "raised already stops on every type");
    }

    private static SetExceptionBreakpointsArguments lastSent(FakeDebugAdapter.Session session, int count)
            throws InterruptedException {
        session.awaitRequests("setExceptionBreakpoints", count);
        return session.exceptionBreakpoints.get(session.exceptionBreakpoints.size() - 1);
    }

    @Test
    void anAdapterWithExceptionOptionsIsToldTheTypesInPlaceOfTheRaisedFilter() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            adapter.exceptionOptions = true;
            DapClient client = new DapClient(NO_HOST);
            client.setExceptionFilters(List.of("uncaught"));
            client.setExtraExceptionFilters(List.of("raised"), List.of("ValueError"));
            client.connect(adapter.port(), "test").get(10, TimeUnit.SECONDS);
            client.launch(Map.of("type", "test", "request", "launch")).get(10, TimeUnit.SECONDS);
            FakeDebugAdapter.Session session = adapter.awaitSession();
            session.awaitRequest("configurationDone");
            int before = session.exceptionBreakpoints.size();

            // Told live, as an exception breakpoint added during the session is (the capabilities are known by then).
            client.setExtraExceptionFilters(List.of("raised"), List.of("ValueError", "KeyError"));
            SetExceptionBreakpointsArguments sent = lastSent(session, before + 1);
            assertEquals(List.of("uncaught"), List.of(sent.getFilters()), "not raised: every exception");
            assertEquals(
                    List.of(
                            ExceptionBreakMode.UNHANDLED + " [Python Exceptions]",
                            ExceptionBreakMode.ALWAYS + " [Python Exceptions, ValueError|KeyError]"),
                    describe(sent.getExceptionOptions()));

            // The breakpoint changed to a type that cannot be told: back to the raised filter.
            client.setExtraExceptionFilters(List.of("raised"), List.of());
            SetExceptionBreakpointsArguments again = lastSent(session, before + 2);
            assertEquals(List.of("uncaught", "raised"), List.of(again.getFilters()));
            assertNull(again.getExceptionOptions());
            client.dispose();
        }
    }

    @Test
    void anAdapterWithoutExceptionOptionsGetsTheRaisedFilter() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            DapClient client = new DapClient(NO_HOST);
            client.setExceptionFilters(List.of("uncaught"));
            client.setExtraExceptionFilters(List.of("raised"), List.of("ValueError"));
            client.connect(adapter.port(), "test").get(10, TimeUnit.SECONDS);
            client.launch(Map.of("type", "test", "request", "launch")).get(10, TimeUnit.SECONDS);
            FakeDebugAdapter.Session session = adapter.awaitSession();
            session.awaitRequest("configurationDone");

            SetExceptionBreakpointsArguments sent = lastSent(session, 1);
            assertEquals(List.of("uncaught", "raised"), List.of(sent.getFilters()));
            assertNull(sent.getExceptionOptions());
            client.dispose();
        }
    }
}
