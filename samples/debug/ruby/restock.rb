module Restock
  # Runs workers threads that each count up their share: the Threads view lists them.
  def self.run(workers)
    restocked = 0
    gate = Mutex.new
    threads = (1..workers).map do |w|
      Thread.new do
        Thread.current.name = "restock-#{w}"
        160.times do
          gate.synchronize do
            restocked += 1 # break here: a hit-count breakpoint (this line runs 480 times), and each thread in Threads
          end
        end
      end
    end
    threads.each(&:join)
    restocked
  end
end
