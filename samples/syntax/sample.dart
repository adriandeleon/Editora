// A small Dart sample for syntax highlighting, folding and the outline.
//
// It covers a sealed class with a switch expression, a mixin, an enum with
// fields, an extension, a typedef, named and factory constructors, getters and
// setters, async/await with a Stream, records, string interpolation, raw and
// multi-line strings, and doc comments.
import 'dart:async';
import 'dart:math' as math;

/// The most items a [Shelf] holds.
const maxItems = 0x40; // hex literal

typedef Describer = String Function(Item item);

/// Anything that can describe itself.
sealed class Item {
  const Item(this.title);

  final String title;

  String describe();
}

class Book extends Item {
  const Book(super.title, {required this.pages});

  /* A block comment
     over two lines. */
  final int pages;

  @override
  String describe() => 'a book: "$title" ($pages pages)';
}

class Album extends Item with Loud {
  Album(super.title, this.minutes);

  Album.single(String title) : this(title, 4);

  factory Album.parse(String line) {
    final parts = line.split(r'\t');
    return Album(parts.first, int.tryParse(parts.last) ?? 0);
  }

  final int minutes;

  @override
  String describe() => shout('an album: ${title.toUpperCase()}, $minutes min');
}

mixin Loud {
  String shout(String text) => '$text!';
}

enum Size {
  small(1),
  large(3.5e1);

  const Size(this.weight);
  final num weight;
}

extension Totals on List<Item> {
  int get pages => whereType<Book>().fold(0, (sum, b) => sum + b.pages);
}

class Shelf {
  final _items = <Item>[];
  var _label = 'shelf';

  String get label => _label;
  set label(String value) => _label = value.trim();

  void add(Item item) {
    if (_items.length >= maxItems) {
      throw StateError('shelf is full');
    }
    _items.add(item);
  }

  (int, double) stats() => (_items.length, math.sqrt(_items.length));

  Stream<String> titles() async* {
    for (final item in _items) {
      yield switch (item) {
        Book(pages: > 300) => '${item.title} (long)',
        Book() || Album() => item.title,
      };
    }
  }
}

Future<void> main() async {
  final shelf = Shelf()
    ..add(const Book('Dune', pages: 412))
    ..add(Album.single('Blue'));
  final report = '''
Shelf ${shelf.label}:
  pages\t${shelf._items.pages}''';
  print(report);
  await for (final title in shelf.titles()) {
    print(title);
  }
}
