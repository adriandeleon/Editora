package com.editora.ui;

import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.editora.AppInfo;
import com.editora.config.Settings;
import com.editora.update.ReleaseInfo;
import com.editora.update.UpdateService;

import static com.editora.i18n.Messages.tr;

/**
 * The release check of one window: the automatic check ({@link UpdateCheckScheduler}), the manual "Check
 * for Updates", the status-bar notice and the download page. The newest release found is shared across
 * windows in-process, so a second window and the About dialog see it.
 */
final class UpdateCoordinator {

    /** The newest release newer than the running version; null when up to date or not yet checked. */
    private static volatile ReleaseInfo latestKnownUpdate;

    private final UpdateService service = new UpdateService();
    private final Supplier<Settings> settings;
    private final BooleanSupplier windowClosed;
    private final Runnable requestSave;
    private final Consumer<String> setStatus;
    /** Shows or hides the status-bar segment: {@code (available, version)}. */
    private final BiConsumer<Boolean, String> showNotice;

    private final Consumer<String> openUrl;

    UpdateCoordinator(
            Supplier<Settings> settings,
            BooleanSupplier windowClosed,
            Runnable requestSave,
            Consumer<String> setStatus,
            BiConsumer<Boolean, String> showNotice,
            Consumer<String> openUrl) {
        this.settings = settings;
        this.windowClosed = windowClosed;
        this.requestSave = requestSave;
        this.setStatus = setStatus;
        this.showNotice = showNotice;
        this.openUrl = openUrl;
    }

    /** The newest known release, for the About dialog's "Update available" row. */
    static ReleaseInfo latestKnown() {
        return latestKnownUpdate;
    }

    /** Called at the end of the window's init: reflects a release an earlier window found, and schedules the
     *  automatic check (once per session, once per day, a few seconds after the first paint). */
    void applyAtStartup() {
        refreshNotice();
        UpdateCheckScheduler.maybeSchedule(
                settings, windowClosed, requestSave, () -> service.check(AppInfo.VERSION, o -> apply(o, false)));
    }

    /** Manual "Check for Updates" — always checks (ignores the throttle + the auto-check setting). */
    void checkNow() {
        setStatus.accept(tr("status.update.checking"));
        service.check(AppInfo.VERSION, o -> apply(o, true));
    }

    /** Applies a check result: on a newer release, cache it + show the notice; a manual check also echoes an
     *  up-to-date / failure status. */
    private void apply(UpdateService.Outcome outcome, boolean manual) {
        if (!outcome.ok()) {
            if (manual) {
                setStatus.accept(tr("status.update.failed", outcome.error()));
            }
            return;
        }
        if (outcome.available()) {
            latestKnownUpdate = outcome.latest();
            refreshNotice();
            setStatus.accept(tr("status.update.available", outcome.latest().version()));
        } else {
            latestKnownUpdate = null; // running the latest
            refreshNotice();
            if (manual) {
                setStatus.accept(tr("status.update.upToDate", AppInfo.VERSION));
            }
        }
    }

    /** Shows the window's status-bar update segment when a newer, non-dismissed release is known; else hides it. */
    void refreshNotice() {
        ReleaseInfo info = latestKnownUpdate;
        boolean show = info != null && !info.version().equals(settings.get().getDismissedUpdateVersion());
        showNotice.accept(show, show ? info.version() : null);
    }

    /** Opens the release page for the known update (else the releases page) and dismisses that version's notice. */
    void openDownloadPage() {
        ReleaseInfo info = latestKnownUpdate;
        openUrl.accept(info != null && !info.url().isBlank() ? info.url() : AppInfo.RELEASES_PAGE);
        if (info != null) {
            settings.get().setDismissedUpdateVersion(info.version());
            requestSave.run();
        }
        refreshNotice();
    }

    /** Stops the check worker (window close). */
    void shutdown() {
        service.shutdown();
    }
}
