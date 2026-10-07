/* The implementation half of the pair. Run "Go: Related File" to jump back to geometry.h; with a
 * C language server running, Go to Definition on point_norm2 in the header lands here too. */
#include "geometry.h"

#include <math.h>

double point_norm2(point p) {
    return p.x * p.x + p.y * p.y;
}

double point_distance(point a, point b) {
    point delta = {a.x - b.x, a.y - b.y};
    return sqrt(point_norm2(delta));
}
