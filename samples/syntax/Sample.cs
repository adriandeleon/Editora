using System;

namespace Demo;

public record Point(int X, int Y)
{
    public int Norm2() => X * X + Y * Y;
}

public static class Program
{
    public static void Main() => Console.WriteLine(new Point(3, 4).Norm2());
}
