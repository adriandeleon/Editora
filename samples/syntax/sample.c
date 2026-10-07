/*
 * A small C sample for syntax highlighting and folding.
 * Covers the preprocessor, a struct, an enum, a union, pointers, a function
 * pointer, a switch, and string/char escapes.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define MAX_ITEMS 0x40
#define ARRAY_LEN(a) (sizeof(a) / sizeof((a)[0]))

typedef enum { ITEM_BOOK, ITEM_TOOL } item_kind;

typedef struct {
    item_kind kind;
    const char *name;
    union {
        int pages;        /* ITEM_BOOK */
        double weight_kg; /* ITEM_TOOL */
    } as;
} item;

typedef int (*item_cmp)(const void *, const void *);

static int by_name(const void *a, const void *b) {
    return strcmp(((const item *)a)->name, ((const item *)b)->name);
}

// A line comment: prints one item, returning the characters written.
static int describe(const item *it) {
    switch (it->kind) {
    case ITEM_BOOK:
        return printf("a book: \"%s\" (%d pages)\n", it->name, it->as.pages);
    case ITEM_TOOL:
        return printf("a tool: %s, %.1f kg\n", it->name, it->as.weight_kg);
    default:
        return 0;
    }
}

int main(void) {
    item shelf[] = {
        {ITEM_TOOL, "hammer", {.weight_kg = 0.6}},
        {ITEM_BOOK, "Dune", {.pages = 612}},
    };
    item_cmp cmp = by_name;
    size_t n = ARRAY_LEN(shelf);

    if (n > MAX_ITEMS) {
        fputs("shelf is full\n", stderr);
        return EXIT_FAILURE;
    }
    qsort(shelf, n, sizeof shelf[0], cmp);
    for (size_t i = 0; i < n; i++) {
        describe(&shelf[i]);
    }
    putchar('\n');
    return EXIT_SUCCESS;
}
