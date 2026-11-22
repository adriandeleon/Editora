package com.editora;

import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * macOS "Open With" support: routes files opened from Finder into the editor. Finder delivers the path as an
 * AppKit {@code openFiles} Apple Event (not a command-line argument), so the argv path ({@code App.fileTargets})
 * never sees it; this installs a Glass {@code Application.EventHandler} to receive it.
 *
 * <p>Kept as a <b>standalone top-level class</b> (not nested in {@link App}) on purpose: the JavaFX launcher
 * reflects over the {@code App} main class to find its methods, which eagerly loads {@code App}'s nest members
 * — so a {@code com.sun.glass.ui} subclass nested in {@code App} would be class-loaded (and access-checked)
 * <em>before</em> {@code start()} runs, outside any {@code try/catch}. As a separate class, {@link Handler}
 * loads only when {@link #install} actually runs, where a missing {@code --add-exports
 * javafx.graphics/com.sun.glass.ui=com.editora} degrades to a logged no-op instead of a launch crash.
 *
 * <p>The same handler catches macOS's own Quit (WIN-13): see {@link #install}.
 *
 * <p>{@code java.awt.Desktop.setOpenFileHandler} (and its quit, about and preferences handlers) can't be used
 * because {@code App.main} forces {@code java.awt.headless=true} (the SVG/Java2D guard), under which AWT's
 * {@code Desktop} throws.
 */
final class MacOpenFiles {

    private static final Logger LOG = Logger.getLogger(MacOpenFiles.class.getName());

    private MacOpenFiles() {}

    /**
     * Installs the Glass {@code openFiles} handler, forwarding opened files to {@code windows}, and the quit
     * handler. Wraps JavaFX's own event handler ({@link Handler} delegates every other event — activate, hide,
     * preferences, … — to it); {@code openFiles} is augmented, and quit (the application menu's Quit, Cmd-Q
     * there, the Dock's Quit, a logout) is taken over: it runs {@code app.quit} in the focused window, the
     * command File → Quit runs, instead of JavaFX's close request to every window in turn (WIN-13). Any
     * failure (internal API absent, no {@code --add-exports}, non-standard runtime) is logged and ignored —
     * never fatal, and then the quit stays JavaFX's.
     */
    static void install(com.editora.ui.WindowManager windows) {
        try {
            com.sun.glass.ui.Application app = com.sun.glass.ui.Application.GetApplication();
            if (app == null) {
                return;
            }
            app.setEventHandler(new Handler(
                    app.getEventHandler(),
                    files -> {
                        if (files == null || files.length == 0) {
                            return;
                        }
                        // Through the same parse the command line uses. macOS delivers a launcher argument here as
                        // well as on argv, so `Editora file.java:42` arrived twice: once parsed into a file and a
                        // line, and once as a literal name with the suffix still attached — which opened nothing and
                        // reported a failure for a file that had in fact just opened.
                        java.util.List<com.editora.ui.MainController.OpenTarget> targets = new java.util.ArrayList<>();
                        for (String f : files) {
                            if (f == null || f.isBlank()) {
                                continue;
                            }
                            com.editora.ui.MainController.OpenTarget target = App.externalTarget(f);
                            if (target != null) {
                                targets.add(target);
                            }
                        }
                        if (!targets.isEmpty()) {
                            javafx.application.Platform.runLater(() -> windows.openExternalFiles(targets));
                        }
                    },
                    () -> windows.runFromApplicationMenu("app.quit")));
        } catch (Throwable t) {
            LOG.log(Level.FINE, "macOS open-files handler unavailable", t);
        }
    }

    /**
     * A Glass {@code Application.EventHandler} that forwards {@code handleOpenFilesAction} to a callback and
     * runs {@code onQuit} for {@code handleQuitAction}, and <b>delegates every other method to the wrapped
     * handler</b> (JavaFX's own, which drives activate, hide, preferences, …). Overriding the full surface —
     * rather than just {@code handleOpenFilesAction} — is required because {@code setEventHandler}
     * <em>replaces</em> the handler wholesale, so an un-forwarded method would silently drop JavaFX's default
     * behavior for that event.
     */
    private static final class Handler extends com.sun.glass.ui.Application.EventHandler {
        private final com.sun.glass.ui.Application.EventHandler delegate;
        private final Consumer<String[]> onOpenFiles;
        private final Runnable onQuit;

