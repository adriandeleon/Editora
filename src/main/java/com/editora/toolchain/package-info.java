/**
 * Which JDK, Maven and Gradle a project uses: the installs jkit and SDKMAN keep ({@link JkitInstalls},
 * {@link SdkmanInstalls}), a project's {@code .sdkmanrc} ({@link SdkmanRc}), the project's own choice in
 * {@code .editora/settings.json}, and driving the {@code jkit} command ({@link JkitCli}). Pure where it can be:
 * the disk is read by the callers' off-FX reads, and {@link ProjectToolchain#resolve} only matches.
 */
@NullMarked
package com.editora.toolchain;

import org.jspecify.annotations.NullMarked;
