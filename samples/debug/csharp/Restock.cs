namespace Shelf;

public static class Restock
{
    /// <summary>Runs <paramref name="workers"/> threads that each count up their share: the Threads view lists them.</summary>
    public static int Run(int workers)
    {
        int restocked = 0;
        var gate = new object();
        var threads = Enumerable.Range(1, workers)
            .Select(w => new Thread(() =>
            {
                for (int i = 0; i < 160; i++)
                {
                    lock (gate)
                    {
                        restocked++; // break here: a hit-count breakpoint (this line runs 480 times), and each thread in Threads
                    }
                }
            }) { Name = $"restock-{w}" })
            .ToList();
        threads.ForEach(t => t.Start());
        threads.ForEach(t => t.Join());
        return restocked;
    }
}
