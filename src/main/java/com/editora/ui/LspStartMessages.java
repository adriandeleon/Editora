package com.editora.ui;

import static com.editora.i18n.Messages.tr;

/** The status-line words for a language server Editora did not start ({@link com.editora.lsp.StartRefusal}). */
final class LspStartMessages {

    private LspStartMessages() {}

    /** The status-line words for a {@link com.editora.lsp.StartRefusal}; only a project can be trusted (B111). */
    static String refused(String server, com.editora.lsp.StartRefusal refusal, boolean project) {
        return switch (refusal.kind()) {
            case SERVER_REFUSED -> tr("status.lsp.startRefused", server, refusal.detail());
            case NO_TYPESCRIPT ->
                refusal.detail().isBlank()
                        ? tr("status.lsp.tsNoSdk")
                        : tr("status.lsp.tsSdkWithoutTsserver", refusal.detail());
            case UNTRUSTED_TYPESCRIPT ->
                project
                        ? tr("status.lsp.tsSdkUntrusted", tr("command.lsp.trustProjectSettings"))
                        : tr("status.lsp.tsSdkUntrustedNoProject");
            case NO_JAVA_FOR_SERVER -> tr("status.lsp.javaTooOld", server, refusal.detail());
            case XML_LOADS_MAVEN -> tr("status.lsp.xmlLoadsMaven", server);
        };
    }
}
