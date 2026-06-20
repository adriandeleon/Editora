<?php

final class Point
{
    public function __construct(
        private int $x,
        private int $y,
    ) {}

    public function norm2(): int
    {
        return $this->x ** 2 + $this->y ** 2;
    }
}

echo (new Point(3, 4))->norm2(), PHP_EOL;
