"""Inventory helpers (the "before" side). Compare With… inventory-after.py."""

MAX_ITEMS = 64


def total_weight(items):
    total = 0
    for item in items:
        total += item["weight"]
    return total


def heaviest(items):
    best = None
    for item in items:
        if best is None or item["weight"] > best["weight"]:
            best = item
    return best


def describe(item):
    return "%s weighs %s kg" % (item["name"], item["weight"])


def load(path):
    items = []
    with open(path) as handle:
        for line in handle:
            name, weight = line.strip().split(",")
            items.append({"name": name, "weight": float(weight)})
    return items


def report(items):
    lines = []
    for item in items:
        lines.append(describe(item))
    lines.append("total: %s kg" % total_weight(items))
    return "\n".join(lines)


if __name__ == "__main__":
    print(report(load("items.csv")))
