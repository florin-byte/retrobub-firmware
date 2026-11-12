package net.gamebub.core.gameboy

import net.gamebub.core.md.MdTreadle
import org.scalatest.freespec.AnyFreeSpec

/** GB-145: frame-locked button exchange, with a 5-clock frame. */
class DualCoreSyncSpec extends AnyFreeSpec {
  private val FrameCycles = 5
  private val Delay = 2

  private class Bench(name: String) {
    val s = MdTreadle(new DualCoreSync(FrameCycles), name)
    var digestV = 0L
    var digestH = 0L
    var ticks = 0L
    private def rot(x: Long): Long = ((x << 5) | (x >>> 27)) & 0xffffffffL
    def sampleV: Long = (0x1000 + ticks) & 0xffffffffL
    def sampleH: Long = (0xabc00000L ^ (ticks * 7)) & 0xffffffffL

    for (p <- Seq("io_enable", "io_run", "io_tick", "io_localButtons", "io_partnerWrite",
      "io_partnerData", "io_pop", "io_sampleVisible", "io_sampleHidden")) s.poke(p, 0)
    s.poke("io_delay", Delay)
    s.poke("reset", 1); s.step(2); s.poke("reset", 0)

    /** One system clock; the cores advance unless stalled (or `gate` holds them). */
    def step(gate: Boolean = false): Unit = {
      val tick = !s.peekBool("io_stall") && !gate
      s.poke("io_tick", tick)
      s.poke("io_sampleVisible", sampleV); s.poke("io_sampleHidden", sampleH)
      if (tick) { digestV = rot(digestV) ^ sampleV; digestH = rot(digestH) ^ sampleH; ticks += 1 }
      s.step()
      s.poke("io_partnerWrite", 0); s.poke("io_pop", 0)
    }
    def steps(n: Int): Unit = for (_ <- 0 until n) step()
    def partner(frame: Int, buttons: Int): Unit = {
      s.poke("io_partnerWrite", 1); s.poke("io_partnerData", ((frame & 0xffff) << 8) | buttons)
      step()
    }
    def pop(): Unit = { s.poke("io_pop", 1); step() }
    def status: BigInt = s.peek("io_status")
    def frame: Int = (status & 0xffff).toInt
    def count: Int = ((status >> 16) & 15).toInt
    def waitingPartner: Boolean = ((status >> 21) & 1) == 1
    def full: Boolean = ((status >> 22) & 1) == 1
    def local: (Boolean, Int, Int) = {
      val v = s.peek("io_local")
      (((v >> 31) & 1) == 1, ((v >> 8) & 0xffff).toInt, (v & 0xff).toInt)
    }
    /** Run until `frames` frames have started or `limit` clocks pass; feeds partner buttons. */
    def runTo(target: Int, partnerButtons: Int => Int, limit: Int = 4000): Unit = {
      var n = 0
      while (frame != (target & 0xffff) && n < limit) {
        if (waitingPartner) partner(frame, partnerButtons(frame))
        else if (count != 0) pop()
        else step()
        n += 1
      }
      assert(frame == (target & 0xffff), s"did not reach frame $target (at $frame)")
    }
  }

  "disabled: no stall, no request, state stays clear" in {
    val b = new Bench("DualCoreSyncOff")
    try {
      b.steps(20)
      b.s.expect("io_stall", 0); b.s.expect("io_request", 0); b.s.expect("io_status", 0x100000)
      b.partner(0, 0xff); b.steps(3)
      b.s.expect("io_hiddenButtons", 0)
    } finally b.s.finish()
  }

  "hold, start, delay, stall on missing partner buttons, digests" in {
    val b = new Bench("DualCoreSyncRun")
    try {
      b.s.poke("io_enable", 1)
      b.steps(4)
      b.s.expect("io_stall", 1, "held until run")
      assert(b.ticks == 0)
      // Partner buttons may arrive before the local run.
      b.partner(2, 0x5a)
      b.s.poke("io_run", 1)
      b.s.poke("io_localButtons", 0x11)
      b.step() // frame 0 boundary: nothing to wait for
      b.s.expect("io_stall", 0)
      b.s.expect("io_request", 1)
      assert(b.local == (true, 2, 0x11))
      b.s.expect("io_digestVisible", 0); b.s.expect("io_digestHidden", 0)
      b.s.expect("io_visibleButtons", 0); b.s.expect("io_hiddenButtons", 0)
      b.pop()
      b.s.expect("io_request", 0)
      b.s.poke("io_localButtons", 0x22)
      while (b.frame == 0) b.step()
      val (dv1, dh1) = (b.digestV, b.digestH)
      assert(b.ticks == FrameCycles)
      b.step() // frame 1 boundary
      assert(b.local == (true, 3, 0x22))
      b.s.expect("io_digestVisible", dv1); b.s.expect("io_digestHidden", dh1)
      b.pop()
      b.s.poke("io_localButtons", 0x33)
      while (b.frame == 1) b.step()
      b.step() // frame 2 boundary: partner buttons were written before run
      b.s.expect("io_stall", 0)
      b.s.expect("io_visibleButtons", 0x11); b.s.expect("io_hiddenButtons", 0x5a)
      assert(b.local == (true, 4, 0x33))
      b.pop()
      while (b.frame == 2) b.step()
      // Frame 3: partner buttons missing. A random gate must not matter; a resend of frame 2 is ignored.
      val ticksAt3 = b.ticks
      b.steps(7)
      b.partner(2, 0x77)
      b.s.expect("io_stall", 1)
      assert(b.waitingPartner && b.ticks == ticksAt3)
      assert(b.s.peek("io_waitCycles") >= 7)
      b.partner(3, 0x0f)
      b.step()
      b.s.expect("io_stall", 0)
      b.s.expect("io_visibleButtons", 0x22); b.s.expect("io_hiddenButtons", 0x0f)
      // A resend of the frame in progress must not survive into frame 3 + 16.
      b.partner(3, 0xee)
      b.pop()
      b.runTo(19, f => f & 0xff)
      b.steps(4)
      assert(b.waitingPartner, "stale slot used for frame 19")
      b.partner(19, 0x13); b.step()
      b.s.expect("io_hiddenButtons", 0x13)
    } finally b.s.finish()
  }

