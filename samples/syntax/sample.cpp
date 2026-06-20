#include <iostream>
#include <vector>

template <typename T>
T sum(const std::vector<T>& xs) {
    T total{};
    for (const auto& x : xs) total += x;
    return total;
}

int main() {
    std::cout << sum<int>({1, 2, 3}) << "\n";
}
