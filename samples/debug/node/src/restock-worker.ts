// One restock worker, started by restock.ts under a name of its own (restock-1…3): the Threads view lists each.
import { parentPort, workerData } from "node:worker_threads";

const rounds: number = workerData;
let restocked = 0;
for (let round = 0; round < rounds; round++) {
  restocked += 1; // break here: a hit-count breakpoint (this line runs 160 times in each worker)
}
parentPort?.postMessage(restocked);
