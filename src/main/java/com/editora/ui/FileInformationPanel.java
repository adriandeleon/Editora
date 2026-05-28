package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.editora.editor.EditorBuffer;
import com.editora.editor.LanguageRegistry;

import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Tool window content showing live information about the active editor: file metadata,
 * text settings, counts, caret position, and the character under the caret.
 */
public class FileInformationPanel extends VBox {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("d MMM uuuu 'at' h:mm a", Locale.getDefault());
    private static final Pattern WORD = Pattern.compile("\\p{L}[\\p{L}\\p{N}_]*");

    private final Label createdValue = value();
    private final Label modifiedValue = value();
    private final Label sizeValue = value();
    private final Label tagsValue = value();
    private final Label permissionsValue = value();
    private final Label ownerValue = value();
    private final Label fullPathValue = value();

    private final Label encodingValue = value();
    private final Label lineEndingsValue = value();
    private final Label modeValue = value();

    private final Label linesValue = value();
    private final Label charsValue = value();
    private final Label wordsValue = value();
    private final Label locationValue = value();
    private final Label lineValue = value();
    private final Label columnValue = value();

    private final Label codePointValue = value();
    private final Label charNameValue = value();
    private final Label charBlockValue = value();
    private final Label charCategoryValue = value();

    private EditorBuffer attached;
    private final ChangeListener<Number> caretListener = (o, w, n) -> refresh();
    private final ChangeListener<String> textListener = (o, w, n) -> refresh();

    public FileInformationPanel() {
        getStyleClass().add("file-info-panel");
        setSpacing(6);
        buildUI();
        refresh();
    }

    public void attach(EditorBuffer buffer) {
        if (attached != null) {
            attached.getArea().caretPositionProperty().removeListener(caretListener);
            attached.getArea().textProperty().removeListener(textListener);
        }
        attached = buffer;
        if (buffer != null) {
            buffer.getArea().caretPositionProperty().addListener(caretListener);
            buffer.getArea().textProperty().addListener(textListener);
        }
        refresh();
    }

    // --- UI construction ---

    private void buildUI() {
        // Inner segmented header: file / outline / warnings + info button.
        ToggleGroup group = new ToggleGroup();
        ToggleButton fileTab = headerToggle(group, Icons.fileSheet(), "File info", true);
        ToggleButton outlineTab = headerToggle(group, Icons.outline(), "Outline (coming soon)", false);
        ToggleButton warningsTab = headerToggle(group, Icons.warning(), "Warnings (coming soon)", false);
        HBox tabs = new HBox(fileTab, outlineTab, warningsTab);
        tabs.getStyleClass().add("file-info-tabs");

        Button info = new Button();
        info.setGraphic(Icons.about());
        info.getStyleClass().addAll("button-icon", "flat", "file-info-aux");
        info.setTooltip(new Tooltip("File information"));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, tabs, spacer, info);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("file-info-header");

        // Content stack — only "File" view is implemented; others are placeholders.
        Region fileView = buildFileView();
        Region outlinePlaceholder = comingSoon("Outline view coming soon");
        Region warningsPlaceholder = comingSoon("Warnings view coming soon");

        StackPane content = new StackPane(fileView, outlinePlaceholder, warningsPlaceholder);
        content.setAlignment(Pos.TOP_CENTER);
        fileView.setVisible(true);
        outlinePlaceholder.setVisible(false);
        warningsPlaceholder.setVisible(false);

        fileTab.selectedProperty().addListener((o, w, n) -> {
            fileView.setVisible(n);
            fileView.setManaged(n);
        });
        outlineTab.selectedProperty().addListener((o, w, n) -> {
            outlinePlaceholder.setVisible(n);
            outlinePlaceholder.setManaged(n);
        });
        warningsTab.selectedProperty().addListener((o, w, n) -> {
            warningsPlaceholder.setVisible(n);
            warningsPlaceholder.setManaged(n);
        });
        outlinePlaceholder.setManaged(false);
        warningsPlaceholder.setManaged(false);

