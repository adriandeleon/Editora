local Point = {}
Point.__index = Point

function Point.new(x, y)
  return setmetatable({ x = x, y = y }, Point)
end

function Point:norm2()
  return self.x * self.x + self.y * self.y
end

print(Point.new(3, 4):norm2())
