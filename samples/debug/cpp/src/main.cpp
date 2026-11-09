#include <iostream>
#include <stdexcept>

#include "shelf.hpp"

int main(int argc, char **argv) {
    std::size_t limit = 6; // break here: a first plain breakpoint; Step Over, then Step Into checkout
    std::string argument = argc > 1 ? argv[1] : "";
    if (argument == "crash") {
        throw std::runtime_error("crash asked for"); // a throw: the debugger stops here (C++: on throw)
    }
    try {
        limit = static_cast<std::size_t>(parse_quantity(argument.empty() ? "6" : argument));
    } catch (const std::invalid_argument &err) {
        std::cout << "not a number: " << argument << std::endl; // break here: a caught exception
    }
    double total = 0;
    for (const std::string &line : checkout(items(), limit, total)) {
        std::cout << line << '\n';
    }
    std::cout << "total " << total << '\n';
    std::cout << "restocked " << restock(3) << std::endl;
    return 0;
}
