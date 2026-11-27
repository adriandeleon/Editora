// The items on the shelf and the checkout loop: a watch, a logpoint, a conditional breakpoint, a caught error.
import 'pricing.dart';

/// What is on the shelf; expand one in Variables to see its fields.
final class Item {
  const Item(this.sku, this.name, this.quantity, this.price);

  final String sku;
  final String name;
  final int quantity;
  final double price;
}

const items = [
  Item('A-1', 'Pencil', 12, 0.40),
  Item('A-2', 'Notebook', 3, 2.75),
  Item('B-1', 'Stapler', 1, 8.90),
  Item('B-2', 'Tape', 4, 1.20),
  Item('C-1', 'Marker', 10, 1.05),
  Item('C-2', 'Eraser', 25, 0.30),
  Item('C-3', 'Ruler', 2, 1.50),
  Item('D-1', 'Glue', 6, 2.10),
];

/// Totals up to [limit] items: `total`, `lines` and `byPrefix` change on every pass.
(List<String>, double) checkout(List<Item> shelf, int limit) {
  final lines = <String>[];
  final byPrefix = <String, int>{};
  var total = 0.0;
  for (final item in shelf.take(limit)) {
    final discount = discountFor(item); // break here: Step Into, then look at `discount` per item
    final price = apply(discount, item.price);
    final subtotal = price * item.quantity;
    lines.add('${item.sku.padRight(4)} ${item.name.padRight(9)} ${'${item.quantity}'.padLeft(3)} x '
        '${price.toStringAsFixed(2).padLeft(5)} = ${subtotal.toStringAsFixed(2).padLeft(7)}');
    byPrefix.update(item.sku[0], (n) => n + item.quantity, ifAbsent: () => item.quantity);
    total += subtotal; // break here: a watch on `total`, or a logpoint printing {item.name}
  }
  final cheap = shelf
      .where((item) => item.price < 1.0) // break here: a conditional breakpoint, item.sku == 'C-2'
      .map((item) => item.name)
      .toList()
    ..sort();
  lines.add('cheap: ${cheap.join(', ')}  by prefix: $byPrefix');
  return (lines, total);
}

/// Turns "12" into 12; a word throws a FormatException, which the caller catches.
int parseQuantity(String text) {
  try {
    return int.parse(text);
  } on FormatException catch (e) {
    throw FormatException('not a quantity: $text (${e.message})'); // break here: `e` in Variables
  }
}