        getChildren().addAll(header, content);
        VBox.setVgrow(content, Priority.ALWAYS);
    }

    private ToggleButton headerToggle(ToggleGroup group, Node icon, String tip, boolean selected) {
        ToggleButton btn = new ToggleButton();
        btn.setGraphic(icon);
        btn.setToggleGroup(group);
        btn.setSelected(selected);
        btn.getStyleClass().add("file-info-tab");
        btn.setTooltip(new Tooltip(tip));
        // Prevent the user from un-selecting the active toggle (keep one always selected).
        btn.setOnAction(e -> {
            if (!btn.isSelected()) {
                btn.setSelected(true);
            }
        });
        return btn;
    }

    private Region buildFileView() {
        VBox box = new VBox(10,
                section("File", buildFileGrid()),
                section("Text Settings", buildTextSettingsGrid()),
                section("Count", buildCountBox()),
                section("Character", buildCharacterGrid()));
        box.setPadding(new Insets(8));
        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("file-info-scroll");
        return scroll;
    }

    private TitledPane section(String title, Region content) {
        TitledPane pane = new TitledPane(title, content);
        pane.getStyleClass().add("file-info-section");
        pane.setExpanded(true);
        return pane;
    }

    private GridPane buildFileGrid() {
        GridPane g = grid();
        int r = 0;
        addRow(g, r++, "Created", createdValue);
        addRow(g, r++, "Modified", modifiedValue);
        addRow(g, r++, "Size", sizeValue);
        addRow(g, r++, "Tags", tagsValue);
        addRow(g, r++, "Permissions", permissionsValue);
        addRow(g, r++, "Owner", ownerValue);
        addRow(g, r, "Full Path", fullPathValue);
        fullPathValue.setWrapText(true);
        return g;
    }

    private GridPane buildTextSettingsGrid() {
        GridPane g = grid();
        addRow(g, 0, "Encoding", encodingValue);
        addRow(g, 1, "Line Endings", lineEndingsValue);
        addRow(g, 2, "Mode", modeValue);
        return g;
    }

    private VBox buildCountBox() {
        GridPane totals = grid();
        addRow(totals, 0, "Lines", linesValue);
        addRow(totals, 1, "Characters", charsValue);
        addRow(totals, 2, "Words", wordsValue);

        GridPane caret = grid();
        addRow(caret, 0, "Location", locationValue);
        addRow(caret, 1, "Line", lineValue);
        addRow(caret, 2, "Column", columnValue);

        VBox box = new VBox(10, totals, new Separator(), caret);
        return box;
    }

    private GridPane buildCharacterGrid() {
        GridPane g = grid();
        addRow(g, 0, "Code Point", codePointValue);
        addRow(g, 1, "Name", charNameValue);
        addRow(g, 2, "Block", charBlockValue);
        addRow(g, 3, "Category", charCategoryValue);
        return g;
    }

    private GridPane grid() {
        GridPane g = new GridPane();
        g.getStyleClass().add("file-info-grid");
        g.setHgap(12);
        g.setVgap(6);
        GridPane.setHgrow(new Region(), Priority.ALWAYS);
        return g;
    }

    private void addRow(GridPane g, int row, String key, Label valueLabel) {
        Label k = new Label(key);
        k.getStyleClass().add("file-info-key");
        valueLabel.getStyleClass().add("file-info-value");
        valueLabel.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(valueLabel, Priority.ALWAYS);
        GridPane.setHalignment(valueLabel, HPos.RIGHT);
        g.add(k, 0, row);
        g.add(valueLabel, 1, row);
    }

    private Label value() {
        Label l = new Label("–");
        l.setMaxWidth(Double.MAX_VALUE);
        return l;
    }

    private Region comingSoon(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("tool-window-placeholder");
        StackPane sp = new StackPane(l);
        sp.setAlignment(Pos.CENTER);
        return sp;
    }

    // --- Live refresh ---

    private void refresh() {
        if (attached == null) {
            clearAll();
            return;
        }
        refreshFile(attached.getPath());
        refreshTextSettings(attached);
        refreshCounts(attached);
        refreshCharacter(attached);
    }

    private void clearAll() {
        for (Label l : new Label[]{
                createdValue, modifiedValue, sizeValue, tagsValue,
                permissionsValue, ownerValue, fullPathValue,
                encodingValue, lineEndingsValue, modeValue,
                linesValue, charsValue, wordsValue,
                locationValue, lineValue, columnValue,
                codePointValue, charNameValue, charBlockValue, charCategoryValue
        }) {
            l.setText("–");
        }
    }

    private void refreshFile(Path path) {
        tagsValue.setText("–");
        if (path == null) {
            createdValue.setText("–");
            modifiedValue.setText("–");
            sizeValue.setText("–");
            permissionsValue.setText("–");
            ownerValue.setText("–");
            fullPathValue.setText("(untitled)");
            return;
        }
        fullPathValue.setText(path.toString());
        try {
            BasicFileAttributes basic = Files.readAttributes(path, BasicFileAttributes.class);
            createdValue.setText(formatTime(basic.creationTime().toInstant()));
            modifiedValue.setText(formatTime(basic.lastModifiedTime().toInstant()));
            sizeValue.setText(formatSize(basic.size()));
        } catch (IOException e) {
            createdValue.setText("–");
            modifiedValue.setText("–");
            sizeValue.setText("–");
        }
        try {
            PosixFileAttributes posix = Files.readAttributes(path, PosixFileAttributes.class);
            permissionsValue.setText(formatPermissions(posix.permissions()));
            ownerValue.setText(posix.owner().getName());
        } catch (IOException | UnsupportedOperationException e) {
            permissionsValue.setText("–");
            ownerValue.setText("–");
        }
    }

    private void refreshTextSettings(EditorBuffer buffer) {
        encodingValue.setText("Unicode (UTF-8)");
        String text = buffer.getArea().getText();
        lineEndingsValue.setText(text.contains("\r\n") ? "CRLF" : "LF");
        modeValue.setText(modeLabel(buffer));
    }

    private static String modeLabel(EditorBuffer buffer) {
        Path p = buffer.getPath();
        if (p == null) {
            return "General";
        }
        String lang = LanguageRegistry.forFileName(p.getFileName().toString()).name();
        return lang.substring(0, 1).toUpperCase(Locale.ROOT) + lang.substring(1);
    }

    private void refreshCounts(EditorBuffer buffer) {
        var area = buffer.getArea();
        String text = area.getText();
        int lineCount = area.getParagraphs().size();
        int charCount = text.length();
        int wordCount = countWords(text);

        int selStartPara = area.getParagraphs().size() == 0 ? 0 : 0;
        int selectedLines;
        int selectedChars = area.getSelection().getLength();
        if (selectedChars > 0) {
            String selText = area.getSelectedText();
            selectedLines = (int) selText.lines().count();
            if (selText.endsWith("\n")) {
                selectedLines = Math.max(1, selectedLines);
            }
        } else {
            selectedLines = 0;
        }

        linesValue.setText(selectedLines > 0 ? formatNum(lineCount) + " (" + selectedLines + ")"
                : formatNum(lineCount));
        charsValue.setText(selectedChars > 0 ? formatNum(charCount) + " (" + selectedChars + ")"
                : formatNum(charCount));
        wordsValue.setText(formatNum(wordCount));

        locationValue.setText(formatNum(area.getCaretPosition()));
        lineValue.setText(formatNum(area.getCurrentParagraph() + 1));
        columnValue.setText(formatNum(area.getCaretColumn() + 1));
        // selStartPara unused — placeholder for future enhancement.
        if (selStartPara < 0) {
            // no-op
        }
    }

    private void refreshCharacter(EditorBuffer buffer) {
        var area = buffer.getArea();
        String text = area.getText();
        int caret = area.getCaretPosition();
        if (caret < 0 || caret >= text.length()) {
            codePointValue.setText("–");
            charNameValue.setText("–");
            charBlockValue.setText("–");
            charCategoryValue.setText("–");
            return;
        }
        int codePoint = text.codePointAt(caret);
        codePointValue.setText(String.format("U+%04X", codePoint));
        String name = Character.getName(codePoint);
        charNameValue.setText(name == null ? "–" : name);
        Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
        charBlockValue.setText(block == null ? "–" : friendlyBlockName(block));
        charCategoryValue.setText(categoryName(Character.getType(codePoint)));
    }

    // --- Formatting helpers ---

    private static String formatTime(java.time.Instant instant) {
        return DATE_FORMAT.format(instant.atZone(ZoneId.systemDefault()));
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " bytes";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%,d kB (%,d bytes)", bytes / 1024, bytes);
        }
        return String.format("%.1f MB (%,d bytes)", bytes / (1024.0 * 1024.0), bytes);
    }

    private static String formatNum(long n) {
        return String.format("%,d", n);
    }

    private static String formatPermissions(Set<PosixFilePermission> perms) {
        int octal = 0;
        if (perms.contains(PosixFilePermission.OWNER_READ))     octal |= 0400;
        if (perms.contains(PosixFilePermission.OWNER_WRITE))    octal |= 0200;
        if (perms.contains(PosixFilePermission.OWNER_EXECUTE))  octal |= 0100;
        if (perms.contains(PosixFilePermission.GROUP_READ))     octal |= 040;
        if (perms.contains(PosixFilePermission.GROUP_WRITE))    octal |= 020;
        if (perms.contains(PosixFilePermission.GROUP_EXECUTE))  octal |= 010;
        if (perms.contains(PosixFilePermission.OTHERS_READ))    octal |= 04;
        if (perms.contains(PosixFilePermission.OTHERS_WRITE))   octal |= 02;
        if (perms.contains(PosixFilePermission.OTHERS_EXECUTE)) octal |= 01;
        return String.format("%o (-%s)", octal, PosixFilePermissions.toString(perms));
    }

    private static int countWords(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        var matcher = WORD.matcher(text);
        int n = 0;
        while (matcher.find()) {
            n++;
        }
        return n;
    }

    private static String friendlyBlockName(Character.UnicodeBlock block) {
        String raw = block.toString();
        StringBuilder out = new StringBuilder(raw.length());
        boolean nextUpper = true;
        for (char c : raw.toCharArray()) {
            if (c == '_') {
                out.append(' ');
                nextUpper = true;
            } else if (nextUpper) {
                out.append(c);
                nextUpper = false;
            } else {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }

    private static String categoryName(int type) {
        return switch (type) {
            case Character.UPPERCASE_LETTER -> "Uppercase Letter (Lu)";
            case Character.LOWERCASE_LETTER -> "Lowercase Letter (Ll)";
            case Character.TITLECASE_LETTER -> "Titlecase Letter (Lt)";
            case Character.MODIFIER_LETTER -> "Modifier Letter (Lm)";
            case Character.OTHER_LETTER -> "Other Letter (Lo)";
            case Character.NON_SPACING_MARK -> "Nonspacing Mark (Mn)";
            case Character.COMBINING_SPACING_MARK -> "Spacing Mark (Mc)";
            case Character.ENCLOSING_MARK -> "Enclosing Mark (Me)";
            case Character.DECIMAL_DIGIT_NUMBER -> "Decimal Digit Number (Nd)";
            case Character.LETTER_NUMBER -> "Letter Number (Nl)";
            case Character.OTHER_NUMBER -> "Other Number (No)";
            case Character.SPACE_SEPARATOR -> "Space Separator (Zs)";
            case Character.LINE_SEPARATOR -> "Line Separator (Zl)";
            case Character.PARAGRAPH_SEPARATOR -> "Paragraph Separator (Zp)";
            case Character.CONTROL -> "Control (Cc)";
            case Character.FORMAT -> "Format (Cf)";
            case Character.SURROGATE -> "Surrogate (Cs)";
            case Character.PRIVATE_USE -> "Private Use (Co)";
            case Character.UNASSIGNED -> "Unassigned (Cn)";
            case Character.DASH_PUNCTUATION -> "Dash Punctuation (Pd)";
            case Character.START_PUNCTUATION -> "Open Punctuation (Ps)";
            case Character.END_PUNCTUATION -> "Close Punctuation (Pe)";
            case Character.CONNECTOR_PUNCTUATION -> "Connector Punctuation (Pc)";
            case Character.OTHER_PUNCTUATION -> "Other Punctuation (Po)";
            case Character.INITIAL_QUOTE_PUNCTUATION -> "Initial Quote (Pi)";
            case Character.FINAL_QUOTE_PUNCTUATION -> "Final Quote (Pf)";
            case Character.MATH_SYMBOL -> "Math Symbol (Sm)";
            case Character.CURRENCY_SYMBOL -> "Currency Symbol (Sc)";
            case Character.MODIFIER_SYMBOL -> "Modifier Symbol (Sk)";
            case Character.OTHER_SYMBOL -> "Other Symbol (So)";
            default -> "Unknown";
        };
    }
}
