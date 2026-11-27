// Discounts as a sealed class: apply() recurses through a stacked one, so the call stack shows nested frames.
import 'checkout.dart';

sealed class Discount {
  const Discount();
}

/// A percentage off.
final class PercentOff extends Discount {
  const PercentOff(this.percent);

  final double percent;
}

/// One discount, then another on what is left.
final class Stacked extends Discount {
  const Stacked(this.first, this.then);

  final Discount first;
  final Discount then;
}

/// An item's discount: a stacked one for a big quantity, none for a cheap item.
Discount? discountFor(Item item) {
  if (item.quantity >= 10) {
    return const Stacked(PercentOff(10), PercentOff(5)); // break here: hit once per big item
  }
  return item.price > 5 ? const PercentOff(15) : null;
}

/// Recurses through a stacked discount: the call stack shows an apply frame inside another.
double apply(Discount? discount, double price) {
  switch (discount) {
    case null:
      return price;
    case PercentOff(:final percent):
      return price * (100 - percent) / 100; // break here: a condition, percent == 5, stops in a stack's second apply
    case Stacked(:final first, :final then):
      return apply(then, apply(first, price));
  }
}
