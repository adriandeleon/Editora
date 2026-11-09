#include "shelf.hpp"

// An item's discount: a stacked one for a big quantity.
std::unique_ptr<Discount> discount_for(const Item &item) {
    if (item.quantity >= 10) {
        auto stacked = std::make_unique<Discount>(Discount{10.0, nullptr}); // break here: hit once per big item
        stacked->then = std::make_unique<Discount>(Discount{5.0, nullptr});
        return stacked;
    }
    if (item.price > 5.0) {
        return std::make_unique<Discount>(Discount{15.0, nullptr});
    }
    return nullptr;
}

// Recurses through a stacked discount: the call stack shows two apply frames.
double apply(const Discount *discount, double price) {
    if (discount == nullptr) {
        return price;
    }
    double off = price * (100.0 - discount->percent) / 100.0; // break here: Step Out, or look at the frames below
    return apply(discount->then.get(), off);
}
