package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import com.editora.agent.runtime.AgentCancellation;
import com.editora.agent.runtime.AgentDocuments;
import com.editora.agent.runtime.AgentWorkspace;
import com.editora.io.AtomicFileWrite;
import com.editora.io.DelegatingFileOperations;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@Tag("fx")
class AgentDocumentHostFxTest {
    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @TempDir
    Path dir;

    private WindowAgentDocuments.Host host(FxWindowFixture fixture) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            AgentCoordinator coordinator = FxTestSupport.field(fixture.controller, "agentCoordinator");
            AgentCoordinator.Ops ops = FxTestSupport.field(coordinator, "ops");
            return ops.nativeDocuments();
        });
    }

    @Test
    void productionLoaderEditAndSaveUseTheNormalDocumentWorkflow() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            var fixture = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("example.txt"), "original\n");
            var documents = new WindowAgentDocuments(host(fixture), new AgentWorkspace(dir));
            var cancellation = new AgentCancellation();
            var before = documents.read(file, cancellation);
            var edits = documents.apply(
                    List.of(new AgentDocuments.Edit(file, before.revision(), "original", "updated")), cancellation);
            assertEquals("original\n", Files.readString(file));
            documents.save(edits, cancellation);
            assertEquals("updated\n", Files.readString(file));
            assertTrue(documents.saved(edits.getFirst(), cancellation));
            var host = host(fixture);
            FxTestSupport.runOnFx(() -> host.find(file).getArea().undo());
            assertEquals("original\n", documents.read(file, cancellation).text());
            assertTrue(documents.read(file, cancellation).dirty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellingTheScopeOrSaveFuturePreventsAStagedCommit(boolean cancelFuture) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            var fixture = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("example.txt"), "original\n");
            var host = host(fixture);
            var documents = new WindowAgentDocuments(host, new AgentWorkspace(dir));
            var cancellation = new AgentCancellation();
            var before = documents.read(file, cancellation);
            documents.apply(
                    List.of(new AgentDocuments.Edit(file, before.revision(), "original", "updated")), cancellation);
            FileWorkflowCoordinator workflows = FxTestSupport.field(fixture.controller, "fileWorkflows");
            ExecutorService worker = FxTestSupport.field(workflows, "autoSaveExecutor");
            var staged = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            async.onClose(release::countDown);
            workflows.setDocumentWriter((target, bytes, commit) ->
                    AtomicFileWrite.writeIf(target, bytes, commit, new DelegatingFileOperations() {
                        @Override
                        public void write(Path path, byte[] contents) throws IOException {
                            super.write(path, contents);
                            staged.countDown();
                            try {
                                if (!release.await(10, TimeUnit.SECONDS))
                                    throw new IOException("Staged save timed out");
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IOException(interrupted);
                            }
                        }
                    }));
            var future = FxTestSupport.callOnFx(() -> host.save(host.find(file), cancellation));
            async.await(staged, "save staging");
            if (cancelFuture) future.cancel(false);
            else cancellation.cancel();
            release.countDown();
            async.awaitWorker(worker);
            async.awaitFx();
            assertEquals("original\n", Files.readString(file));
            assertTrue(FxTestSupport.callOnFx(() -> host.find(file).isDirty()));
            assertFalse(FxTestSupport.callOnFx(() -> workflows.hasPendingSave(host.find(file))));
            if (!cancelFuture) assertFalse(async.await(future));
        }
    }
}
