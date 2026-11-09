/*
 * A lone C file: open it and press F5 (Debug). Editora compiles it with `cc -g -O0` (no project needed), then
 * CodeLLDB runs it. Every suggested spot is a "break here" comment.
 */
#include <stdio.h>
#include <string.h>

struct item {
    const char *name;
    int quantity;
    double price;
};

static double subtotal(const struct item *item) {
    return item->quantity * item->price; // break here: Step Out returns to the loop
}

int main(void) {
    struct item items[] = {{"pencil", 12, 0.40}, {"tape", 4, 1.20}, {"glue", 6, 2.10}};
    const char *title = "tally"; // break here: a first plain breakpoint
    double total = 0;
    for (size_t i = 0; i < sizeof items / sizeof items[0]; i++) {
        total += subtotal(&items[i]); // break here: Step Into, a watch on `total`
    }
    printf("%s: %zu items, %.2f\n", title, strlen(title), total);
    return 0;
}
