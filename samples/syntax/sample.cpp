// A small C++ sample for syntax highlighting and folding.
// Covers namespaces, a class hierarchy, templates with a concept, smart
// pointers, a lambda, range-for, structured bindings, and raw strings.
#include <algorithm>
#include <concepts>
#include <iostream>
#include <map>
#include <memory>
#include <string>
#include <vector>

namespace shelf {

constexpr int kMaxItems = 0x40;

/** Anything that can describe itself. */
class Item {
public:
    explicit Item(std::string name) : name_(std::move(name)) {}
    virtual ~Item() = default;

    [[nodiscard]] virtual std::string describe() const = 0;
    [[nodiscard]] const std::string& name() const noexcept { return name_; }

private:
    std::string name_;
};

class Book final : public Item {
public:
    Book(std::string title, int pages) : Item(std::move(title)), pages_(pages) {}

    std::string describe() const override {
        return "a book: \"" + name() + "\" (" + std::to_string(pages_) + " pages)";
    }

private:
    int pages_;
};

template <typename T>
concept Summable = requires(T a, T b) {
    { a + b } -> std::convertible_to<T>;
};

template <Summable T>
T sum(const std::vector<T>& xs) {
    T total{};
    for (const auto& x : xs) total += x;
    return total;
}

}  // namespace shelf

int main() {
    std::vector<std::unique_ptr<shelf::Item>> items;
    items.push_back(std::make_unique<shelf::Book>("Dune", 612));
    items.push_back(std::make_unique<shelf::Book>("Emma", 474));

    /* Sort by name with a lambda. */
    std::sort(items.begin(), items.end(),
              [](const auto& a, const auto& b) { return a->name() < b->name(); });

    std::map<char, int> initials;
    for (const auto& item : items) {
        ++initials[item->name().front()];
        std::cout << item->describe() << '\n';
    }
    for (const auto& [letter, count] : initials) {
        std::cout << letter << ": " << count << "\n";
    }
    std::cout << R"(raw \n string)" << ' ' << shelf::sum<int>({1, 2, 3}) << std::endl;
    return static_cast<int>(items.size()) > shelf::kMaxItems;
}
