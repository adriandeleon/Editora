package demo;

import java.util.List;

/** A small Java sample for syntax highlighting + folding. */
public final class Sample {
    private final List<String> items;

    public Sample(List<String> items) {
        this.items = items;
    }

    public int count() {
        return items.size();
    }
}