        Handler(com.sun.glass.ui.Application.EventHandler delegate, Consumer<String[]> onOpenFiles, Runnable onQuit) {
            this.delegate = delegate;
            this.onOpenFiles = onOpenFiles;
            this.onQuit = onQuit;
        }

        @Override
        public void handleOpenFilesAction(com.sun.glass.ui.Application app, long time, String[] files) {
            if (delegate != null) {
                delegate.handleOpenFilesAction(app, time, files);
            }
            try {
                onOpenFiles.accept(files);
            } catch (Throwable t) {
                LOG.log(Level.FINE, "open-files handling failed", t);
            }
        }

        @Override
        public void handleWillFinishLaunchingAction(com.sun.glass.ui.Application app, long time) {
            if (delegate != null) {
                delegate.handleWillFinishLaunchingAction(app, time);
            }
        }

        @Override
        public void handleDidFinishLaunchingAction(com.sun.glass.ui.Application app, long time) {
            if (delegate != null) {
                delegate.handleDidFinishLaunchingAction(app, time);
            }
        }

        @Override
        public void handleWillBecomeActiveAction(com.sun.glass.ui.Application app, long time) {
            if (delegate != null) {
                delegate.handleWillBecomeActiveAction(app, time);
            }
        }

        @Override
        public void handleDidBecomeActiveAction(com.sun.glass.ui.Application app, long time) {
            if (delegate != null) {
                delegate.handleDidBecomeActiveAction(app, time);
            }
        }

        @Override
        public void handleWillResignActiveAction(com.sun.glass.ui.Application app, long time) {
            if (delegate != null) {
                delegate.handleWillResignActiveAction(app, time);
            }
        }

        @Override
        public void handleDidResignActiveAction(com.sun.glass.ui.Application app, long time) {
            if (delegate != null) {
                delegate.handleDidResignActiveAction(app, time);
            }
        }

        @Override
        public void handleDidReceiveMemoryWarning(com.sun.glass.ui.Application app, long time) {
            if (delegate != null) {
                delegate.handleDidReceiveMemoryWarning(app, time);
            }
        }

        @Override
        public void handleWillHideAction(com.sun.glass.ui.Application app, long time) {
            if (delegate != null) {
                delegate.handleWillHideAction(app, time);
            }
        }

        @Override
        public void handleDidHideAction(com.sun.glass.ui.Application app, long time) {
            if (delegate != null) {
                delegate.handleDidHideAction(app, time);
            }
        }

        @Override
        public void handleWillUnhideAction(com.sun.glass.ui.Application app, long time) {
            if (delegate != null) {
                delegate.handleWillUnhideAction(app, time);
            }
        }

        @Override
        public void handleDidUnhideAction(com.sun.glass.ui.Application app, long time) {
            if (delegate != null) {
                delegate.handleDidUnhideAction(app, time);
            }
        }

        @Override
        public void handleQuitAction(com.sun.glass.ui.Application app, long time) {
            // Not passed on: JavaFX's own handler sends every window a close request, and each asked for
            // itself. Run on a later pulse, outside this native callback, as the Quit row's command runs.
            // Glass has already told macOS not to terminate, so a cancelled quit leaves the app running.
            javafx.application.Platform.runLater(() -> {
                try {
                    onQuit.run();
                } catch (Throwable t) {
                    LOG.log(Level.WARNING, "quit from the application menu failed", t);
                }
            });
        }

        @Override
        public void handlePreferencesChanged(java.util.Map<String, Object> map) {
            if (delegate != null) {
                delegate.handlePreferencesChanged(map);
            }
        }
    }
}
