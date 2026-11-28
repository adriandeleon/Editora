package com.editora.editor;

import com.editora.editor.EditorBuffer.MarkdownViewMode;
import com.editora.editor.EditorBuffer.MarkwhenView;
import com.editora.editor.markdown.DiagramImages;
import com.editora.editor.markdown.MermaidImages;
import com.editora.maven.PomFiles;

/**
 * Which preview a buffer's file offers: the per-format feature gates pushed from Settings (SVG, Typst, crontab,
 * fstab, systemd, SSH config, Dockerfile, GitHub Actions, Maven pom, structured data), the content checks for a
 * workflow or a pom, the pom's summary-or-XML toggle, the Markwhen view, and dropping back to the editor when a
 * preview stops being offered.
 *
 * <p>Moved out of {@code EditorBuffer} for size. The previews themselves are drawn by {@link BufferPreview}.
 */
final class BufferPreviewKinds {

    private final EditorBuffer b;

    BufferPreviewKinds(EditorBuffer b) {
        this.b = b;
    }

    /** Fired (FX thread) when the Markwhen view flips, so the controller persists it. */
    private Runnable onMarkwhenViewChanged = () -> {};
    /** Structured-data (JSON/YAML/TOML) preview: on when the feature is enabled (pushed from settings). */
    private boolean structuredPreviewEnabled;
    /** SVG image preview for .svg files: on when the feature is enabled (pushed from settings). */
    private boolean svgPreviewEnabled;
    /** Typst document preview for .typ files: on when the feature is enabled (pushed from settings). */
    private boolean typstPreviewEnabled;
    /** Crontab schedule preview for crontab files: on when the feature is enabled (pushed from settings). */
    private boolean crontabPreviewEnabled;
    /** fstab mount preview for /etc/fstab files: on when the feature is enabled (pushed from settings). */
    private boolean fstabPreviewEnabled;
    /** systemd unit preview: on when the feature is enabled (pushed from settings). */
    private boolean systemdPreviewEnabled;
    /** SSH client-config preview: on when the feature is enabled (pushed from settings). */
    private boolean sshConfigPreviewEnabled;
    /** Dockerfile stage preview: on when the feature is enabled (pushed from settings). */
    private boolean dockerfilePreviewEnabled;
    /** GitHub Actions workflow preview (content-detected YAML): on when the feature is enabled. */
    private boolean githubActionsPreviewEnabled;
    /** Maven pom.xml summary preview: on when the feature is enabled (pushed from settings). */
    private boolean pomPreviewEnabled;
    /** Sets the Markwhen preview renderer (restore path — no callback/re-render side effects here beyond
     *  a re-render when a preview is showing). */
    void setMarkwhenView(MarkwhenView view) {
        if (view != null && view != b.markwhenView) {
            b.markwhenView = view;
            if (b.markdownViewMode != MarkdownViewMode.EDITOR) {
                b.previewPanes.scheduleRenderPreview();
            }
        }
    }
    /** Flips timeline ⇄ calendar, re-renders the preview, and notifies the controller to persist. */
    void toggleMarkwhenView() {
        b.markwhenView = b.markwhenView == MarkwhenView.TIMELINE ? MarkwhenView.CALENDAR : MarkwhenView.TIMELINE;
        if (b.markdownViewMode != MarkdownViewMode.EDITOR) {
            b.previewPanes.scheduleRenderPreview();
        }
        onMarkwhenViewChanged.run();
    }
    /** Injects the persist callback fired when the Markwhen view flips. */
    void setOnMarkwhenViewChanged(Runnable callback) {
        this.onMarkwhenViewChanged = callback == null ? () -> {} : callback;
    }
    /**
     * Leaves the preview when — and only when — this buffer has no preview left. Called after any
     * {@code set*PreviewEnabled} flip.
     *
     * <p>Each setter used to ask "is this file my format?" instead, which is wrong both ways. One setting can
     * back several formats (an XML file's DOM tree rides {@code structuredPreview}, but {@code isStructured()}
     * is false for XML) — so the buffer was left stranded in a preview whose feature is off: every branch of
     * {@link BufferPreview#scheduleRenderPreview} then misses and falls through to the Markdown tail, rendering the source
     * as Markdown, while {@code hasPreview()} being false takes away the toggle needed to escape. And one file
     * can have two previews (a workflow is also YAML) — so turning off the unrelated one clobbered a
     * per-file view mode that is persisted in {@code WorkspaceState.markdownViewModes}.
     */
    private void reconcilePreviewMode() {
        if (!hasPreview() && b.markdownViewMode != MarkdownViewMode.EDITOR) {
            b.setMarkdownViewMode(MarkdownViewMode.EDITOR);
        }
    }
    /** Whether this buffer supports the 3-mode preview: Markdown always, Mermaid only while the feature
     *  is enabled (so a .mmd file is plain text with no preview affordance when Mermaid is off), and CSV
     *  only once the grid preview node has been injected (the feature-on gate — see {@link EditorBuffer#hasCsvPreview}). */
    boolean hasPreview() {
        return b.isMarkdown()
                || b.isMarkwhen()
                || (b.isDiagram() && MermaidImages.isEnabled() && !b.encryption.isEncrypted()) // renders via temp files
                || (b.isRenderedDiagram() && DiagramImages.isEnabled() && !b.encryption.isEncrypted())
                || b.hasCsvPreview()
                || b.hasHttpPreview()
                || hasStructuredPreview()
                || hasPomPreview()
                || hasXmlPreview()
                || hasSvgPreview()
                || hasCrontabPreview()
                || hasFstabPreview()
                || hasSystemdPreview()
                || hasSshConfigPreview()
                || hasDockerfilePreview()
                || hasGithubActionsPreview()
                || (hasTypstPreview() && !b.encryption.isEncrypted());
    }
    /** Whether the SVG image preview should be offered (feature on + a .svg buffer + not a huge file). */
    boolean hasSvgPreview() {
        return svgPreviewEnabled && b.isSvg() && !b.hugeFile;
    }
    /** Pushes the SVG-preview feature gate (from Settings); drops back to source when turned off. */
    void setSvgPreviewEnabled(boolean enabled) {
        if (this.svgPreviewEnabled == enabled) {
            return;
        }
        this.svgPreviewEnabled = enabled;
        reconcilePreviewMode();
    }
    /** Whether the Typst document preview should be offered (feature on + a .typ buffer + not a huge file). */
    boolean hasTypstPreview() {
        return typstPreviewEnabled && b.isTypst() && !b.hugeFile;
    }
    /** Pushes the Typst-preview feature gate (from Settings); drops back to source when turned off. */
    void setTypstPreviewEnabled(boolean enabled) {
        if (this.typstPreviewEnabled == enabled) {
            return;
        }
        this.typstPreviewEnabled = enabled;
        reconcilePreviewMode();
    }
    /** Whether the structured tree/OpenAPI preview should be offered (feature on, and not a huge file). */
    boolean hasStructuredPreview() {
        return structuredPreviewEnabled && b.isStructured() && !b.hugeFile;
    }
    /** Whether the XML DOM-tree preview should be offered (structured-preview feature on, XML, not huge). */
    boolean hasXmlPreview() {
        return structuredPreviewEnabled && b.isXml() && !b.hugeFile;
    }
    /** Whether the crontab schedule preview should be offered (feature on + a crontab buffer + not huge). */
    boolean hasCrontabPreview() {
        return crontabPreviewEnabled && b.isCrontab() && !b.hugeFile;
    }
    /** Pushes the crontab-preview feature gate (from Settings); drops back to source when turned off. */
    void setCrontabPreviewEnabled(boolean enabled) {
        if (this.crontabPreviewEnabled == enabled) {
            return;
        }
        this.crontabPreviewEnabled = enabled;
        reconcilePreviewMode();
    }
    /** Whether the fstab mount preview should be offered (feature on + an fstab buffer + not huge). */
    boolean hasFstabPreview() {
        return fstabPreviewEnabled && b.isFstab() && !b.hugeFile;
    }
    /** Pushes the fstab-preview feature gate (from Settings); drops back to source when turned off. */
    void setFstabPreviewEnabled(boolean enabled) {
        if (this.fstabPreviewEnabled == enabled) {
            return;
        }
        this.fstabPreviewEnabled = enabled;
        reconcilePreviewMode();
    }
    /** Whether the systemd unit preview should be offered (feature on + a systemd buffer + not huge). */
    boolean hasSystemdPreview() {
        return systemdPreviewEnabled && b.isSystemd() && !b.hugeFile;
    }
    /** Pushes the systemd-preview feature gate (from Settings); drops back to source when turned off. */
    void setSystemdPreviewEnabled(boolean enabled) {
        if (this.systemdPreviewEnabled == enabled) {
            return;
        }
        this.systemdPreviewEnabled = enabled;
        reconcilePreviewMode();
    }
    /** Whether the SSH-config preview should be offered (feature on + an ssh-config buffer + not huge). */
    boolean hasSshConfigPreview() {
        return sshConfigPreviewEnabled && b.isSshConfig() && !b.hugeFile;
    }
    /** Pushes the ssh-config-preview feature gate (from Settings); drops back to source when turned off. */
    void setSshConfigPreviewEnabled(boolean enabled) {
        if (this.sshConfigPreviewEnabled == enabled) {
            return;
        }
        this.sshConfigPreviewEnabled = enabled;
        reconcilePreviewMode();
    }
    /** Whether the Dockerfile stage preview should be offered (feature on + a Dockerfile buffer + not huge). */
    boolean hasDockerfilePreview() {
        return dockerfilePreviewEnabled && b.isDockerfile() && !b.hugeFile;
    }
    /** Pushes the Dockerfile-preview feature gate (from Settings); drops back to source when turned off. */
    void setDockerfilePreviewEnabled(boolean enabled) {
        if (this.dockerfilePreviewEnabled == enabled) {
            return;
        }
        this.dockerfilePreviewEnabled = enabled;
        reconcilePreviewMode();
    }
    /** A GitHub Actions workflow buffer — a YAML file whose content matches the workflow signature (on+jobs).
     *  Detected by content (not path/extension) so it works anywhere; reads only a bounded head of the file. */
    boolean isGithubActions() {
        if (!"yaml".equals(b.language)) {
            return false;
        }
        int len = b.area.getLength();
        String head = b.area.getText(0, Math.min(len, 65536));
        return com.editora.ghactions.GithubActions.looksLikeWorkflow(head);
    }
    /** Whether the GitHub Actions workflow preview should be offered (feature on + a workflow + not huge). */
    boolean hasGithubActionsPreview() {
        return githubActionsPreviewEnabled && !b.hugeFile && isGithubActions();
    }
    /** Pushes the GitHub-Actions-preview feature gate (from Settings); drops back to the YAML tree/source when off. */
    void setGithubActionsPreviewEnabled(boolean enabled) {
        if (this.githubActionsPreviewEnabled == enabled) {
            return;
        }
        this.githubActionsPreviewEnabled = enabled;
        reconcilePreviewMode();
    }
    /**
     * A Maven pom.xml — an XML buffer named {@code pom.xml}/{@code *.pom}, or any other XML file carrying a
     * {@code <project>} root with a {@code <modelVersion>} (so a {@code pom-template.xml} or a generated
     * {@code effective-pom.xml} is recognized too). Reads only a bounded head, like {@link #isGithubActions()}.
     */
    boolean isPom() {
        if (!b.isXml()) {
            return false;
        }
        String fileName = b.path != null ? b.path.getFileName().toString() : b.displayName;
        return PomFiles.isPom(fileName, b.area.getText(0, Math.min(b.area.getLength(), PomFiles.SNIFF_CHARS)));
    }
    /**
     * Whether the pom summary is the preview to render — the feature is on, this is a pom, and the user
     * hasn't switched this buffer to the generic XML tree. Independent of {@link #hasXmlPreview()}'s
     * structured-preview gate, so turning the XML tree off still leaves a pom its own preview.
     */
    boolean hasPomPreview() {
        return pomPreviewEnabled && !b.hugeFile && !b.pomShowXml && isPom();
    }
    /** Pushes the pom-preview feature gate (from Settings); drops back to the XML tree/source when off. */
    void setPomPreviewEnabled(boolean enabled) {
        if (this.pomPreviewEnabled == enabled) {
            return;
        }
        this.pomPreviewEnabled = enabled;
        reconcilePreviewMode();
    }
    /**
     * Flips a pom between its summary and the standard XML tree, then re-renders. Refuses the XML direction
     * when the structured-data preview (which owns the XML tree) is switched off, rather than flipping into a
     * view that isn't there and stranding the buffer back in the editor.
     *
     * @return false when the flip was refused for that reason
     */
    boolean togglePomView() {
        if (!b.pomShowXml && !structuredPreviewEnabled) {
            return false;
        }
        b.pomShowXml = !b.pomShowXml;
        if (b.markdownViewMode != MarkdownViewMode.EDITOR) {
            b.previewPanes.scheduleRenderPreview();
        }
        return true;
    }
    /** Whether this buffer uses the self-scrolling tree host (structured, XML, crontab, or fstab) — shared surface. */
    boolean hasTreePreview() {
        return hasStructuredPreview()
                || hasPomPreview()
                || hasXmlPreview()
                || hasCrontabPreview()
                || hasFstabPreview()
                || hasSystemdPreview()
                || hasSshConfigPreview()
                || hasDockerfilePreview()
                || hasGithubActionsPreview();
    }
    /** Pushes the structured-preview feature gate (from Settings); re-attaches the toggle if it flipped. */
    void setStructuredPreviewEnabled(boolean enabled) {
        if (this.structuredPreviewEnabled == enabled) {
            return;
        }
        this.structuredPreviewEnabled = enabled;
        reconcilePreviewMode();
    }
}
