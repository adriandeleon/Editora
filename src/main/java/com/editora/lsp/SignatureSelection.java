package com.editora.lsp;

import org.eclipse.lsp4j.SignatureHelp;

/** Retains an explicitly selected overload across server refreshes without inventing applicability. */
public final class SignatureSelection {
    private SignatureHelp help;
    private String selectedLabel;

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
        var active = active();
        if (active == null || active.total() < 2) return;
        int index = Math.floorMod(active.index() + delta, active.total());
        help = new SignatureHelp(help.getSignatures(), index, help.getActiveParameter());
        var next = active();
        selectedLabel = next == null ? null : next.label();
    }

    public SignatureHelp context() {
        return help;
    }

    public SignatureFormat.Active active() {
        return SignatureFormat.resolve(help);
    }

    public void clear() {
        help = null;
        selectedLabel = null;
    }
}
