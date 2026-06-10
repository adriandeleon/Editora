package com.editora.template;

/**
 * One generated file of a multi-file template: a substitutable {@code path} (relative to the chosen
 * target folder) and its {@code body} (which may contain {@code ${variable}}/{@code ${cursor}}). Pure
 * data, deserialized by {@link TemplateRegistry}.
 */
public record TemplateFile(String path, String body) {
}
