"""A small Python sample."""
from dataclasses import dataclass


@dataclass
class Point:
    x: int
    y: int

    def norm2(self) -> int:
        return self.x * self.x + self.y * self.y


if __name__ == "__main__":
    print(Point(3, 4).norm2())
