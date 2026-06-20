#include <stdio.h>

int norm2(int x, int y) {
    return x * x + y * y;
}

int main(void) {
    printf("%d\n", norm2(3, 4));
    return 0;
}
