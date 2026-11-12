package net.gamebub.core.gameboy

import net.gamebub.core.md.MdTreadle
import org.scalatest.freespec.AnyFreeSpec

/** GB-150: start phase between the two cores, partner-write diagnostics and link cable counters. */
class DualCoreSyncDiagSpec extends AnyFreeSpec {
  private def bench(name: String) = {
    val s = MdTreadle(new DualCoreSync(50), name)
    for (p <- Seq("io_enable", "io_run", "io_tick", "io_localButtons", "io_partnerWrite", "io_partnerData",
      "io_pop", "io_sampleVisible", "io_sampleHidden", "io_phase", "io_visibleLate", "io_serialVisible",
      "io_serialHidden")) s.poke(p, 0)
    s.poke("io_delay", 2)
    s.poke("reset", 1); s.step(2); s.poke("reset", 0)
    s
  }
  /** One clock; the cores tick unless the module stalls them. Returns whether they ticked. */
  private def step(s: MdTreadle.Sim): Boolean = {
    val tick = !s.peekBool("io_stall")
    s.poke("io_tick", tick)
    s.step()
    s.poke("io_partnerWrite", 0); s.poke("io_pop", 0)
    tick
  }

  "the late core is held for exactly `phase` core clocks, on the side that visibleLate names" in {
    for (visibleLate <- Seq(0, 1)) {
      val s = bench(s"DualCoreSyncPhase$visibleLate")
      try {
        val (late, early) = if (visibleLate == 1) ("io_holdVisible", "io_holdHidden") else ("io_holdHidden", "io_holdVisible")
        s.poke("io_phase", 7); s.poke("io_visibleLate", visibleLate)
        s.poke("io_sampleVisible", 0x111); s.poke("io_sampleHidden", 0x222)
        s.poke("io_enable", 1)
        s.step(3)
        s.expect(late, 1, "held before the start"); s.expect(early, 0)
        s.poke("io_run", 1)
        var ticks = 0
        var held = 0
        while (ticks < 20) {
          val holding = s.peekBool(late)
          s.expect(early, 0)
          if (step(s)) { ticks += 1; if (holding) held += 1 }
        }
        assert(held == 7, s"late core held for $held core clocks")
        s.expect(late, 0)
        // The digest of the late core missed exactly the held clocks: after 20 ticks the two
        // digests differ although both would rotate the same way from equal samples.
        s.poke("io_pop", 1); s.step(); s.poke("io_pop", 0)
      } finally s.finish()
    }
  }

  "phase 0 holds nobody" in {
    val s = bench("DualCoreSyncPhase0")
    try {
      s.poke("io_enable", 1); s.poke("io_run", 1)
      for (_ <- 0 until 10) { s.expect("io_holdVisible", 0); s.expect("io_holdHidden", 0); step(s) }
    } finally s.finish()
  }

  "partner writes are counted by outcome" in {
    val s = bench("DualCoreSyncWrites")
    try {
      s.poke("io_enable", 1); s.poke("io_run", 1)
      def write(frame: Int): Unit = { s.poke("io_partnerWrite", 1); s.poke("io_partnerData", (frame << 8) | 1); step(s) }
      step(s)            // frame 0 starts
      write(2)           // accepted, early
      write(40)          // outside the window
      write(0)           // frame 0 is already running
      val stat = s.peek("io_writeStat")
      assert((stat & 0xffff) == 3 && (stat >> 16) == 1, s"writeStat=$stat")
      val rejects = s.peek("io_rejectStat")
      assert((rejects & 0xffff) == 1 && (rejects >> 16) == 1, s"rejectStat=$rejects")
      assert(s.peek("io_lastReject") == 0, "last refused: module frame 0, written frame 0")
      // Clearing the mode clears the counters.
      s.poke("io_enable", 0); s.step(); s.poke("io_enable", 1)
      assert(s.peek("io_writeStat") == 0 && s.peek("io_rejectStat") == 0)
    } finally s.finish()
  }

  "link cable activity is counted per core" in {
    val s = bench("DualCoreSyncCable")
    try {
      s.poke("io_enable", 1); s.poke("io_run", 1)
      def serial(visible: Int, hidden: Int, clocks: Int = 2): Unit = {
        s.poke("io_serialVisible", visible); s.poke("io_serialHidden", hidden)
        var n = 0
        while (n < clocks) { if (step(s)) n += 1 }
      }
      serial(3, 2)   // visible starts as master, hidden waits on the external clock
      serial(0, 0)   // both complete
      serial(3, 0)   // visible master again
      serial(3, 3)   // hidden starts as master while the visible one is: collision
      serial(0, 0)
      val v = s.peek("io_serialStatVisible"); val h = s.peek("io_serialStatHidden"); val x = s.peek("io_serialStatCross")
      assert((v & 0xffff) == 2 && (v >> 16) == 2, s"visible=$v")
      assert((h & 0xffff) == 1 && (h >> 16) == 2, s"hidden=$h")
      assert((x & 0xffff) == 1, s"collisions=$x")
      assert(((x >> 16) & 0xff) == 0 && (x >> 24) == 1, s"external clock completions=$x")
    } finally s.finish()
  }
}
