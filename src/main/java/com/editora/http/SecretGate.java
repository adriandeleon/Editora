package com.editora.http;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The trust gate for secrets: asked once per run, on the run's worker and before anything is sent, whether the run may
 * read {@code reads} (the secret sources its requests resolve, as written: {@code $env TOKEN}, {@code $dotenv KEY}) and
 * send what they resolve to {@code hosts}. It may block (a dialog on the FX thread). A gate that asks the user checks
 * {@code cancelled} again just before asking, so a run cancelled or replaced meanwhile never puts up a prompt.
 *
 * <p>The answer is a {@link Verdict}, not a message: {@link SecretApproval} words the refusal the requests that read
 * secrets are reported with.
 */
@FunctionalInterface
public interface SecretGate {

    /** Reads anything without asking (tests, tools; the editor always passes its own gate). */
    SecretGate UNCHECKED = (reads, hosts, cancelled) -> Verdict.ALLOWED;

    Verdict check(List<String> reads, List<String> hosts, BooleanSupplier cancelled);

    /** What a gate answers. */
    enum Verdict {
        /** The run may read the secrets and send them to the hosts. */
        ALLOWED,
        /** The user (or a window without a trust store) refused. */
        REFUSED,
        /** There is no folder to trust: an untitled buffer outside any project. */
        NO_FOLDER,
        /** The run was cancelled or replaced before, or while, it was asked. */
        CANCELLED
    }
}
