/* The header half of a header/implementation pair. Run "Go: Related File" to jump to geometry.c. */
#ifndef GEOMETRY_H
#define GEOMETRY_H

typedef struct {
    double x;
    double y;
} point;

/* The squared distance from the origin. */
double point_norm2(point p);

/* The distance between two points. */
double point_distance(point a, point b);

#endif /* GEOMETRY_H */
