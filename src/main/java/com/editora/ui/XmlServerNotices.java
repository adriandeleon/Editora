package com.editora.ui;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.editora.lsp.XmlServerSettings;
import com.editora.text.LspDiagnostic;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * What the XML server (lemminx) says that only makes sense in VS Code, said the Editora way: its symbol-limit
 * notice names a setting it assumes the client has (XML review A13), and a schema it was not allowed to download
 * reads only "Downloading external resources is disabled." on the file, with nothing saying where that is decided
 * (B2). One per window.
 */
final class XmlServerNotices {

    /** lemminx's {@code DownloadResourceDisabled} diagnostic code. */
    static final String DOWNLOAD_DISABLED = "DownloadResourceDisabled";

    private static final Pattern SYMBOL_LIMIT = Pattern.compile(
            "^(?:(.+?): )?For performance reasons, document symbols have been limited to (\\d{1,9}) items\\..*",
            Pattern.DOTALL);

    /** Whether the blocked-download line was said since the XML settings were last applied. */
    private boolean downloadsOffSaid;

    /** The XML settings were applied again (a change, a trust decision): a blocked download is worth saying again. */
    void reset() {
        downloadsOffSaid = false;
    }

    /**
     * The status line for a file's new diagnostics when one is a schema lemminx was not allowed to download, the
     * first time since {@link #reset}; else null. {@code said} words it for that file: what would let the download
     * through there (XML/pom review 3, C25).
     */
    @Nullable
    String onDiagnostics(List<LspDiagnostic> diagnostics, java.util.function.Supplier<String> said) {
        if (downloadsOffSaid || diagnostics == null) {
            return null;
        }
        for (LspDiagnostic d : diagnostics) {
            if (DOWNLOAD_DISABLED.equals(d.code())) {
                downloadsOffSaid = true;
                return said.get();
            }
        }
        return null;
    }

    /**
     * {@code message} from {@code serverId}, with lemminx's symbol-limit notice ("If a new limit is set, please close
     * and reopen this file") replaced by one that names Editora's setting; anything else unchanged.
     */
    static String rewrite(@Nullable String serverId, @Nullable String message) {
        if (message == null || !XmlServerSettings.isLemminx(serverId)) {
            return message == null ? "" : message;
        }
        Matcher m = SYMBOL_LIMIT.matcher(message.strip());
        if (!m.matches()) {
            return message;
        }
        String file = m.group(1);
        String text = tr("status.lsp.xmlSymbolLimit", Integer.parseInt(m.group(2)));
        return file == null ? text : file + ": " + text;
    }
}
