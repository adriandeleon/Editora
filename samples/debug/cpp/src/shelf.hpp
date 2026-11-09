#pragma once

#include <map>
#include <memory>
#include <string>
#include <vector>

/// What is on the shelf; expand one in Variables to see its fields.
struct Item {
    std::string sku;
    std::string name;
    int quantity;
    double price;
};

/// A percentage off, possibly stacked on another discount.
struct Discount {
    double percent;
    std::unique_ptr<Discount> then;
};

std::vector<Item> items();
std::vector<std::string> checkout(const std::vector<Item> &shelf, std::size_t limit, double &total);
int parse_quantity(const std::string &text);
std::unique_ptr<Discount> discount_for(const Item &item);
double apply(const Discount *discount, double price);
int restock(int workers);
