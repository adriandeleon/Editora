// A small C# sample for syntax highlighting and folding.
// Covers records, an interface, generics, LINQ, pattern matching, async/await,
// string interpolation, verbatim and raw strings, and attributes.
using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;

namespace Demo;

/// <summary>Anything the shelf can hold.</summary>
public interface IItem
{
    string Name { get; }
}

public record Book(string Name, int Pages) : IItem;

public record Tool(string Name, double WeightKg) : IItem;

public sealed class Shelf<T> where T : IItem
{
    private const int MaxItems = 0x40; // hex literal
    private readonly List<T> _items = new();

    public int Count => _items.Count;

    public bool Add(T item)
    {
        if (_items.Count >= MaxItems)
        {
            return false;
        }
        _items.Add(item);
        return true;
    }

    public IEnumerable<string> Names() =>
        from item in _items
        orderby item.Name
        select item.Name;
}

public static class Program
{
    /* A block comment: the switch expression matches on type and property. */
    private static string Describe(IItem item) => item switch
    {
        Book { Pages: > 500 } b => $"a long book: \"{b.Name}\"",
        Book b => $"a book: {b.Name}",
        Tool t => $"a tool ({t.WeightKg:F1} kg): {t.Name}",
        _ => throw new ArgumentOutOfRangeException(nameof(item)),
    };

    [Obsolete("Use Describe instead.")]
    private static string Path() => @"C:\shelf\items.txt";

    public static async Task Main()
    {
        var shelf = new Shelf<IItem>();
        shelf.Add(new Book("Dune", 612));
        shelf.Add(new Tool("Hammer", 0.6));

        await Task.Delay(TimeSpan.FromMilliseconds(1));
        Console.WriteLine(string.Join(", ", shelf.Names()));
        Console.WriteLine(Describe(new Book("Emma", 474)));
        Console.WriteLine("""A "raw" string literal.""");
    }
}
