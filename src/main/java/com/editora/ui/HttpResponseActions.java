package com.editora.ui;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javafx.application.Platform;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.stage.FileChooser;

import com.editora.editor.EditorBuffer;
import com.editora.http.CurlExport;
import com.editora.http.HttpExchange;
import com.editora.http.HttpResponseFormat;
import com.editora.http.HttpResult;
import com.editora.io.StagedExport;

import static com.editora.i18n.Messages.tr;

/**
 * What the response view does with a response it shows: Copy as cURL (from how the request was sent), Open in tab
 * (the body as received) and Save (the bytes as received, or a status-and-headers report). FX thread; Save writes in
 * the background and reports in the status bar.
 */
final class HttpResponseActions {

    private final CoordinatorHost host;
    private final HttpClientCoordinator.WindowOps ops;

    HttpResponseActions(CoordinatorHost host, HttpClientCoordinator.WindowOps ops) {
        this.host = host;
        this.ops = ops;
    }

    void copyAsCurl(HttpExchange ex) {
        HttpExchange.Details how = ex.details();
        if (HttpExchange.isBodyPlaceholder(ex.requestBody())
                && how.bodyFile() == null
                && how.formParts().isEmpty()) {
            // Saved by an older version, which kept only the body's summary: a command would upload that text.
            host.setStatus(tr("status.http.curlUnavailable"));
            return;
        }
        String curl = CurlExport.toCurl(ex); // from how it was sent: file upload, form fields, digest, -L
        ClipboardContent cc = new ClipboardContent();
        cc.putString(curl);
        Clipboard.getSystemClipboard().setContent(cc);
        host.setStatus(tr("status.http.curlCopied"));
    }

    void openInTab(HttpExchange ex) {
        HttpResult r = ex.result();
        if (r.failed()) {
            host.setStatus(tr("status.http.noResponseSelected"));
            return;
        }
        if (r.binary()) {
            host.setStatus(tr("status.http.binaryResponse")); // no text form — Save keeps the bytes
            return;
        }
        EditorBuffer buffer = new EditorBuffer();
        buffer.setDisplayName("response" + HttpResponseFormat.extensionFor(r.contentType()));
        ops.openTab(buffer);
        buffer.setContent(r.body()); // the body as received; the viewer's pretty-printing is display-only
    }

    /**
     * Save response: the body exactly as received (the default — the bytes, so an image or an archive is the
     * real file), or the whole response with its status line and headers as text.
     */
    void save(EditorBuffer buffer, HttpExchange ex, HttpHistoryEntry entry) {
        if (ex == null || ex.result().failed()) {
            host.setStatus(tr("status.http.noResponseSelected"));
            return;
        }
        HttpResult r = ex.result();
        String ext = HttpResponseFormat.extensionFor(r.contentType());
        FileChooser chooser = new FileChooser();
        chooser.setTitle(tr("dialog.saveResponse.title"));
        FileChooser.ExtensionFilter bodyOnly =
                new FileChooser.ExtensionFilter(tr("dialog.saveResponse.body"), "*" + (ext.isEmpty() ? ".txt" : ext));
        FileChooser.ExtensionFilter report =
                new FileChooser.ExtensionFilter(tr("dialog.saveResponse.withHeaders"), "*.txt");
        chooser.getExtensionFilters().setAll(bodyOnly, report);
        chooser.setSelectedExtensionFilter(bodyOnly);
        chooser.setInitialFileName(saveName(ex.url(), ext));
        Path file = buffer.getPath();
        Path dir = file != null ? file.toAbsolutePath().getParent() : ops.projectRoot(); // untitled: the project
        if (dir != null && Files.isDirectory(dir)) {
            chooser.setInitialDirectory(dir.toFile());
        }
        File chosen = chooser.showSaveDialog(host.window());
        if (chosen == null) {
            return;
        }
        // Written off the FX thread (a response may be 50 MB). Staged: the Save dialog may have confirmed replacing a
        // file, which a failed write must not empty.
        Path target = chosen.toPath();
        boolean asReport = chooser.getSelectedExtensionFilter() == report;
        boolean partial = entry != null && entry.bodyCut();
        HttpEnvCache.IO.execute(() -> {
            String status;
            try {
                if (asReport) {
                    StagedExport.writeString(target, HttpResponseFormat.render(r));
                } else {
                    byte[] bytes = r.rawBody() != null && r.rawBody().length > 0
                            ? r.rawBody()
                            : r.body().getBytes(StandardCharsets.UTF_8);
                    StagedExport.write(target, bytes);
                }
                status = tr(partial ? "status.http.savedPartial" : "status.http.saved", target.getFileName());
            } catch (IOException | RuntimeException e) {
                status = tr("status.http.saveFailed", e.getMessage());
            }
            String done = status;
            Platform.runLater(() -> host.setStatus(done));
        });
    }

    /**
     * The file name Save offers: the URL's last path segment when it already names a file ({@code logo.png}),
     * else {@code response} plus the extension of the content type ({@code response.json}). Pure.
     */
    static String saveName(String url, String ext) {
        String u = url == null ? "" : url;
        int cut = u.length();
        for (char c : new char[] {'?', '#'}) {
            int at = u.indexOf(c);
            if (at >= 0) {
                cut = Math.min(cut, at);
            }
        }
        String path = u.substring(0, cut);
        int scheme = path.indexOf("://");
        int slash = path.lastIndexOf('/');
        String last = slash < 0 || slash <= scheme + 2 ? "" : path.substring(slash + 1);
        if (last.matches("[\\w-]+(\\.[\\w-]+)*\\.[A-Za-z0-9]{1,8}")) {
            return last;
        }
        return "response" + (ext == null || ext.isEmpty() ? ".txt" : ext);
    }
}
