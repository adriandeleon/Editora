package com.editora.ui;

import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.editora.AppInfo;
import com.editora.config.Settings;
import com.editora.update.ReleaseInfo;
import com.editora.update.UpdateService;
import com.editora.update.UpdateStatus;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The release check of one window: the automatic check ({@link UpdateCheckScheduler}), the manual "Check
 * for Updates", the status-bar notice and the download page. The last answer is kept in the shared
 * preferences, so every window, the About panel and the next launch go on the same one until the next check.
 */
final class UpdateCoordinator implements SettingsWindow.AboutUpdates {

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

    /** What the last check that got an answer found, for the About panel. */
    @Override
    public UpdateStatus status() {
        Settings s = settings.get();
        return new UpdateStatus(s.getLatestReleaseVersion(), s.getLatestReleaseUrl(), s.getLastUpdateCheckOkEpoch());
    }

    /** The newer release the last check found; null when up to date or not yet checked. */
    private @Nullable ReleaseInfo knownUpdate() {
        return status().update(AppInfo.VERSION);
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
        checkNow(error -> {});
    }

    /** As {@link #checkNow()}, then tells {@code done} the failure reason, or null when the check got an answer. */
    @Override
    public void checkNow(Consumer<@Nullable String> done) {
        setStatus.accept(tr("status.update.checking"));
        service.check(AppInfo.VERSION, o -> {
            apply(o, true);
            done.accept(o.error());
        });
    }

    /** Applies a check result: keep what it found, show the notice for a newer release; a manual check also
     *  echoes an up-to-date / failure status. A failed check keeps the last answer. */
    private void apply(UpdateService.Outcome outcome, boolean manual) {
        if (!outcome.ok()) {
            if (manual) {
                setStatus.accept(tr("status.update.failed", outcome.error()));
            }
            return;
        }
        ReleaseInfo latest = outcome.latest(); // set on every successful check
        if (latest != null) {
            Settings s = settings.get();
            s.setLatestReleaseVersion(latest.version());
            s.setLatestReleaseUrl(latest.url());
            s.setLastUpdateCheckOkEpoch(System.currentTimeMillis());
            requestSave.run();
        }
        if (outcome.available() && latest != null) {
            refreshNotice();
            setStatus.accept(tr("status.update.available", latest.version()));
        } else {
            refreshNotice(); // running the latest: withdraws a notice shown earlier
            if (manual) {
                setStatus.accept(tr("status.update.upToDate", AppInfo.VERSION));
            }
        }
    }

    /** Shows the window's status-bar update segment when a newer, non-dismissed release is known; else hides it. */
    void refreshNotice() {
        ReleaseInfo info = knownUpdate();
        boolean show = info != null && !info.version().equals(settings.get().getDismissedUpdateVersion());
        showNotice.accept(show, show && info != null ? info.version() : null);
    }

    /** Opens the release page for the known update (else the releases page) and dismisses that version's notice. */
    void openDownloadPage() {
        ReleaseInfo info = knownUpdate();
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
