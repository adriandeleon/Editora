import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * A deliberately long, deeply nested file. Nothing here is interesting as code; it exists so the
 * features that need height and depth have something to work on:
 *
 * <ul>
 *   <li><b>Sticky scroll</b>: scroll into {@link Aisle.Shelf.Bin#take} and the enclosing class, nested
 *       classes and method stay pinned above the viewport.</li>
 *   <li><b>Fold Level 1 to 5</b>, <b>Fold Recursively</b> and <b>Go to Parent Fold</b>.</li>
 *   <li><b>Structure</b> outline, <b>Go to Symbol</b>, and the <b>minimap</b>.</li>
 *   <li><b>Narrow to Defun</b> on any method, and <b>bracket pair colorization</b> in {@link #nested}.</li>
 * </ul>
 */
public class Warehouse {

    /** Stock-keeping unit. */
    public record Sku(String code, String description, double unitWeightKg) {}

    public enum Zone {
        AMBIENT,
        CHILLED,
        HAZARDOUS;

        boolean needsPermit() {
            return this == HAZARDOUS;
        }
    }

    public static class OutOfStock extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public OutOfStock(Sku sku, int wanted, int available) {
            super("wanted " + wanted + " of " + sku.code() + " but only " + available + " available");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Level 1: an aisle. Level 2: a shelf in it. Level 3: a bin on that shelf.
    // ---------------------------------------------------------------------------------------------

    public static class Aisle {
        private final String name;
        private final Zone zone;
        private final List<Shelf> shelves = new ArrayList<>();

        public Aisle(String name, Zone zone) {
            this.name = name;
            this.zone = zone;
        }

        public Shelf addShelf(int height) {
            Shelf shelf = new Shelf(height);
            shelves.add(shelf);
            return shelf;
        }

        public int totalQuantity() {
            int total = 0;
            for (Shelf shelf : shelves) {
                for (Shelf.Bin bin : shelf.bins) {
                    if (bin.quantity > 0) {
                        total += bin.quantity;
                    }
                }
            }
            return total;
        }

        public class Shelf {
            private final int height;
            private final List<Bin> bins = new ArrayList<>();

            Shelf(int height) {
                this.height = height;
            }

            public Bin addBin(Sku sku, int quantity) {
                Bin bin = new Bin(sku, quantity);
                bins.add(bin);
                return bin;
            }

            public String label() {
                return name + "-" + height;
            }

            public class Bin {
                private final Sku sku;
                private int quantity;

                Bin(Sku sku, int quantity) {
                    this.sku = sku;
                    this.quantity = quantity;
                }

                /** Removes {@code wanted} units, or throws without changing anything. */
                public int take(int wanted) {
                    if (wanted <= 0) {
                        throw new IllegalArgumentException("wanted must be positive");
                    }
                    if (zone.needsPermit()) {
                        if (!permits.contains(sku.code())) {
                            throw new IllegalStateException("no permit for " + sku.code());
                        }
                    }
                    if (wanted > quantity) {
                        throw new OutOfStock(sku, wanted, quantity);
                    }
                    quantity -= wanted;
                    return quantity;
                }

                public double weightKg() {
                    return quantity * sku.unitWeightKg();
                }

                @Override
                public String toString() {
                    return label() + "/" + sku.code() + " x" + quantity;
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The warehouse itself
    // ---------------------------------------------------------------------------------------------

    private static final List<String> permits = new ArrayList<>();
    private final Map<String, Aisle> aisles = new LinkedHashMap<>();

    public Aisle aisle(String name, Zone zone) {
        return aisles.computeIfAbsent(name, n -> new Aisle(n, zone));
    }

    public void grantPermit(String skuCode) {
        permits.add(skuCode);
    }

    public List<Aisle.Shelf.Bin> find(Predicate<Aisle.Shelf.Bin> test) {
        List<Aisle.Shelf.Bin> found = new ArrayList<>();
        for (Aisle aisle : aisles.values()) {
            for (Aisle.Shelf shelf : aisle.shelves) {
                for (Aisle.Shelf.Bin bin : shelf.bins) {
                    if (test.test(bin)) {
                        found.add(bin);
                    }
                }
            }
        }
        return found;
    }

    public Optional<Aisle.Shelf.Bin> heaviest() {
        return find(bin -> true).stream().max(Comparator.comparingDouble(Aisle.Shelf.Bin::weightKg));
    }

    public Map<Zone, Integer> quantityByZone() {
        Map<Zone, Integer> totals = new LinkedHashMap<>();
        for (Aisle aisle : aisles.values()) {
            totals.merge(aisle.zone, aisle.totalQuantity(), Integer::sum);
        }
        return totals;
    }

    /** Pick {@code wanted} units of a SKU from as many bins as it takes, emptiest bin first. */
    public int pick(String skuCode, int wanted) {
        List<Aisle.Shelf.Bin> bins = find(bin -> bin.sku.code().equals(skuCode) && bin.quantity > 0);
        bins.sort(Comparator.comparingInt(bin -> bin.quantity));
        int remaining = wanted;
        for (Aisle.Shelf.Bin bin : bins) {
            if (remaining == 0) {
                break;
            }
            int fromThisBin = Math.min(remaining, bin.quantity);
            bin.take(fromThisBin);
            remaining -= fromThisBin;
        }
        return wanted - remaining;
    }

    /** Six bracket depths on one line, for bracket pair colorization and Go to Matching Bracket. */
    static int nested(int[][] grid) {
        return Math.max(0, Math.min(9, grid[Math.abs((grid.length - 1) % (1 + (2 * (3 - 2))))][0]));
    }

    public String report() {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Aisle> entry : aisles.entrySet()) {
            Aisle aisle = entry.getValue();
            out.append("Aisle ").append(entry.getKey()).append(" [").append(aisle.zone).append("]\n");
            for (Aisle.Shelf shelf : aisle.shelves) {
                out.append("  Shelf ").append(shelf.label()).append('\n');
                for (Aisle.Shelf.Bin bin : shelf.bins) {
                    out.append("    ").append(bin);
                    if (bin.quantity == 0) {
                        out.append("  (empty)");
                    } else if (bin.weightKg() > 100) {
                        out.append("  (heavy)");
                    }
                    out.append('\n');
                }
            }
        }
        return out.toString();
    }

    public static void main(String[] args) {
        Warehouse warehouse = new Warehouse();
        Sku bolts = new Sku("BOLT-M8", "M8 bolts, box of 100", 1.2);
        Sku paint = new Sku("PAINT-RED", "Red paint, 5 l", 6.5);
        Sku milk = new Sku("MILK-1L", "Milk, 1 l", 1.03);

        Aisle a = warehouse.aisle("A", Zone.AMBIENT);
        a.addShelf(1).addBin(bolts, 40);
        a.addShelf(2).addBin(bolts, 5);
        warehouse.aisle("B", Zone.HAZARDOUS).addShelf(1).addBin(paint, 20);
        warehouse.aisle("C", Zone.CHILLED).addShelf(1).addBin(milk, 0);

        warehouse.grantPermit(paint.code());
        System.out.println("picked " + warehouse.pick("BOLT-M8", 12) + " bolts");
        System.out.println("picked " + warehouse.pick("PAINT-RED", 3) + " tins of paint");
        try {
            warehouse.find(bin -> bin.sku.equals(milk)).get(0).take(1);
        } catch (OutOfStock e) {
            System.out.println("out of stock: " + e.getMessage());
        }
        System.out.print(warehouse.report());
        System.out.println(warehouse.quantityByZone() + " heaviest=" + warehouse.heaviest().orElseThrow());
        System.out.println("nested=" + nested(new int[][] {{7}, {8}}));
    }
}
