package net.gamebub.core.gba

import net.gamebub.core.md.MdTreadle
import org.scalatest.freespec.AnyFreeSpec

/** GB-159: every answer of GbaRomBlockCache is the memory's word, for any memory latency,
  * with back-to-back requests, and after a flush with another ROM in memory.
  */
class GbaRomBlockCacheSpec extends AnyFreeSpec {
  private val Entries = 64
  private def word(address: Long, generation: Int): Long =
    (address * 2654435761L + 0x9e3779b9L * (generation + 1)) & 0xffffffffL

  /** Drives the module as the emulated cartridge does, against a pipeline memory with a fixed latency. */
  private class Bench(name: String, val latency: Int) {
    val s = MdTreadle(new GbaRomBlockCache(25, 32, Entries), name)
    for (p <- Seq("io_flush", "io_in_enable", "io_in_address", "io_in_isWrite", "io_in_writeStrobe",
                  "io_in_dataWrite", "io_out_dataRead"))
      s.poke(p, 0)
    s.poke("io_out_ready", 1)
    s.poke("reset", 1); s.step(2); s.poke("reset", 0)

    var generation = 0
    private var busy = false
    private var left = 0
    private var address = 0L
    var memReads = 0
    var cycles = 0L

    /** Start of a cycle: the memory's outputs. */
    private def memory(): Unit = {
      if (busy) left -= 1
      val done = busy && left == 0
      s.poke("io_out_ready", !busy || done)
      s.poke("io_out_dataRead", if (done) word(address, generation) else 0x5a5a5a5aL)
    }
    /** End of a cycle: the memory takes a request made with ready high. */
    private def clock(): Unit = {
      val ready = s.peekBool("io_out_ready")
      val enable = s.peekBool("io_out_enable")
      assert(ready || !enable, "request to the memory while it is not ready")
      val next = s.peek("io_out_address").toLong
      s.step(); cycles += 1
      if (ready) busy = false
      if (ready && enable) {
        assert((next & 3) == 0, "the memory address is a word address in bytes")
        busy = true; left = latency; address = next; memReads += 1
      }
    }
    def idle(n: Int = 1): Unit = for (_ <- 0 until n) { memory(); clock() }

    /** Reads `addresses` one after another; with `pipelined` the next request is made in the
      * cycle the previous one is answered, as the cartridge does with a pending request.
      */
    def read(addresses: Seq[Long], pipelined: Boolean): Unit = {
      var waiting: Option[Long] = None          // taken, not answered
      var queue = addresses.toList
      var guard = 0
      while (waiting.nonEmpty || queue.nonEmpty) {
        memory()
        val offer = queue.nonEmpty && (waiting.isEmpty || pipelined)
        s.poke("io_in_enable", offer)
        if (offer) s.poke("io_in_address", queue.head)
        val ready = s.peekBool("io_in_ready")
        if (waiting.nonEmpty && ready) {
          val a = waiting.get
          assert(s.peek("io_in_dataRead").toLong == word(a, generation), f"wrong word for $a%x")
          waiting = None
        }
        val taken = offer && ready
        clock()
        if (taken) { waiting = Some(queue.head); queue = queue.tail }
        guard += 1
        assert(guard < (addresses.length + 2) * (3 * latency + 8) + 2 * Entries, "no answer")
      }
      s.poke("io_in_enable", 0)
    }
  }

  private def run(name: String, latency: Int, seed: Int): (Int, Int) = {
    val b = new Bench(name, latency)
    try {
      val rng = new scala.util.Random(seed)
      var pc = 0x8000L
      var requests = 0
      for (n <- 0 until 1500) {
        // Straight-line halfwords (two per 32-bit word), loops back, jumps, data reads.
        val roll = rng.nextInt(100)
        if (roll < 4) pc = rng.nextInt(1 << 23).toLong * 2
        else if (roll < 30) pc = (pc - 2 * rng.nextInt(40)).max(0)
        val burst = 1 + rng.nextInt(3)
        val list = (0 until burst).map { _ =>
          val target = if (roll >= 90) rng.nextInt(1 << 23).toLong * 2 else { val t = pc; pc += 2; t }
          (target >> 2) << 2
        }
        b.read(list, pipelined = rng.nextBoolean()); requests += burst
        b.idle(rng.nextInt(6))
        if (n % 197 == 196) {
          // Core reset: the flush is held while the host loads another ROM.
          b.s.poke("io_flush", 1); b.idle(latency + 2)
          b.generation += 1
          b.idle(2); b.s.poke("io_flush", 0)
        }
      }
      (requests, b.memReads)
    } finally b.s.finish()
  }

  "answers are always the memory's word, and loops are answered from the cache" in {
    for (latency <- Seq(1, 2, 5, 9)) {
      val (requests, reads) = run(s"GbaRomBlockCache_$latency", latency, 11 + latency)
      assert(reads < requests * 2 / 3, s"$reads memory reads for $requests requests")
    }
  }

  "a kept word answers on the cycle after the request, without a memory read" in {
    val b = new Bench("GbaRomBlockCacheHit", 4)
    try {
      b.idle(Entries + 4)                          // the cache empties itself after reset
      b.read(Seq(0x100L, 0x104L), pipelined = false)
      val before = b.memReads
      val start = b.cycles
      b.read(Seq(0x100L), pipelined = false)
      assert(b.cycles - start == 2, "request cycle + answer cycle")
      b.read(Seq(0x104L, 0x100L), pipelined = true)
      assert(b.memReads == before, "nothing went to memory")
    } finally b.s.finish()
  }
}
