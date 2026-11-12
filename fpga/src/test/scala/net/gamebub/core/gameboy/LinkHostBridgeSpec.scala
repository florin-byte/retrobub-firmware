package net.gamebub.core.gameboy

import gameboy.WirelessLink._
import net.gamebub.core.md.MdTreadle
import org.scalatest.freespec.AnyFreeSpec

class LinkHostBridgeSpec extends AnyFreeSpec {
  private def run(name: String)(test: MdTreadle.Sim => Unit): Unit = {
    val s = MdTreadle(new LinkHostBridge, name)
    try {
      for (p <- Seq("io_mem_enable", "io_mem_write", "io_mem_address", "io_mem_dataWrite",
        "io_link_event_valid", "io_link_event_bits",
        "io_link_eventNext_valid", "io_link_eventNext_bits", "io_link_stall", "io_link_held",
        "io_link_armed", "io_link_guardExpired", "io_channelReset")) s.poke(p, 0)
      s.poke("io_mem_writeStrobe", 15)
      s.poke("reset", 1); s.step(2); s.poke("reset", 0); s.step()
      test(s)
    } finally s.finish()
  }
  private def read(s: MdTreadle.Sim, address: Int): BigInt = {
    s.poke("io_mem_enable", 1); s.poke("io_mem_write", 0); s.poke("io_mem_address", address)
    s.peek("io_mem_dataRead")
  }
  private def write(s: MdTreadle.Sim, address: Int, data: Int): Unit = {
    s.poke("io_mem_enable", 1); s.poke("io_mem_write", 1)
    s.poke("io_mem_address", address); s.poke("io_mem_dataWrite", data)
    s.step()
    // Event lanes are one-cycle pulses, independent of the two-cycle host write.
    s.poke("io_link_event_valid", 0); s.poke("io_link_eventNext_valid", 0)
    s.step(); s.poke("io_mem_enable", 0); s.poke("io_mem_write", 0)
  }
  private def event(s: MdTreadle.Sim, kind: Int, byte: Int): Unit = {
    s.poke("io_link_event_valid", 1); s.poke("io_link_event_bits", kind * 256 + byte)
    s.step(); s.poke("io_link_event_valid", 0)
  }
  "ABI, configuration, atomic commands and session reset" in {
    run("LinkRegisters") { s =>
      assert(read(s, 0x40) == BigInt("574c021f", 16))
      assert(read(s, 0x44) == 0); s.expect("io_coreRequest", 0)
      write(s, 0x44, 15); s.expect("io_link_config", 13)
      assert(((read(s, 0x48) >> 16) & 255) == 1)
      s.poke("io_mem_address", 0x54); s.poke("io_mem_dataWrite", MasterReply * 256 + 0xA3)
      s.poke("io_mem_enable", 1); s.poke("io_mem_write", 1)
      s.expect("io_link_command_valid", 1); s.expect("io_link_command_bits", MasterReply * 256 + 0xA3)
      s.step(); s.expect("io_link_command_valid", 0); s.step(); s.poke("io_mem_enable", 0)
      write(s, 0x54, Config * 256 + 5); s.expect("io_link_config", 5)
      event(s, Master, 0x42)
      write(s, 0x58, 1); s.expect("io_coreRequest", 0); s.expect("io_link_config", 0)
      assert(((read(s, 0x48) >> 16) & 255) == 2)
      s.poke("io_channelReset", 1); s.expect("io_link_clear", 1); s.step()
      s.poke("io_channelReset", 0); assert(((read(s, 0x48) >> 16) & 255) == 3)
    }
  }
  "FIFO preserves order, drops hints near full and flags decisive overflow" in {
    run("LinkFifo") { s =>
      for (i <- 0 until 12) event(s, Master, i)
      event(s, Armed, 99); event(s, Disarm, 99)
      assert((read(s, 0x48) & 31) == 12)
      for (i <- 12 until 16) event(s, SlaveDone, i)
      event(s, Abandon, 99)
      assert((read(s, 0x48) & 0x21F) == 0x210)
      s.expect("io_coreRequest", 1)
      for (i <- 0 until 16) {
        val want = (if (i < 12) Master else SlaveDone) * 256 + i
        assert((read(s, 0x4C) & 0x7ff) == want); assert((read(s, 0x4C) & 0x7ff) == want)
        write(s, 0x50, 1)
      }
      s.expect("io_coreRequest", 0); assert((read(s, 0x4C) & 0x7ff) == 0)
      assert((read(s, 0x48) & 512) == 512)
      write(s, 0x58, 2); assert((read(s, 0x48) & 512) == 0)
      write(s, 0x50, 1); assert((read(s, 0x48) & 31) == 0)
      event(s, Master, 0x77); s.expect("io_coreRequest", 1)
      write(s, 0x50, 1); s.expect("io_coreRequest", 0)
    }
  }
  "simultaneous full FIFO pop and insertion retains all decisive events" in {
    run("LinkFifoSimultaneous") { s =>
      for (i <- 0 until 16) event(s, Master, i)
      s.poke("io_link_event_valid", 1); s.poke("io_link_event_bits", SlaveDone * 256 + 16)
      write(s, 0x50, 1); s.poke("io_link_event_valid", 0)
      assert((read(s, 0x48) & 0x21F) == 16)
      for (i <- 1 to 16) {
        assert((read(s, 0x4C) & 0x7ff) == (if (i < 16) Master * 256 + i else SlaveDone * 256 + 16))
        write(s, 0x50, 1)
      }
      s.expect("io_coreRequest", 0)
    }
  }
  "both lanes preserve every coinciding pair at FIFO boundaries and wraparound" in {
    run("LinkFifoPairs") { s =>
      val pairs = Seq((Abandon, SlaveNak), (Abandon, SlaveNakStalled),
        (Master, SlaveNak), (Armed, SlaveDone), (Armed, SlaveNak), (Disarm, SlaveNak))
      for ((first, second) <- pairs; occupancy <- Seq(0, 11, 12, 14, 15, 16); pop <- Seq(false, true)) {
        write(s, 0x58, 1)
        // Move both pointers near the end so a two-lane insertion also wraps.
        for (i <- 0 until 15) { event(s, Master, i); write(s, 0x50, 1) }
        for (i <- 0 until occupancy) event(s, Master, i)
        s.poke("io_link_event_valid", 1); s.poke("io_link_event_bits", first * 256 + 99)
        s.poke("io_link_eventNext_valid", 1); s.poke("io_link_eventNext_bits", second * 256 + 100)
        if (pop) write(s, 0x50, 1) else s.step()
        s.poke("io_link_event_valid", 0); s.poke("io_link_eventNext_valid", 0)
        val removed = if (pop && occupancy > 0) 1 else 0
        val wantedFirst = !Seq(Armed, Disarm).contains(first) || occupancy < 12
        val insertedFirst = wantedFirst && occupancy - removed < 16
        val insertedSecond = occupancy - removed + (if (insertedFirst) 1 else 0) < 16
        val expected = (removed until occupancy).map(Master * 256 + _) ++
          (if (insertedFirst) Seq(first * 256 + 99) else Seq.empty) ++
          (if (insertedSecond) Seq(second * 256 + 100) else Seq.empty)
        val overflow = (wantedFirst && !insertedFirst) || !insertedSecond
        assert((read(s, 0x48) & 0x21F) == expected.size + (if (overflow) 512 else 0))
        for (value <- expected) {
          assert((read(s, 0x4C) & 0x7ff) == value)
          write(s, 0x50, 1)
        }
        s.expect("io_coreRequest", 0)
      }
    }
  }

