package com.editora.lsp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A server's own warnings reach the log the Debug Log shows (shell review C2): bash-language-server reports a
 * missing ShellCheck only as a {@code window/logMessage} warning, which used to be logged at FINE and dropped.
 */
class LanguageServerLogTest {

    @Test
    void warningsAndErrorsAreLoggedAtInfoAndChatterIsNot() {
        Logger log = Logger.getLogger(LanguageServerSession.class.getName());
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (log.isLoggable(record.getLevel())) {
                    records.add(record);
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        log.addHandler(handler);
        try {
            var spec = new LspServerRegistry.ServerSpec("bash", List.of("bash-language-server"), List.of());
            var session = new LanguageServerSession(spec, Path.of("/tmp"), d -> {}, (t, m) -> {});
            session.logMessage(new MessageParams(
                    MessageType.Warning,
                    "ShellCheck: disabling linting as no executable was found at path 'shellcheck'"));
            session.logMessage(new MessageParams(MessageType.Log, "BackgroundAnalysis: resolving glob"));

            assertEquals(
                    List.of("[bash] ShellCheck: disabling linting as no executable was found at path 'shellcheck'"),
                    records.stream()
                            .filter(r -> r.getLevel().intValue() >= java.util.logging.Level.INFO.intValue())
                            .map(LogRecord::getMessage)
                            .toList());
        } finally {
            log.removeHandler(handler);
        }
    }
}
