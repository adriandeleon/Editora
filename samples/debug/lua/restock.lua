-- The restock, required by shelf.lua: three coroutines (Lua has no threads) taking turns, each counting its share.
local restock = {}

-- One worker: counts 160 units, yielding after each so the next worker gets a turn.
local function worker(name, tally)
  for n = 1, 160 do
    tally[name] = (tally[name] or 0) + 1 -- break here: runs 480 times; a condition, n == 100 and name == "restock-2", stops in that coroutine
    coroutine.yield(n)
  end
end

-- Resumes the workers round-robin until every one has finished; returns how many units they counted.
function restock.run(workers)
  local tally, running = {}, {}
  for w = 1, workers do
    running[#running + 1] = coroutine.create(function() worker("restock-" .. w, tally) end)
  end
  while #running > 0 do
    for i = #running, 1, -1 do
      local co = running[i]
      assert(coroutine.resume(co)) -- break here: `co` is the coroutine about to run; Threads lists each as "thread: 0x…"
      if coroutine.status(co) == "dead" then
        table.remove(running, i)
      end
    end
  end
  local restocked = 0
  for _, count in pairs(tally) do
    restocked = restocked + count
  end
  return restocked
end

return restock
