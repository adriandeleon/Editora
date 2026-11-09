namespace Shelf;

/// <summary>What is on the shelf; expand one in Variables to see its properties.</summary>
public record Item(string Sku, string Name, int Quantity, decimal Price);

public static class Stock
{
    public static readonly List<Item> Items =
    [
        new("A-1", "Pencil", 12, 0.40m),
        new("A-2", "Notebook", 3, 2.75m),
        new("B-1", "Stapler", 1, 8.90m),
        new("B-2", "Tape", 4, 1.20m),
        new("C-1", "Marker", 10, 1.05m),
        new("C-2", "Eraser", 25, 0.30m),
        new("C-3", "Ruler", 2, 1.50m),
        new("D-1", "Glue", 6, 2.10m),
    ];
}

public static class Checkout
{
    /// <summary>Totals up to <paramref name="limit"/> items: total, lines and byPrefix change on every pass.</summary>
    public static (List<string> Lines, decimal Total) Run(List<Item> shelf, int limit)
    {
        var lines = new List<string>();
        var byPrefix = new SortedDictionary<string, int>();
        decimal total = 0;
        foreach (var (item, i) in shelf.Select((item, i) => (item, i)))
        {
            if (i == limit)
            {
                break;
            }
            var discount = Pricing.DiscountFor(item); // break here: Step Into, then look at `discount` per item
            var price = Pricing.Apply(discount, item.Price);
            var subtotal = price * item.Quantity;
            lines.Add($"{item.Sku,-4} {item.Name,-9} {item.Quantity,3} x {price,5:F2} = {subtotal,7:F2}");
            var prefix = item.Sku[..1];
            byPrefix[prefix] = byPrefix.GetValueOrDefault(prefix) + item.Quantity;
            total += subtotal; // break here: a watch on `total`, or a logpoint printing {item.Name}
        }
        var cheap = shelf.Where(item => item.Price < 1.0m) // break here: a conditional breakpoint, item.Sku == "C-2"
            .Select(item => item.Name)
            .Order()
            .ToList();
        lines.Add($"cheap: {string.Join(", ", cheap)}  by prefix: {string.Join(", ", byPrefix)}");
        return (lines, total);
    }

    /// <summary>Turns "12" into 12; a word throws a FormatException, which the caller catches.</summary>
    public static int ParseQuantity(string text)
    {
        try
        {
            return int.Parse(text);
        }
        catch (FormatException e)
        {
            throw new ArgumentException($"not a quantity: {text}", e); // break here: `e` in Variables
        }
    }
}
