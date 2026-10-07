"""Inventory helpers (the "after" side). Compare With… inventory-before.py."""

import csv

MAX_ITEMS = 128


def load(path):
    items = []
    with open(path, newline="") as handle:
        for name, weight in csv.reader(handle):
            items.append({"name": name, "weight": float(weight)})
    return items


def total_weight(items):
    return sum(item["weight"] for item in items)


def heaviest(items):
    best = None
    for item in items:
        if best is None or item["weight"] > best["weight"]:
                best = item
    return best


def describe(item):
    return "%s weighs %.1f kg" % (item["name"], item["weight"])


def report(items, heading="Inventory"):
    lines = [heading]
    for item in items:
        lines.append(describe(item))
    lines.append("total: %s kg" % total_weight(items))
    return "\n".join(lines)


if __name__ == "__main__":
    print(report(load("items.csv")))
