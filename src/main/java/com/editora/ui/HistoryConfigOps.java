package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.editora.config.ConfigManager;
import com.editora.config.HistoryRevision;

/**
 * The half of {@link HistoryCoordinator.Ops} that is the configuration store and nothing else: where the
 * index lives, how it is saved, and whether stored bodies may be collected now. A window supplies the rest
 * (its tool window, its tabs, its project).
 */
abstract class HistoryConfigOps implements HistoryCoordinator.Ops {
    private final ConfigManager config;

    HistoryConfigOps(ConfigManager config) {
        this.config = config;
    }

    @Override
    public Map<String, List<HistoryRevision>> historyMap() {
        return config.getHistory();
    }

    @Override
    public Map<String, Map<String, List<HistoryRevision>>> historyByProject() {
        return config.getHistoryByProject();
    }

    @Override
    public void saveHistory() {
        config.saveHistory();
    }

    @Override
    public void saveHistory(Consumer<Boolean> completion) {
        config.saveHistory(completion);
    }

    @Override
    public Path blobsDir() {
        return config.getHistoryBlobsDir();
    }

    @Override
    public boolean canCollectNow() {
        return config.shared().canCollectHistoryBlobs();
    }
}
