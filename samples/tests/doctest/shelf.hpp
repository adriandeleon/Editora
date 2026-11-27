// The shelf logic the tests in shelf_test.cpp check: an item's subtotal, a percentage off, a quantity read
// from text. Header-only, so the test program is this header and one test file.
#pragma once

#include <stdexcept>
#include <string>

/// What is on the shelf.
struct Item {
    std::string sku;
    std::string name;
    int quantity;
    double price;
};

/// What a line of the receipt costs: quantity times price.
inline double subtotal(const Item &item) {
    return item.quantity * item.price;
}

/// `price` with `percent` taken off, for any number type (cents as int, dollars as double).
template <typename T>
T discounted(T price, int percent) {
    return price * (100 - percent) / 100;
}

/// A quantity typed by hand; throws std::invalid_argument for anything that isn't a whole number >= 0.
inline int parse_quantity(const std::string &text) {
    std::size_t used = 0;
    int n = std::stoi(text, &used); // throws std::invalid_argument for "lots"
    if (used != text.size() || n < 0) {
        throw std::invalid_argument("not a quantity: " + text);
    }
    return n;
}
