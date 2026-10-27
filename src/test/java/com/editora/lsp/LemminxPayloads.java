package com.editora.lsp;

import java.nio.file.Path;
import java.util.Map;

import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler;

/** What lemminx (vscode-xml 0.29) actually sends, captured from the XML review's transcripts (2026-10-27). */
public final class LemminxPayloads {

    private LemminxPayloads() {}

    /** The grammar-less document the "Generate … and bind" fixes are offered on. */
    public static final String NOGRAMMAR_XML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<config><server host=\"localhost\" port=\"8080\"><timeout>30</timeout></server>"
            + "<users><user name=\"a\"/><user name=\"b\"/></users></config>\n";

    /** The DTD the fix writes. */
    public static final String NOGRAMMAR_DTD = "<!ELEMENT config (server,users)>\n<!ELEMENT server (timeout)>\n"
            + "<!ATTLIST server host NMTOKEN #REQUIRED>\n<!ATTLIST server port NMTOKEN #REQUIRED>\n"
            + "<!ELEMENT timeout (#PCDATA)>\n<!ELEMENT users (user+)>\n<!ELEMENT user EMPTY>\n"
            + "<!ATTLIST user name NMTOKEN #REQUIRED>";

    /**
     * {@code codeAction/resolve}'s edit for "Generate 'nogrammar.dtd' and bind with DOCTYPE", verbatim but for
     * the folder: create the DTD, a {@code TextDocumentEdit} on it with {@code "version": 0} (a document no
     * client ever opened), then the DOCTYPE insertion into the XML file at the version it has open (1).
     */
    public static WorkspaceEdit generateDtdAndBind(Path folder) {
        String dir = folder.toUri().toString().replaceAll("/$", "");
        String json = "{\"documentChanges\": [{\"uri\": \"DIR/nogrammar.dtd\", \"options\": {\"overwrite\": false,"
                + " \"ignoreIfExists\": true}, \"kind\": \"create\"}, {\"textDocument\": {\"version\": 0, \"uri\":"
                + " \"DIR/nogrammar.dtd\"}, \"edits\": [{\"range\": {\"start\": {\"line\": 0, \"character\": 0},"
                + " \"end\": {\"line\": 0, \"character\": 0}}, \"newText\": "
                + new com.google.gson.Gson().toJson(NOGRAMMAR_DTD)
                + "}]}, {\"textDocument\": {\"version\": 1, \"uri\": \"DIR/nogrammar.xml\"}, \"edits\": [{\"range\":"
                + " {\"start\": {\"line\": 1, \"character\": 0}, \"end\": {\"line\": 1, \"character\": 0}},"
                + " \"newText\": \"<!DOCTYPE config SYSTEM \\\"nogrammar.dtd\\\">\\n\"}]}]}";
        return new MessageJsonHandler(Map.of()).getGson().fromJson(json.replace("DIR", dir), WorkspaceEdit.class);
    }
}
