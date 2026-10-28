package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.doctor.DoctorCheck;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Doctor's lemminx-maven artifact store row looks where lemminx-maven puts the store: {@code .lemminx-maven} beside the
 * local repository, which settings.xml can move (XML/pom review 3, A22/D21).
 */
class DoctorMavenStoreTest {

    @Test
    void theStoreIsBesideTheDefaultLocalRepository(@TempDir Path home) {
        assertEquals(
                home.toAbsolutePath().normalize().resolve(".m2/.lemminx-maven"), DoctorCoordinator.mavenStoreDir(home));
    }

    @Test
    void aMovedLocalRepositoryMovesTheStore(@TempDir Path home) throws Exception {
        Path repo = home.resolve("data/m2/repository");
        Files.createDirectories(home.resolve(".m2"));
        Files.writeString(
                home.resolve(".m2/settings.xml"),
                "<settings xmlns=\"http://maven.apache.org/SETTINGS/1.0.0\"><localRepository>" + repo
                        + "</localRepository></settings>");
        Path store = DoctorCoordinator.mavenStoreDir(home);
        assertEquals(home.resolve("data/m2/.lemminx-maven").toAbsolutePath().normalize(), store);

        Files.createDirectories(store.resolve("org/x/1"));
        Files.writeString(store.resolve("org/x/1/x-1.pom"), "<project/>");
        DoctorCheck row = DoctorCoordinator.mavenStoreRow(() -> store).probe().get();
        assertTrue(row.detail().contains(store.toString()), row.detail());

        Files.writeString(
                home.resolve(".m2/settings.xml"),
                "<settings><localRepository>${user.home}/elsewhere/repo</localRepository></settings>");
        assertEquals(
                home.resolve("elsewhere/.lemminx-maven").toAbsolutePath().normalize(),
                DoctorCoordinator.mavenStoreDir(home));
        assertEquals(
                tr("doctor.xmlCache.empty"),
                DoctorCoordinator.mavenStoreRow(() -> DoctorCoordinator.mavenStoreDir(home))
                        .probe()
                        .get()
                        .detail());
    }
}
