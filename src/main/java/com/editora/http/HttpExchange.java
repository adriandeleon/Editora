package com.editora.http;

import java.util.List;

/**
 * One executed request/response pair: the {@code label} (method + URL) and the fully <b>resolved</b> request
 * ({@code method}/{@code url}/{@code headers}/{@code requestBody}, with {@code {{vars}}} already substituted)
 * alongside its {@link HttpResult}. Produced by {@link HttpClientService} so the response viewer can show a
 * history entry, render the body by content type, and build a "Copy as cURL" command from resolved values.
 */
public record HttpExchange(
        String label, String method, String url, List<String[]> headers, String requestBody, HttpResult result) {}
