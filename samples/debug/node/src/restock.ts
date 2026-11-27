// The restock: three named worker threads (restock-1…3), each counting up its share in restock-worker.ts.
import { Worker } from "node:worker_threads";

/** Starts one worker per name and waits for all of their counts. */
export async function restock(workers: number, rounds: number): Promise<number> {
  const names = Array.from({ length: workers }, (_, i) => `restock-${i + 1}`);
  const counts = await Promise.all(names.map((name) => runWorker(name, rounds)));
  return counts.reduce((sum, count) => sum + count, 0); // break here: past the await, main.ts's await shows below
  // an async boundary in the call stack
}

/** One worker thread named `name`; its count comes back as a message. */
function runWorker(name: string, rounds: number): Promise<number> {
  return new Promise((resolve, reject) => {
    const worker = new Worker(new URL("./restock-worker.ts", import.meta.url), { name, workerData: rounds });
    worker.once("message", (count: number) => resolve(count));
    worker.once("error", reject);
  });
}
