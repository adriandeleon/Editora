// The shelf, in C#: open this file and press F5 (Debug). Editora builds the project with dotnet build in the
// Debug console, then netcoredbg stops at your breakpoints. Every suggested spot is a "break here" comment.
// `dotnet run` runs it outside Editora; the `crash` argument throws, which stops the debugger where it was thrown.
using Shelf;

int limit = 6; // break here: a first plain breakpoint; Step Over, then Step Into Checkout
if (args.Length > 0)
{
    if (args[0] == "crash")
    {
        var missing = new Dictionary<string, int>();
        Console.WriteLine(missing["boom"]); // a KeyNotFoundException nobody catches: the debugger stops here
    }
    else
    {
        try
        {
            limit = Checkout.ParseQuantity(args[0]);
        }
        catch (ArgumentException e)
        {
            Console.WriteLine(e.Message); // a word: caught here, so the debugger does not stop on it
        }
    }
}
var (lines, total) = Checkout.Run(Stock.Items, limit);
foreach (var line in lines)
{
    Console.WriteLine(line);
}
Console.WriteLine($"total {total:F2}");
Console.WriteLine($"restocked {Restock.Run(3)}");
