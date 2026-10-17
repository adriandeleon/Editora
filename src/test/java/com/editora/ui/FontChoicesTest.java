package com.editora.ui;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FontChoicesTest {

    @Test
    void weightVariantsOfAListedFamilyAndSymbolFontsAreLeftOut() {
        List<String> system = List.of(
                "Cascadia Code",
                "DejaVu Sans Mono",
                "IBM Plex Mono ExtLt",
                "IBM Plex Mono Text",
                "IBM Plex Mono SmBld",
                "JetBrainsMono NF",
                "JetBrainsMono NF ExtraBold",
                "JetBrainsMono NF Thin",
                "JetBrainsMono NFM",
                "JetBrainsMono NFM Light",
                "MathJax_Typewriter",
                "esint10",
                "Liberation Mono",
                "Mono Light");
        assertEquals(
                List.of(
                        "JetBrains Mono",
                        "Cascadia Code",
                        "IBM Plex Mono",
                        "DejaVu Sans Mono",
                        "JetBrainsMono NF",
                        "JetBrainsMono NFM",
                        "Liberation Mono",
                        "Mono Light"),
                FontChoices.choices(List.of("JetBrains Mono", "Cascadia Code", "IBM Plex Mono"), system),
                "bundled first; a weight of a family not listed (Mono Light) stays");
    }
}
