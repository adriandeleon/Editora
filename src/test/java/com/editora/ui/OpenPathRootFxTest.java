package com.editora.ui;

import java.nio.file.Path;

import javafx.scene.control.Label;

import com.editora.config.PathDisplay;
import com.editora.vfs.EmbeddedSftpFixture;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Opening a root as a file — {@code editora /}, {@code editora D:\}, or Remote > Open File on {@code sftp://host}
 * (which is the host's {@code /}). A root has no file name, and {@code openPath} used to throw on it: from the
 * command line that aborted the session restore and left an empty window. It now says it is a folder.
 */
@Tag("fx")
class OpenPathRootFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void aLocalOrRemoteRootIsReportedAsAFolderAndOpensNoTab(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            EmbeddedSftpFixture sftp = async.own(EmbeddedSftpFixture.start(dir));
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            EditorArea area = FxTestSupport.field(fx.controller, "editorArea");
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");

            for (Path root : new Path[] {dir.getRoot(), sftp.remotePath("").getRoot()}) {
                assertEquals(null, root.getFileName(), "the case under test: " + root);
                int before = FxTestSupport.callOnFx(() -> area.tabs().size());
                FxTestSupport.runOnFx(() -> workflows.openPath(root, true));
                async.awaitFx();

                assertEquals(tr("status.openIsFolder", PathDisplay.of(root)), echo(fx));
                assertEquals(
                        before, (int) FxTestSupport.callOnFx(() -> area.tabs().size()), "no tab for " + root);
            }
        }
    }

    private static String echo(FxWindowFixture fx) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            StatusBar status = FxTestSupport.field(fx.controller, "statusBar");
            return FxTestSupport.<Label>field(status, "echo").getText();
        });
    }
}
