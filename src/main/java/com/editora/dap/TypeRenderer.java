package com.editora.dap;

/**
 * A type renderer (IntelliJ's Java Type Renderers, Visual Studio's natvis): how the debugger shows a value of one type
 * — its value as an expression of the value ({@code this.amount + " " + this.currency}) and, optionally, its children
 * as named expressions in place of its fields. Kept in Settings ({@code debugTypeRenderers}), a bean for Jackson.
 *
 * <p>{@code this} (or {@code self}) in an expression stands for the value; Editora puts the value's own expression in
 * its place and evaluates the result in the stopped frame.
 */
public class TypeRenderer {

    private boolean enabled = true;
    /** The debug language it applies to ({@code java}, {@code python}…; {@code ""}: every language). */
    private String language = "";
    /** The type, qualified or not ({@code com.shop.Money}, {@code Money}). */
    private String type = "";
    /** The value shown: an expression of {@code this}; {@code ""} keeps the debugger's own. */
    private String value = "";
    /** The children shown, one {@code name = expression} a line; {@code ""} keeps the debugger's own. */
    private String children = "";

    public TypeRenderer() {}

    public TypeRenderer(boolean enabled, String language, String type, String value, String children) {
        this.enabled = enabled;
        this.language = language;
        this.type = type;
        this.value = value;
        this.children = children;
    }

    /** A copy, for an editor that changes its own until it saves. */
    public TypeRenderer copy() {
        return new TypeRenderer(enabled, language, type, value, children);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getLanguage() {
        return language == null ? "" : language;
    }

    public void setLanguage(String language) {
        this.language = language == null ? "" : language;
    }

    public String getType() {
        return type == null ? "" : type;
    }

    public void setType(String type) {
        this.type = type == null ? "" : type;
    }

    public String getValue() {
        return value == null ? "" : value;
    }

    public void setValue(String value) {
        this.value = value == null ? "" : value;
    }

    public String getChildren() {
        return children == null ? "" : children;
    }

    public void setChildren(String children) {
        this.children = children == null ? "" : children;
    }
}
