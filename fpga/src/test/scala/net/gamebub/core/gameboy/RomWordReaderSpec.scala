package net.gamebub.core.gameboy

import net.gamebub.core.md.MdTreadle
import org.scalatest.freespec.AnyFreeSpec

/** GB-150: every answer of RomWordReader is the memory's word, for any memory
  * latency, with the cache and the prefetch on or off, across flushes.
  */
class RomWordReaderSpec extends AnyFreeSpec {
  private def word(address: Long): Long = (address * 2654435761L + 0x9e3779b9L) & 0xffffffffL

  /** Drives the module as the emulated cartridge does, against a memory with a fixed latency. */
  private class Bench(name: String, latency: Int) {
    val s = MdTreadle(new RomWordReader(23, 4), name)
    for (p <- Seq("io_cache", "io_prefetch", "io_flush", "io_reqValid", "io_reqAddress", "io_memDone", "io_memData"))
      s.poke(p, 0)
    s.poke("reset", 1); s.step(2); s.poke("reset", 0)

    private var busy = false
    private var left = 0
    private var address = 0L
    var memReads = 0
    var cycles = 0L

    /** Settle the memory's outputs for this cycle; returns nothing, pokes memDone/memData. */
    private def memory(): Unit = {
      val enable = s.peekBool("io_memEnable")
      if (!busy && enable) { busy = true; left = latency; address = s.peek("io_memAddress").toLong; memReads += 1 }
      if (busy) {
        assert(enable, "memEnable dropped before memDone")
        assert(s.peek("io_memAddress").toLong == address, "memAddress changed during a read")
      }
      val done = busy && left == 0
      s.poke("io_memDone", done)
      s.poke("io_memData", if (done) word(address) else 0x5a5a5a5aL)
    }
    def step(): Unit = {
      memory()
      val done = s.peekBool("io_memDone")
      s.step(); cycles += 1
      if (busy) { if (done) busy = false else left -= 1 }
    }
    /** One cartridge access: valid from the start cycle; if answered in the start cycle the
      * request stays one more cycle (the busy register), as in HandheldGameboy.
      */
    def read(addr: Long): Int = {
      s.poke("io_reqValid", 1); s.poke("io_reqAddress", addr)
      var waited = 0
      var first = true
      var answered = false
      while (!answered) {
        memory()
        val valid = s.peekBool("io_respValid")
        if (valid) assert(s.peek("io_respData").toLong == word(addr), f"wrong word for $addr%x after $waited cycles")
        val done = s.peekBool("io_memDone")
        s.step(); cycles += 1
        if (busy) { if (done) busy = false else left -= 1 }
        if (valid && !first) answered = true
        first = false
        waited += 1
        assert(waited < 4 * latency + 12, f"no answer for $addr%x")
      }
      s.poke("io_reqValid", 0)
      waited
    }
  }

  private def run(name: String, latency: Int, cache: Boolean, prefetch: Boolean, seed: Int): (Int, Int, Long) = {
    val b = new Bench(name, latency)
    try {
      b.s.poke("io_cache", cache); b.s.poke("io_prefetch", prefetch)
      val rng = new scala.util.Random(seed)
      var pc = 0x1000L
      var requests = 0
      for (n <- 0 until 1500) {
        // Mostly straight-line bytes, sometimes a jump, sometimes a data read elsewhere.
        val roll = rng.nextInt(100)
        if (roll < 8) pc = rng.nextInt(1 << 22).toLong
        val target = if (roll >= 8 && roll < 16) rng.nextInt(1 << 22).toLong else { val t = pc; pc += 1; t }
        b.read(target >> 2); requests += 1
        for (_ <- 0 until rng.nextInt(5)) b.step()
        if (n % 211 == 210) { b.s.poke("io_flush", 1); b.step(); b.step(); b.s.poke("io_flush", 0) }
        if (n == 700 && cache) { b.s.poke("io_cache", 0); b.step(); b.s.poke("io_cache", 1) }
      }
      (requests, b.memReads, b.cycles)
    } finally b.s.finish()
  }

  "answers are always the memory's word" in {
    for (latency <- Seq(1, 3, 6); (cache, prefetch) <- Seq((false, false), (true, false), (true, true))) {
      val (requests, reads, _) = run(s"RomWordReader_${latency}_${cache}_$prefetch", latency, cache, prefetch, 7 + latency)
      if (!cache) assert(reads == requests, "without the cache every request is one memory read")
      else assert(reads < requests || prefetch, "the cache saves reads")
    }
  }

  "the prefetch makes straight-line reads faster than the cache alone" in {
    val (_, _, cached) = run("RomWordReaderSpeedCache", 5, cache = true, prefetch = false, 3)
    val (_, _, prefetched) = run("RomWordReaderSpeedPrefetch", 5, cache = true, prefetch = true, 3)
    assert(prefetched < cached, s"cycles with prefetch $prefetched, without $cached")
  }

  "without the cache a request reads memory in its own cycle" in {
    val b = new Bench("RomWordReaderWire", 2)
    try {
      b.s.poke("io_reqValid", 1); b.s.poke("io_reqAddress", 0x123)
      b.s.expect("io_memEnable", 1); b.s.expect("io_memAddress", 0x123); b.s.expect("io_respValid", 0)
      b.s.poke("io_reqValid", 0)
      b.s.expect("io_memEnable", 0)
    } finally b.s.finish()
  }
}
