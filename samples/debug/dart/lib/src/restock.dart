// The restock: three named isolates (restock-1…3) that each count up their share, for the Threads view.
import 'dart:isolate';

/// Runs [workers] isolates and adds up what they counted: each one is a row of the Threads view while it runs.
Future<int> restock(int workers) async {
  final counts = await Future.wait([
    for (var w = 1; w <= workers; w++) Isolate.run(() => _count(160), debugName: 'restock-$w'),
  ]);
  return counts.fold<int>(0, (sum, n) => sum + n);
}

int _count(int times) {
  var restocked = 0;
  for (var n = 1; n <= times; n++) {
    restocked++; // break here: stops in each isolate (Threads lists restock-1…3), or a condition such as n == 100
  }
  return restocked;
}
