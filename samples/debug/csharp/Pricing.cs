namespace Shelf;

/// <summary>A percentage off, possibly stacked on another discount.</summary>
public record Discount(decimal Percent, Discount? Then = null);

public static class Pricing
{
    /// <summary>An item's discount: a stacked one for a big quantity.</summary>
    public static Discount? DiscountFor(Item item) => item switch
    {
        { Quantity: >= 10 } => new Discount(10, new Discount(5)), // break here: hit once per big item
        { Price: > 5 } => new Discount(15),
        _ => null,
    };

    /// <summary>Recurses through a stacked discount: the call stack shows two Apply frames.</summary>
    public static decimal Apply(Discount? discount, decimal price)
    {
        if (discount is null)
        {
            return price;
        }
        price = price * (100 - discount.Percent) / 100; // break here: Step Out, or look at the frames below
        return Apply(discount.Then, price);
    }
}