  "a full local queue stops the cores until the host pops" in {
    val b = new Bench("DualCoreSyncFull")
    try {
      b.s.poke("io_enable", 1); b.s.poke("io_run", 1)
      var n = 0
      while (!b.full && n < 2000) {
        if (b.waitingPartner) b.partner(b.frame, 1) else b.step()
        n += 1
      }
      assert(b.full && b.count == 8)
      val at = b.frame
      while (b.frame == at) b.step()
      if (b.waitingPartner) b.partner(b.frame, 1)
      val ticks = b.ticks
      b.steps(10)
      assert(b.ticks == ticks, "cores ran with a full queue")
      assert(b.local._2 == Delay, "oldest sample lost")
      b.pop(); b.step()
      b.s.expect("io_stall", 0)
      assert(b.count == 8)
    } finally b.s.finish()
  }

  "frame counter wrap keeps waiting for partner buttons" in {
    val s = MdTreadle(new DualCoreSync(2), "DualCoreSyncWrap")
    try {
      for (p <- Seq("io_tick", "io_localButtons", "io_partnerWrite", "io_partnerData", "io_pop",
        "io_sampleVisible", "io_sampleHidden")) s.poke(p, 0)
      s.poke("io_delay", Delay); s.poke("io_enable", 1); s.poke("io_run", 1)
      s.poke("reset", 1); s.step(2); s.poke("reset", 0)
      def frame: Int = (s.peek("io_status") & 0xffff).toInt
      var guard = 0
      while (frame != 1 || guard < 65536) {
        val stall = s.peekBool("io_stall")
        val waiting = ((s.peek("io_status") >> 21) & 1) == 1
        s.poke("io_tick", !stall)
        s.poke("io_partnerWrite", waiting); s.poke("io_partnerData", (frame << 8) | 0x44)
        s.poke("io_pop", 1)
        s.step()
        guard += 1
        assert(guard < 400000, "no wrap")
      }
      // Frame 1 after the wrap is below `delay` again, but must still wait.
      s.poke("io_partnerWrite", 0); s.poke("io_tick", 0); s.step(3)
      s.expect("io_stall", 1)
      assert(((s.peek("io_status") >> 21) & 1) == 1)
    } finally s.finish()
  }

  "ten buttons and a GBA frame (GB-156): the frame sits above the buttons" in {
    val Gba = 280896
    val s = MdTreadle(new DualCoreSync(Gba, 10), "DualCoreSyncGba")
    try {
      for (p <- Seq("io_tick", "io_localButtons", "io_partnerWrite", "io_partnerData", "io_pop",
        "io_sampleVisible", "io_sampleHidden", "io_run")) s.poke(p, 0)
      s.poke("io_delay", 1); s.poke("io_enable", 1)
      s.poke("reset", 1); s.step(2); s.poke("reset", 0)
      // Partner buttons for frame 1, with both bits above the GB eight set.
      s.poke("io_partnerWrite", 1); s.poke("io_partnerData", (1 << 10) | 0x2a5); s.step()
      s.poke("io_partnerWrite", 0)
      s.poke("io_run", 1); s.poke("io_localButtons", 0x315)
      s.step() // frame 0 boundary
      s.expect("io_stall", 0)
      s.expect("io_local", (BigInt(1) << 31) | (1 << 10) | 0x315)
      s.poke("io_pop", 1); s.step(); s.poke("io_pop", 0)
      s.poke("io_tick", 1)
      var ticks = 0
      while ((s.peek("io_status") & 0xffff) == 0) { s.step(); ticks += 1; assert(ticks <= Gba, "frame too long") }
      assert(ticks == Gba, s"frame of $ticks core clocks")
      s.poke("io_tick", 0); s.step() // frame 1 boundary
      s.expect("io_stall", 0)
      s.expect("io_visibleButtons", 0x315); s.expect("io_hiddenButtons", 0x2a5)
    } finally s.finish()
  }
}