  "reset discards both lanes and overflow wins over error clear" in {
    run("LinkPairReset") { s =>
      for (i <- 0 until 16) event(s, Master, i)
      s.poke("io_link_event_valid", 1); s.poke("io_link_event_bits", Abandon * 256 + 99)
      s.poke("io_link_eventNext_valid", 1); s.poke("io_link_eventNext_bits", SlaveNak * 256 + 100)
      write(s, 0x58, 2)
      assert((read(s, 0x48) & 0x21F) == 0x210)
      s.poke("io_channelReset", 1); s.step(); s.poke("io_channelReset", 0)
      s.poke("io_link_event_valid", 0); s.poke("io_link_eventNext_valid", 0)
      assert((read(s, 0x48) & 0x21F) == 0)
      s.expect("io_coreRequest", 0)
    }
  }

  "guard advances transfer id and rejects A verdict while B is held" in {
    run("LinkGuardId") { s =>
      write(s, 0x44, 13)
      event(s, Master, 0x42)
      assert((read(s, 0x48) >> 24) == 1)
      assert(read(s, 0x4C) == BigInt("01000342", 16))
      write(s, 0x50, 1)
      s.poke("io_link_guardExpired", 1); s.step(); s.poke("io_link_guardExpired", 0)
      assert((read(s, 0x48) >> 24) == 2)
      event(s, Master, 0x43)
      assert((read(s, 0x48) >> 24) == 3)
      assert(read(s, 0x4C) == BigInt("03000343", 16))
      for (op <- Seq(MasterReply, MasterDrop)) {
        s.poke("io_mem_address", 0x54); s.poke("io_mem_enable", 1); s.poke("io_mem_write", 1)
        s.poke("io_mem_dataWrite", (1 << 24) | op * 256 | 0x99)
        s.expect("io_link_command_valid", 0)
        s.poke("io_mem_dataWrite", (3 << 24) | op * 256 | 0x77)
        s.expect("io_link_command_valid", 1)
        s.expect("io_link_command_bits", op * 256 + 0x77)
        s.poke("io_mem_enable", 0)
      }
      s.poke("io_link_held", 1); s.poke("io_link_armed", 1)
      assert((read(s, 0x48) & 0xc00) == 0xc00)
    }
  }

}
