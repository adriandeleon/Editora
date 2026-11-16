package com.editora.ui;

import java.util.List;
import java.util.function.Consumer;

import com.editora.build.OutputStyle;
import com.editora.git.GitOutputDiffs;
import com.editora.process.CommandLog;
import com.editora.run.RuntimeVersion;
import com.editora.run.StackTraceLinks;
import org.jspecify.annotations.Nullable;

/**
 * A window's shared Output console, built on first need: when its tool window first opens, or when something
 * first asks it for a tab ({@link #started}, {@link #logCommand}, {@link #commandStarted}), whichever comes
 * first. A window in which nothing has run and Output has never been opened holds no {@link BuildOutputPanel}.
 *
 * <p>Every writer goes through this holder, so nothing written while the tool window is closed is lost: the
 * first write that needs a tab builds the panel, and the tool window shows that same panel when it opens. What
 * the window sets before then (the font, the link handlers) is kept here and handed to the panel as it is
 * built. A call that only reads, or that writes to a tab the panel would not have ({@link #appendOutput} before
 * any {@link #started}), builds nothing. FX thread only, like the panel.
 */
final class BuildOutput {

    private @Nullable BuildOutputPanel panel;

    private @Nullable String fontFamily;
    private int fontSize;
    private @Nullable Consumer<StackTraceLinks.Link> onLink;
    private @Nullable Consumer<String> onUrl;
    private @Nullable Consumer<GitOutputDiffs.Target> onGitDiff;

    /** The panel, built now if this is its first use: the tool window's content supplier, and every tab maker. */
    BuildOutputPanel panel() {
        BuildOutputPanel p = panel;
        if (p == null) {
            p = new BuildOutputPanel();
            if (onLink != null) {
                p.setOnLink(onLink);
            }
            if (onUrl != null) {
                p.setOnUrl(onUrl);
            }
            if (onGitDiff != null) {
                p.setOnGitDiff(onGitDiff);
            }
            if (fontFamily != null) {
                p.setOutputFont(fontFamily, fontSize);
            }
            panel = p;
        }
        return p;
    }

    /** The panel if something has built it, else null: for a caller that must not be what builds it. */
    @Nullable
    BuildOutputPanel panelIfBuilt() {
        return panel;
    }

    void setOnLink(Consumer<StackTraceLinks.Link> onLink) {
        this.onLink = onLink;
        if (panel != null) {
            panel.setOnLink(onLink);
        }
    }

    void setOnUrl(Consumer<String> onUrl) {
        this.onUrl = onUrl;
        if (panel != null) {
            panel.setOnUrl(onUrl);
        }
    }

    void setOnGitDiff(Consumer<GitOutputDiffs.Target> onGitDiff) {
        this.onGitDiff = onGitDiff;
        if (panel != null) {
            panel.setOnGitDiff(onGitDiff);
        }
    }

    void setOutputFont(String family, int size) {
        this.fontFamily = family;
        this.fontSize = size;
        if (panel != null) {
            panel.setOutputFont(family, size);
        }
    }

    // --- writers that make a tab: these build the panel ---

    void started(Object owner, String toolName, String header, OutputStyle style, Runnable onStop) {
        panel().started(owner, toolName, header, style, onStop);
    }

    void logCommand(Object owner, String tabTitle, CommandLog.Entry entry) {
        panel().logCommand(owner, tabTitle, entry);
    }

    void commandStarted(Object owner, String tabTitle, List<String> argv, Runnable onStop) {
        panel().commandStarted(owner, tabTitle, argv, onStop);
    }

    // --- writers to a tab that exists: without a panel there is no tab, and the panel itself drops these ---

    void commandProgress(Object owner, String line, boolean transientLine) {
        if (panel != null) {
            panel.commandProgress(owner, line, transientLine);
        }
    }

    void appendOutput(Object owner, String line, boolean stderr) {
        if (panel != null) {
            panel.appendOutput(owner, line, stderr);
        }
    }

    void setRuntime(Object owner, @Nullable RuntimeVersion version) {
        if (panel != null) {
            panel.setRuntime(owner, version);
        }
    }

    void finished(Object owner, int code) {
        if (panel != null) {
            panel.finished(owner, code);
        }
    }

    void stopped(Object owner) {
        if (panel != null) {
            panel.stopped(owner);
        }
    }

    void failed(Object owner, String message) {
        if (panel != null) {
            panel.failed(owner, message);
        }
    }

    // --- readers ---

    void selectTab(Object owner) {
        if (panel != null) {
            panel.selectTab(owner);
        }
    }

    boolean hasTabs() {
        return panel != null && panel.hasTabs();
    }

    boolean findInShownOutput() {
        return panel != null && panel.findInShownOutput();
    }

    boolean saveShownOutput() {
        return panel != null && panel.saveShownOutput();
    }

    boolean toggleShownPreviousRun() {
        return panel != null && panel.toggleShownPreviousRun();
    }
}
