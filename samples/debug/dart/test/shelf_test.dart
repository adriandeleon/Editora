// Green package:test tests: put the caret in one and use Debug Test at Caret, which runs that test alone.
import 'package:shelf_sample/shelf_sample.dart';
import 'package:test/test.dart';

void main() {
  group('checkout', () {
    test('totals the first two items', () {
      final (lines, total) = checkout(items, 2);
      expect(lines, hasLength(3)); // break here: `lines` and `total` in Variables
      expect(total, closeTo(12.35, 0.01));
    });

    test('groups quantities by prefix', () {
      final (lines, _) = checkout(items, items.length);
      expect(lines.last, contains('{A: 15, B: 5, C: 37, D: 6}'));
    });
  });

  test('a stacked discount applies both', () {
    expect(apply(const Stacked(PercentOff(10), PercentOff(50)), 100), 45);
  });

  test('a word is not a quantity', () {
    expect(parseQuantity('12'), 12);
    expect(() => parseQuantity('many'), throwsFormatException);
  });

  test('three isolates restock 480', () async {
    expect(await restock(3), 480);
  });
}
