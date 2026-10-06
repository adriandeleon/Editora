package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.scene.control.TextField;

import com.editora.search.FileResult;
import com.editora.search.LineMatch;
import com.editora.search.SearchQuery;
import com.editora.search.SearchService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A12-14: the popup searches as you type, so a search is not yet a query worth remembering — only the one a
 * result was opened from goes into the shared history.
 */
@Tag("fx")
class SearchInFilesPopupHistoryFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void onlyTheQueryAResultWasOpenedFromIsRecorded() throws Exception {
        List<String> recorded = new ArrayList<>();
        List<String> searched = new ArrayList<>();
        List<Path> opened = new ArrayList<>();
        Path file = Path.of("a.txt").toAbsolutePath();
        FxTestSupport.runOnFx(() -> {
            SearchInFilesPopup popup = new SearchInFilesPopup(new OverlayHost(), new SearchInFilesPopup.Ops() {
                @Override
                public Path defaultRoot() {
                    return null;
                }

                @Override
                public void search(
                        SearchQuery query,
                        Path root,
                        String includeGlobs,
                        String excludeGlobs,
                        Consumer<SearchService.Outcome> onResult) {
                    searched.add(query.text());
                    List<LineMatch> matches =
                            List.of(new LineMatch(3, 1, query.text().length(), "handle it"));
                    onResult.accept(new SearchService.Outcome(List.of(new FileResult(file, matches)), 1, 1, false));
                }

                @Override
                public void openMatch(Path path, int line, int col) {
                    opened.add(path);
                }

                @Override
                public void recordSearch(String query) {
                    recorded.add(query);
                }
            });
            TextField query = FxTestSupport.field(popup, "query");
            for (String typed : List.of("h", "ha", "han", "handle")) {
                query.setText(typed);
                FxTestSupport.invoke(popup, "runSearch"); // what the debounce fires after each pause
            }
            assertEquals(List.of("h", "ha", "han", "handle"), searched);
            assertEquals(List.of(), recorded, "typing alone records nothing");

            FxTestSupport.invoke(popup, "openSelected");
        });
        assertEquals(List.of(file), opened);
        assertEquals(List.of("handle"), recorded);
    }
}
