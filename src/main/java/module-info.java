module com.editora {
    requires javafx.controls;
    requires javafx.fxml;
    requires org.fxmisc.richtext;
    requires org.fxmisc.flowless;
    requires org.fxmisc.undo;
    requires reactfx;
    requires atlantafx.base;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.dataformat.toml;
    requires org.eclipse.tm4e.core;
    // Apache Lucene's pure-Java Hunspell spell checker (analysis.common) + its store/FST (core).
    requires org.apache.lucene.analysis.common;
    requires org.apache.lucene.core;
    // Lets Lucene read JVM internals for its optimizations (silences a startup warning); jlink bundles it.
    requires jdk.management;
    requires org.commonmark;
    // SVG rasterizer for the Markdown preview's badge images (renders via AWT → java.desktop).
    requires com.github.weisj.jsvg;
    requires jlatexmath; // pure-Java LaTeX math rendering for the Markdown preview/PDF
    requires java.desktop;
    requires java.net.http; // built-in HTTP client for .http request execution
    requires jdk.httpserver; // built-in HttpServer for the HTML Live Preview (com.editora.web), loopback only
    // Apache PDFBox: PDF export. An automatic module (Automatic-Module-Name only) — moditect injects a
    // real descriptor for the jlink dist build (pdfbox + pdfbox-io + fontbox + commons-logging).
    requires org.apache.pdfbox;
    requires org.commonmark.ext.gfm.tables;
    requires org.commonmark.ext.gfm.strikethrough;
    requires org.commonmark.ext.task.list.items;
    requires org.commonmark.ext.autolink;
    requires org.commonmark.ext.front.matter; // YAML front matter
    requires org.commonmark.ext.footnotes;
    requires org.commonmark.ext.heading.anchor; // heading id generation for HTML export
    requires org.commonmark.ext.ins; // ++underline++
    requires java.logging;
    // Language Server Protocol client (LSP4J) + its JSON-RPC transport. Automatic modules
    // (filename-derived names) — moditect injects real descriptors for the jlink dist build.
    requires org.eclipse.lsp4j;
    requires org.eclipse.lsp4j.jsonrpc;
    // Debug Adapter Protocol client (LSP4J DAP). Automatic module, moditect-patched like lsp4j above.
    requires org.eclipse.lsp4j.debug;
    // Gson (already a transitive module via jsonrpc) — the DAP layer parses jdtls's untyped
    // workspace/executeCommand results, which lsp4j hands back as gson JsonElements.
    requires com.google.gson;
    // java-diff-utils: Myers line diff + unified-diff generation for the diff viewer (com.editora.diff).
    // Automatic module — moditect injects a real descriptor for the jlink dist build.
    requires io.github.javadiffutils;
    // Apache MINA SSHD: SFTP client + nio FileSystemProvider for remote (SFTP) file access
    // (com.editora.vfs). The combined sshd-osgi bundle (org.apache.sshd.osgi) avoids the
    // sshd-common/sshd-core split-package conflict; sshd-sftp adds the SFTP FileSystemProvider.
    // Automatic modules — moditect injects descriptors for the jlink dist build.
    requires org.apache.sshd.osgi;
    requires org.apache.sshd.sftp;

    opens com.editora to
            javafx.fxml;
    opens com.editora.ui to
            javafx.fxml;
    opens com.editora.config to
            com.fasterxml.jackson.databind;
    opens com.editora.vfs to
            com.fasterxml.jackson.databind; // RemoteConnection record in connections.json
    opens com.editora.todo to
            com.fasterxml.jackson.databind; // TodoPattern POJO in settings.toml (todoPatterns array)
    opens com.editora.macro to
            com.fasterxml.jackson.databind; // Macro/MacroStep records in macros.json
    // Jackson reflects on the snippet JSON DTO (SnippetManager.Dto). The bundled snippet *resources*
    // need no opens — our own SnippetManager reads them via Class.getResourceAsStream.
    opens com.editora.snippet to
            com.fasterxml.jackson.databind;
    // Jackson reflects on the template JSON DTOs (TemplateRegistry.Dto/FileDto); bundled template
    // resources are read via Class.getResourceAsStream and need no opens.
    opens com.editora.template to
            com.fasterxml.jackson.databind;
    // Jackson reflects on the plugin manifest DTO (PluginManifest); the public plugin API also lives here.
    opens com.editora.plugin to
            com.fasterxml.jackson.databind;
    // tm4e (a separate module) reads these grammar resources via Class.getResourceAsStream;
    // without opening the package, JPMS encapsulates the .tmLanguage.json files and grammar
    // loading silently fails, falling back to the legacy regex highlighter.
    opens com.editora.grammars to
            org.eclipse.tm4e.core;
    // LSP4J dispatches notifications/requests to our LanguageClient via reflection, and Gson (used by
    // jsonrpc) reflectively reads our custom @JsonNotification param DTO (language/status →
    // LanguageStatus). Gson runs in the unnamed module on the classpath under `mvn javafx:run` (and as
    // com.google.gson under jlink), so this must be an UNQUALIFIED opens — a qualified opens to
    // org.eclipse.lsp4j.jsonrpc alone leaves Gson unable to set the DTO fields accessible.
    opens com.editora.lsp;
    // Same as com.editora.lsp: the DAP client (com.editora.dap) receives events via reflection and Gson
    // reflectively reads the DAP DTOs — UNQUALIFIED opens for the unnamed-module Gson under javafx:run.
    opens com.editora.dap;

    exports com.editora;
    exports com.editora.command;
    exports com.editora.config;
    exports com.editora.editor;
    exports com.editora.ui;
    // The plugin API (Plugin, PluginContext, ActiveEditor, ToolWindowSide) — implemented by plugin jars.
    exports com.editora.plugin;
}
