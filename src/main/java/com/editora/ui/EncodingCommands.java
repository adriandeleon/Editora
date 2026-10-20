package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Tab;

import com.editora.editor.EditorBuffer;
import com.editora.editorconfig.EditorConfigCharset;
import com.editora.io.TextEncodings;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Buffer: Reopen with Encoding…, Buffer: Save with Encoding… and the status-bar encoding segment's chooser.
 *
 * <p>Reopening reads the file again in the chosen encoding — strictly, so a file that is not valid in it is
 * left as it is and the first unreadable position is named — and remembers the choice for the file
 * ({@code WorkspaceState.fileEncodings}), which the next open honours. Saving with an encoding makes it the
 * one the buffer is written in from then on, whatever {@code .editorconfig} says; it is a pending change until
 * the file is saved, like a line-ending conversion, and is remembered once it is on disk.
 */
final class EncodingCommands {

    static final String REOPEN = "buffer.reopenWithEncoding";
    static final String SAVE = "buffer.saveWithEncoding";
    static final String CHOOSE = "buffer.chooseEncoding";

    private final FileWorkflowCoordinator.Host host;
    private final FileWorkflowCoordinator files;

    /**
     * Asked before reopening a buffer with unsaved edits (they are lost; Local History keeps a copy). Replaced
     * by tests.
     */
    volatile BiPredicate<EditorBuffer, String> confirmDiscard = this::confirmDiscard;

    EncodingCommands(FileWorkflowCoordinator.Host host, FileWorkflowCoordinator files) {
        this.host = host;
        this.files = files;
    }

    /** {@code buffer.chooseEncoding}: what clicking the status bar's encoding offers. */
    void choose() {
        if (host.activeBuffer() == null) {
            host.setStatus(tr("status.noFileOpen"));
            return;
        }
        host.editorSettings().chooseSetting(CHOOSE, () -> List.of(REOPEN, SAVE), id -> tr("command." + id), id -> {
            if (REOPEN.equals(id)) {
                reopen();
            } else {
                save();
            }
        });
    }

