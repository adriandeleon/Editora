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
 * <p>{@code java.awt.Desktop.setOpenFileHandler} can't be used because {@code App.main} forces
 * {@code java.awt.headless=true} (the SVG/Java2D guard), under which AWT's {@code Desktop} throws.
 */
final class MacOpenFiles {

    private static final Logger LOG = Logger.getLogger(MacOpenFiles.class.getName());

    private MacOpenFiles() {}

    /**
     * Installs the Glass {@code openFiles} handler, forwarding opened files to {@code windows}. Wraps JavaFX's
     * own event handler ({@link Handler} delegates every other event — quit, activate, preferences, … — to it)
     * so Cmd-Q and the rest keep working; only {@code openFiles} is augmented. Any failure (internal API
     * absent, no {@code --add-exports}, non-standard runtime) is logged and ignored — never fatal.
     */
    static void install(com.editora.ui.WindowManager windows) {
        try {
            com.sun.glass.ui.Application app = com.sun.glass.ui.Application.GetApplication();
            if (app == null) {
                return;
            }
            app.setEventHandler(new Handler(app.getEventHandler(), files -> {
                if (files == null || files.length == 0) {
                    return;
                }
                java.util.List<java.nio.file.Path> paths = new java.util.ArrayList<>();
                for (String f : files) {
                    if (f != null && !f.isBlank()) {
                        paths.add(java.nio.file.Path.of(f));
                    }
                }
                if (!paths.isEmpty()) {
                    javafx.application.Platform.runLater(() -> windows.openExternalFiles(paths));
                }
            }));
        } catch (Throwable t) {
            LOG.log(Level.FINE, "macOS open-files handler unavailable", t);
        }
    }

    /**
     * A Glass {@code Application.EventHandler} that forwards {@code handleOpenFilesAction} to a callback and
     * <b>delegates every other method to the wrapped handler</b> (JavaFX's own, which drives Cmd-Q, activate,
     * preferences, …). Overriding the full surface — rather than just {@code handleOpenFilesAction} — is
     * required because {@code setEventHandler} <em>replaces</em> the handler wholesale, so an un-forwarded
     * method would silently drop JavaFX's default behavior for that event.
     */
    private static final class Handler extends com.sun.glass.ui.Application.EventHandler {
        private final com.sun.glass.ui.Application.EventHandler delegate;
        private final Consumer<String[]> onOpenFiles;

        Handler(com.sun.glass.ui.Application.EventHandler delegate, Consumer<String[]> onOpenFiles) {
            this.delegate = delegate;
            this.onOpenFiles = onOpenFiles;
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
            if (delegate != null) {
                delegate.handleQuitAction(app, time);
            }
        }

        @Override
        public void handlePreferencesChanged(java.util.Map<String, Object> map) {
            if (delegate != null) {
                delegate.handlePreferencesChanged(map);
            }
        }
    }
}
