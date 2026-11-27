// The shelf, in Dart: open this file and press F5 (Debug) to debug it on the SDK's own debugger.
// Every suggested spot is a "break here" comment. `dart run` runs it outside Editora, `dart test` runs the
// tests, and the `crash` program argument throws an error nobody catches, which stops the debugger there.
import 'package:shelf_sample/shelf_sample.dart';

Future<void> main(List<String> args) async {
  var limit = 6; // break here: a first plain breakpoint; Step Over, then Step Into checkout
  if (args.isNotEmpty) {
    if (args.first == 'crash') {
      final missing = <String, int>{};
      print(missing['boom']!); // a TypeError nobody catches: the debugger stops here by itself
    }
    try {
      limit = parseQuantity(args.first);
    } on FormatException catch (e) {
      print(e.message);
    }
  }
  final (lines, total) = checkout(items, limit);
  lines.forEach(print);
  print('total ${total.toStringAsFixed(2)}');
  print('restocked ${await restock(3)}');
}
