#include <algorithm>
#include <cstdio>

#include "shelf.hpp"

std::vector<Item> items() {
    return {
        {"A-1", "Pencil", 12, 0.40}, {"A-2", "Notebook", 3, 2.75}, {"B-1", "Stapler", 1, 8.90},
        {"B-2", "Tape", 4, 1.20},    {"C-1", "Marker", 10, 1.05},  {"C-2", "Eraser", 25, 0.30},
        {"C-3", "Ruler", 2, 1.50},   {"D-1", "Glue", 6, 2.10},
    };
}

// Totals up to `limit` items: `total`, `lines` and `by_prefix` change on every pass.
std::vector<std::string> checkout(const std::vector<Item> &shelf, std::size_t limit, double &total) {
    std::vector<std::string> lines;
    std::map<std::string, int> by_prefix;
    for (std::size_t i = 0; i < shelf.size() && i < limit; i++) {
        const Item &item = shelf[i];
        auto discount = discount_for(item); // break here: Step Into, then look at `discount` per item
        double price = apply(discount.get(), item.price);
        double subtotal = price * item.quantity;
        char line[96];
        std::snprintf(line, sizeof line, "%-4s %-9s %3d x %5.2f = %7.2f", item.sku.c_str(), item.name.c_str(),
                      item.quantity, price, subtotal);
        lines.emplace_back(line);
        by_prefix[item.sku.substr(0, 1)] += item.quantity;
        total += subtotal; // break here: a watch on `total`, or a logpoint printing {item.name}
    }
    std::vector<std::string> cheap;
    for (const Item &item : shelf) {
        if (item.price < 1.0) { // break here: a conditional breakpoint, item.sku == "C-2"
            cheap.push_back(item.name);
        }
    }
    std::sort(cheap.begin(), cheap.end());
    lines.push_back("cheap: " + std::to_string(cheap.size()) + " items, " + std::to_string(by_prefix.size()) +
                    " prefixes");
    return lines;
}

// "12" is 12; a word throws std::invalid_argument, which main catches.
int parse_quantity(const std::string &text) {
    return std::stoi(text); // break here: Step Into std::stoi throws for a word
}
