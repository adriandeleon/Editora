"""A script worth stepping through: locals, a loop, nested calls, a collection, and an exception.

Suggested breakpoints are marked with "break here". Try a conditional breakpoint (n == 15), a
logpoint, a watch on `total`, step into `classify`, and set-value on `limit` while paused.
"""
from dataclasses import dataclass


@dataclass
class Tally:
    fizz: int = 0
    buzz: int = 0
    plain: int = 0


def classify(n: int) -> str:
    if n % 15 == 0:
        return "FizzBuzz"
    if n % 3 == 0:
        return "Fizz"
    if n % 5 == 0:
        return "Buzz"
    return str(n)


def run(limit: int) -> Tally:
    tally = Tally()
    seen: dict[str, list[int]] = {}
    total = 0
    for n in range(1, limit + 1):
        label = classify(n)  # break here: step into, or make it conditional on n == 15
        seen.setdefault(label if not label.isdigit() else "plain", []).append(n)
        total += n
        if "Fizz" in label:
            tally.fizz += 1
        if "Buzz" in label:
            tally.buzz += 1
        if label.isdigit():
            tally.plain += 1
    print(f"total={total} groups={ {k: len(v) for k, v in seen.items()} }")  # break here: inspect `seen`
    return tally


def parse_limit(text: str) -> int:
    try:
        return int(text)
    except ValueError as err:
        print(f"not a number: {err}")  # break here: the exception is in `err`
        return 20


if __name__ == "__main__":
    print(run(parse_limit("twenty")))
