package demo;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A small Java sample for syntax highlighting and folding.
 *
 * <p>Covers the scopes worth checking by eye: Javadoc, annotations, generics, a record, a sealed
 * interface, a switch expression, a text block, a lambda, and string escapes.
 */
public final class Sample {

    /** Anything the inventory can hold. */
    sealed interface Item permits Book, Tool {}

    record Book(String title, int pages) implements Item {}

    record Tool(String name, double weightKg) implements Item {}

    private static final int MAX_ITEMS = 0x40; // hex literal
    private static final String BANNER = """
            Inventory report
            ----------------
            """;

    private final List<Item> items = new ArrayList<>();

    /** Adds an item, refusing once the shelf is full. */
    public boolean add(Item item) {
        if (items.size() >= MAX_ITEMS) {
            return false;
        }
        return items.add(item);
    }

    /* A block comment: the switch below is exhaustive because Item is sealed. */
    static String describe(Item item) {
        return switch (item) {
            case Book b when b.pages() > 500 -> "a long book: \"" + b.title() + "\"";
            case Book b -> "a book: " + b.title();
            case Tool t -> String.format("a tool (%.1f kg): %s", t.weightKg(), t.name());
        };
    }

    public Optional<Item> heaviestTool() {
        return items.stream()
                .filter(Tool.class::isInstance)
                .max((a, b) -> Double.compare(((Tool) a).weightKg(), ((Tool) b).weightKg()));
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder(BANNER);
        for (Item item : items) {
            out.append("- ").append(describe(item)).append('\n');
        }
        return out.toString();
    }

    public static void main(String[] args) {
        Sample shelf = new Sample();
        shelf.add(new Book("The Mythical Man-Month", 322));
        shelf.add(new Tool("Hammer", 0.6));
        System.out.print(shelf);
    }
}