    /** {@code buffer.reopenWithEncoding}: reads the active file again in an encoding the user picks. */
    void reopen() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null) {
            host.setStatus(tr("status.noFileOpen"));
            return;
        }
        Path file = buffer.getPath();
        if (file == null) {
            host.setStatus(tr("status.encoding.untitled", tr("command." + SAVE)));
            return;
        }
        if (buffer.isTruncatedLoad()) {
            host.setStatus(tr("status.encoding.partial", buffer.getTitle()));
            return;
        }
        if (buffer.encryption().isEncrypted()) {
            host.setStatus(tr("encryption.status.noReopenEncoding")); // the file on disk is ciphertext
            return;
        }
        pick(REOPEN, buffer.getEffectiveCharset(), name -> reopenAs(buffer, name, done -> {}));
    }

    /**
     * Reads {@code buffer}'s file again as {@code name} (asking first when that discards unsaved edits);
     * {@code done} hears on the FX thread whether the text was replaced.
     */
    void reopenAs(EditorBuffer buffer, String name, Consumer<Boolean> done) {
        Path file = buffer.getPath();
        if (buffer.isDisposed() || file == null) {
            done.accept(false);
            return;
        }
        if (buffer.isDirty() && !confirmDiscard.test(buffer, name)) {
            done.accept(false);
            return;
        }
        files.fileLoadExecutor.execute(() -> {
            try {
                String editorConfigCharset = editorConfigCharsetFor(file); // reads .editorconfig files
                byte[] bytes = Files.readAllBytes(file);
                TextEncodings.Decoded decoded = TextEncodings.decode(bytes, name);
                EditorConfigCharset.Decoded detected = EditorConfigCharset.decodeLossless(bytes, editorConfigCharset);
                Platform.runLater(() -> reopened(buffer, file, decoded, detected, done));
            } catch (IOException | RuntimeException e) { // a closed SFTP file system may raise either
                Platform.runLater(() -> {
                    host.setStatus(tr("status.failedReload", file.getFileName(), RemoteReadFailure.reason(file, e)));
                    done.accept(false);
                });
            }
        });
    }

    private void reopened(
            EditorBuffer buffer,
            Path file,
            TextEncodings.Decoded decoded,
            EditorConfigCharset.Decoded detected,
            Consumer<Boolean> done) {
        if (buffer.isDisposed() || !file.equals(buffer.getPath())) {
            done.accept(false);
            return;
        }
        String shown = EditorConfigCharset.displayName(decoded.charset());
        if (!decoded.ok()) {
            host.setStatus(tr(
                    "status.encoding.notValid",
                    file.getFileName(),
                    shown,
                    String.valueOf(decoded.line()),
                    String.valueOf(decoded.column())));
            done.accept(false);
            return;
        }
        // A choice detection would make anyway is not worth remembering: the file opens that way regardless.
        boolean detectedAlready = !detected.assumed() && detected.charset().equals(decoded.charset());
        files.rememberEncoding(file, detectedAlready ? null : decoded.charset());
        Tab tab = host.tabFor(buffer);
        files.reloadFromDisk(tab, buffer, applied -> {
            if (applied) {
                host.setStatus(tr("status.encoding.reopened", file.getFileName(), shown));
            }
            done.accept(applied);
        });
    }

    /** {@code buffer.saveWithEncoding}: makes the active buffer be written in an encoding the user picks. */
    void save() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null) {
            host.setStatus(tr("status.noFileOpen"));
            return;
        }
        if (buffer.isTruncatedLoad()) {
            host.setStatus(tr("status.encoding.partial", buffer.getTitle()));
            return;
        }
        if (buffer.isCharsetAssumed()) {
            // Its text is the file's bytes shown through a stand-in; encoding that text anew writes mojibake.
            host.setStatus(tr(
                    "status.encoding.unknown",
                    buffer.getTitle(),
                    EditorConfigCharset.displayName(buffer.getDetectedCharset()),
                    tr("command." + REOPEN)));
            return;
        }
        pick(SAVE, buffer.getEffectiveCharset(), name -> saveIn(buffer, name));
    }

    /** Makes {@code buffer} be written in {@code name} from its next save on, if its text fits that encoding. */
    void saveIn(EditorBuffer buffer, String name) {
        if (buffer.isDisposed()) {
            return;
        }
        String text = buffer.getContent();
        String shown = EditorConfigCharset.displayName(name);
        if (!EditorConfigCharset.canEncode(text, name)) {
            SaveEncoding.Unencodable first = SaveEncoding.firstUnpairedSurrogate(text);
            if (first == null) {
                first = SaveEncoding.firstUnencodable(text, name);
            }
            host.setStatus(tr(
                    "status.encoding.cannotRepresent",
                    shown,
                    first == null ? "?" : first.display(),
                    first == null ? "?" : String.valueOf(first.line())));
            return;
        }
        if (!name.equals(buffer.getDetectedCharset())) {
            files.setWritesBom(buffer, true); // a UTF-16 newly chosen is written with its byte-order mark
        }
        buffer.setUserCharset(name);
        Tab tab = host.tabFor(buffer);
        if (tab != null) {
            host.updateTabMeta(tab, buffer);
        }
        host.editorSettings().refreshStatusBarFor(buffer);
        host.setStatus(tr(
                name.equals(buffer.getDetectedCharset()) ? "status.encoding.kept" : "status.encoding.willSave",
                buffer.getTitle(),
                shown));
    }

    /** The encoding picker: the common encodings with their script, then every other one the runtime has. */
    private void pick(String commandId, String current, Consumer<String> onChoose) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (TextEncodings.Encoding e : TextEncodings.choices()) {
            String name = EditorConfigCharset.displayName(e.name());
            labels.put(
                    e.name(),
                    e.group() == TextEncodings.Group.OTHER
                            ? name
                            : tr(
                                    "encoding.choice",
                                    name,
                                    tr("encoding.group." + e.group().name())));
        }
        host.editorSettings()
                .chooseSetting(
                        commandId,
                        () -> List.copyOf(labels.keySet()),
                        name -> labels.getOrDefault(name, name),
                        () -> current,
                        onChoose);
    }

    private @Nullable String editorConfigCharsetFor(Path file) {
        if (host.editorSettings().editorConfigEnabled() && com.editora.vfs.Vfs.isLocal(file)) {
            return com.editora.editorconfig.EditorConfig.resolveFor(file).charset();
        }
        return null;
    }

    private boolean confirmDiscard(EditorBuffer buffer, String charset) {
        Alert alert = Dialogs.styled(new Alert(Alert.AlertType.CONFIRMATION));
        alert.initOwner(host.stage());
        alert.setTitle(tr("command." + REOPEN));
        alert.setHeaderText(
                tr("dialog.reopenEncoding.header", buffer.getTitle(), EditorConfigCharset.displayName(charset)));
        alert.setContentText(tr("dialog.reopenEncoding.content"));
        return alert.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }
}
