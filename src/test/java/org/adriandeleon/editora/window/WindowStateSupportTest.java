package org.adriandeleon.editora.window;

import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowStateSupportTest {

    @Test
    void keepsSavedPositionWhenItIntersectsAScreen() {
        WindowStateSupport.Position position = WindowStateSupport.resolveVisiblePosition(
                1440,
                920,
                2078,
                158,
                List.of(
                        new Rectangle2D(0, 0, 1728, 1117),
                        new Rectangle2D(1728, 0, 1920, 1080)
                )
        );

        assertEquals(2078, position.x());
        assertEquals(158, position.y());
    }

    @Test
    void recentersWhenSavedPositionIsMissing() {
        WindowStateSupport.Position position = WindowStateSupport.resolveVisiblePosition(
                1440,
                920,
                Double.NaN,
                Double.NaN,
                List.of(new Rectangle2D(0, 0, 1728, 1117))
        );

        assertEquals(144, position.x());
        assertEquals(98.5d, position.y());
    }

    @Test
    void recentersWhenSavedPositionIsOffScreen() {
        WindowStateSupport.Position position = WindowStateSupport.resolveVisiblePosition(
                1440,
                920,
                5000,
                5000,
                List.of(new Rectangle2D(0, 0, 1728, 1117))
        );

        assertEquals(144, position.x());
        assertEquals(98.5d, position.y());
    }

    @Test
    void visibilityCheckRequiresIntersectionWithAnyScreen() {
        List<Rectangle2D> screens = List.of(
                new Rectangle2D(0, 0, 1728, 1117),
                new Rectangle2D(1728, 0, 1920, 1080)
        );

        assertTrue(WindowStateSupport.isVisible(2078, 158, 1440, 920, screens));
        assertFalse(WindowStateSupport.isVisible(5000, 5000, 1440, 920, screens));
    }
}

