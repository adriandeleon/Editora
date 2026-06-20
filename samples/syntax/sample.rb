# frozen_string_literal: true

Point = Struct.new(:x, :y) do
  def norm2
    x * x + y * y
  end
end

puts Point.new(3, 4).norm2
