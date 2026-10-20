package com.editora.lsp;

import org.eclipse.lsp4j.SignatureHelp;
import org.jspecify.annotations.Nullable;

/** Retains an explicitly selected overload across server refreshes without inventing applicability. */
public final class SignatureSelection {
    private @Nullable SignatureHelp help;
    private @Nullable String selectedLabel;

    public void update(SignatureHelp next) {
        if (SignatureFormat.resolve(next) == null) {
            clear();
            return;
        }
        int index = next.getActiveSignature() == null ? 0 : next.getActiveSignature();
        if (selectedLabel != null) {
            int found = -1;
            for (int i = 0; i < next.getSignatures().size(); i++)
                if (next.getSignatures().get(i) != null
                        && selectedLabel.equals(next.getSignatures().get(i).getLabel())) found = i;
            if (found >= 0) index = found;
            else selectedLabel = null;
        }
        help = new SignatureHelp(next.getSignatures(), index, next.getActiveParameter());
    }

    public void move(int delta) {
        var current = help;
        var active = active();
        if (current == null || active == null || active.total() < 2) return;
        int index = Math.floorMod(active.index() + delta, active.total());
        help = new SignatureHelp(current.getSignatures(), index, current.getActiveParameter());
        var next = active();
        selectedLabel = next == null ? null : next.label();
    }

    public @Nullable SignatureHelp context() {
        return help;
    }

    public SignatureFormat.@Nullable Active active() {
        return SignatureFormat.resolve(help);
    }

    public void clear() {
        help = null;
        selectedLabel = null;
    }
}
