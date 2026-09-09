package com.editora.lsp;

import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonElement;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.services.LanguageServer;

/**
 * The standard language-server surface plus the JDT LS requests Editora invokes directly.
 *
 * <p>Registering these methods on the LSP4J launcher is required even though calls are issued through its
 * raw endpoint. LSP4J uses the method registry to deserialize responses; an unknown method's non-null JSON
 * result is otherwise converted to {@code null} during response dispatch.
 */
interface JdtLanguageServer extends LanguageServer {

    @JsonRequest("java/classFileContents")
    CompletableFuture<JsonElement> classFileContents(Object params);

    @JsonRequest("java/checkToStringStatus")
    CompletableFuture<JsonElement> checkToStringStatus(Object params);

    @JsonRequest("java/checkHashCodeEqualsStatus")
    CompletableFuture<JsonElement> checkHashCodeEqualsStatus(Object params);

    @JsonRequest("java/checkConstructorsStatus")
    CompletableFuture<JsonElement> checkConstructorsStatus(Object params);

    @JsonRequest("java/listOverridableMethods")
    CompletableFuture<JsonElement> listOverridableMethods(Object params);

    @JsonRequest("java/generateToString")
    CompletableFuture<JsonElement> generateToString(Object params);

    @JsonRequest("java/generateHashCodeEquals")
    CompletableFuture<JsonElement> generateHashCodeEquals(Object params);

    @JsonRequest("java/generateConstructors")
    CompletableFuture<JsonElement> generateConstructors(Object params);

    @JsonRequest("java/addOverridableMethods")
    CompletableFuture<JsonElement> addOverridableMethods(Object params);

    @JsonRequest("java/buildWorkspace")
    CompletableFuture<JsonElement> buildWorkspace(Object params);

    @JsonRequest("java/organizeImports")
    CompletableFuture<JsonElement> organizeImports(Object params);
}
