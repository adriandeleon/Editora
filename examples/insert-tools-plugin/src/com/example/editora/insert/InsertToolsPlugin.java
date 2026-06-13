package com.example.editora.insert;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.function.Supplier;

import com.editora.plugin.Plugin;
import com.editora.plugin.PluginContext;

/**
 * Inserts generated text at the caret: a UUID, or the current date/time in a few common formats. Each
 * command pulls a fresh value (a {@link Supplier}) at invocation time, so values are current.
 */
public class InsertToolsPlugin implements Plugin {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public void start(PluginContext ctx) {
        ins(ctx, "uuid", "Insert: UUID", () -> UUID.randomUUID().toString());
        ins(ctx, "timestampIso", "Insert: Timestamp (ISO-8601)",
                () -> LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        ins(ctx, "date", "Insert: Date (yyyy-MM-dd)", () -> LocalDate.now().format(DATE));
        ins(ctx, "time", "Insert: Time (HH:mm:ss)", () -> LocalTime.now().format(TIME));
        ins(ctx, "datetime", "Insert: Date & Time", () -> LocalDateTime.now().format(DATETIME));
    }

    private void ins(PluginContext ctx, String id, String title, Supplier<String> value) {
        ctx.registerCommand(id, title, () -> ctx.activeEditor().insertAtCaret(value.get()));
    }
}
