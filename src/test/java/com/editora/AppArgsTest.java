package com.editora;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Tests parsing the --config-dir program argument (pure; no JavaFX launch). */
class AppArgsTest {

    @Test
    void equalsForm() {
        assertEquals("/opt/cfg", App.configDirArg(List.of("--config-dir=/opt/cfg")));
    }

    @Test
    void spaceSeparatedForm() {
        assertEquals("/opt/cfg", App.configDirArg(List.of("--config-dir", "/opt/cfg")));
    }

    @Test
    void trimsValue() {
        assertEquals("/opt/cfg", App.configDirArg(List.of("--config-dir=  /opt/cfg  ")));
    }

    @Test
    void absentOrEmptyYieldsNull() {
        assertNull(App.configDirArg(List.of()));
        assertNull(App.configDirArg(List.of("--other", "x")));
        assertNull(App.configDirArg(List.of("--config-dir=")));
        assertNull(App.configDirArg(List.of("--config-dir"))); // no following value
    }
}
