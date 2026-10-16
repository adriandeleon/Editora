package com.editora.ui;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

import com.editora.config.Project;
import com.editora.process.ExecTargets;
import com.editora.process.ProcessRunner;
import com.editora.vfs.EmbeddedSftpFixture;
import com.editora.vfs.Vfs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Git: Clone in a window whose project is on a remote host clones there, by the host's git, next to the
 * project: the form proposes a folder on the host, has no local folder dialog, and says where it clones.
 */
@Tag("fx")
@DisabledOnOs(OS.WINDOWS)
class GitRemoteCloneFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        org.testfx.api.FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void restoreTheDefault() {
        ExecTargets.setRemoteEnabled(null);
    }

    private static void git(Path dir, String... args) {
        List<String> argv = new java.util.ArrayList<>(List.of("git"));
        argv.addAll(List.of(args));
        ProcessRunner.Result r = ProcessRunner.run(dir, Duration.ofSeconds(30), argv);
        assertTrue(r.ok(), String.join(" ", argv) + ": " + r.message());
    }

    private static void setWindowProject(FxWindowFixture fx, Project project) throws Exception {
        Field f = MainController.class.getDeclaredField("windowProject");
        f.setAccessible(true);
        f.set(fx.controller, project);
    }

    private static Node byId(Scene scene, String id) {
        return scene.lookup("#" + id);
    }

    @Test
    void cloningInARemoteProjectsWindowClonesOnItsHost(@TempDir Path dir) throws Exception {
        ExecTargets.setRemoteEnabled(() -> true);
        try (AsyncTestScope async = new AsyncTestScope()) {
            EmbeddedSftpFixture sftp = async.own(EmbeddedSftpFixture.startWithShell(dir));
            // A repository the host can clone from, with a README the window opens afterwards.
            Path source = Files.createDirectories(sftp.serverPath("source"));
            git(source, "init", "-q", "-b", "main");
            git(source, "config", "user.email", "ana@example.com");
            git(source, "config", "user.name", "Ana");
            git(source, "config", "commit.gpgsign", "false");
            Files.writeString(source.resolve("README.md"), "# tool\n");
            git(source, "add", "README.md");
            git(source, "commit", "-q", "-m", "first");
            Path origin = sftp.serverPath("tool.git");
            git(sftp.serverPath(""), "clone", "-q", "--bare", source.toString(), origin.toString());
            Files.createDirectories(sftp.serverPath("work/app"));

            FxWindowFixture fx = async.own(FxWindowFixture.create());
            fx.shared.getSettings().setGitSupport(true);
            fx.shared.getSettings().setProjectSupport(true);
            Path project = sftp.remotePath("work/app");
            setWindowProject(fx, new Project("app", "app", Vfs.toStorableString(project)));
            GitFeatureFx w = GitFeatureFx.attach(fx);
            FxTestSupport.runOnFx(w.git::applySupport);
            Path expected = sftp.remotePath("work/tool");
            CountDownLatch cloned = w.watchStatus(tr("status.clonedInto", expected)::equals);

            FxTestSupport.runOnFx(w.git::cloneRepo);
            GitFeatureFx.await("the clone form", () -> byId(w.scene(), "clone-url") != null);
            FxTestSupport.runOnFx(() -> {
                Scene scene = w.scene();
                ((TextField) byId(scene, "clone-url")).setText(origin.toString());
                TextField folder = (TextField) byId(scene, "clone-directory");
                assertEquals(expected.toString(), folder.getText(), "beside the project, named as the host names it");
                Label onHost = (Label) byId(scene, "clone-on-host");
                assertEquals(
                        tr("dialog.clone.onHost", ExecTargets.forPath(project).id()), onHost.getText());
                assertTrue(onHost.isVisible());
                Button browse = scene.getRoot().lookupAll(".button").stream()
                        .filter(n -> n instanceof Button b
                                && tr("dialog.clone.browse").equals(b.getText()))
                        .map(Button.class::cast)
                        .findFirst()
                        .orElseThrow();
                assertFalse(browse.isVisible(), "no local folder dialog for a folder on the host");
                Button clone = scene.getRoot().lookupAll(".button").stream()
                        .filter(n -> n instanceof Button b
                                && tr("dialog.clone.button").equals(b.getText()))
                        .map(Button.class::cast)
                        .findFirst()
                        .orElseThrow();
                clone.fire();
            });
            async.await(cloned, "the clone");

            assertEquals("# tool\n", Files.readString(sftp.serverPath("work/tool/README.md")));
            GitFeatureFx.await("the README opened", () -> {
                var active = w.active();
                return active != null && expected.resolve("README.md").equals(active.getPath());
            });
        }
    }
}
