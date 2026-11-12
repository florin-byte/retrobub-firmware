package gameboy

import net.gamebub.core.md.MdTreadle
import org.scalatest.freespec.AnyFreeSpec

class WirelessSerialSpec extends AnyFreeSpec {
  import WirelessLink._
  private def run(name: String, config: Int = 13)(test: MdTreadle.Sim => Unit): Unit = {
    // The guard is scaled to 64 module clocks; CPU speed must not scale it.
    val s = MdTreadle(new Serial(Gameboy.Configuration(skipBootrom = true, wirelessGuardCycles = 64)), name)
    try {
      for (p <- Seq("io_enabled", "io_write", "io_address", "io_dataWrite",
        "io_divSerial", "io_divSerialFast", "io_wireless_clear",
        "io_wireless_command_valid", "io_wireless_command_bits", "io_clocker_tCycle",
        "io_clocker_counter8Mhz", "io_clocker_phiPulse", "io_clocker_pulse4Mhz",
        "io_clocker_pulse8Mhz", "io_clocker_pulseVramDma", "io_clocker_doubleSpeed")) s.poke(p, 0)
      s.poke("io_clocker_enable", 1); s.poke("io_cgbMode", 1)
      s.poke("io_serial_in", 1); s.poke("io_serial_clockIn", 1)
      s.poke("io_wireless_config", config)
      s.poke("reset", 1); s.step(2); s.poke("reset", 0); s.step()
      test(s)
    } finally s.finish()
  }
  private def write(s: MdTreadle.Sim, addr: Int, data: Int, event: Option[Int] = None): Unit = {
    s.poke("io_address", addr); s.poke("io_dataWrite", data)
    s.poke("io_enabled", 1); s.poke("io_write", 1)
    event.foreach(e => { s.expect("io_wireless_event_valid", 1); s.expect("io_wireless_event_bits", e) })
    s.step(); s.poke("io_write", 0); s.poke("io_enabled", 0)
  }
  private def command(s: MdTreadle.Sim, op: Int, byte: Int = 0): Unit = {
    s.poke("io_wireless_command_bits", op * 256 + byte); s.poke("io_wireless_command_valid", 1)
  }
  private def endCommand(s: MdTreadle.Sim): Unit = { s.step(); s.poke("io_wireless_command_valid", 0) }
  private def start(s: MdTreadle.Sim, sc: Int = 0x81): Unit = {
    write(s, 1, 0x35); write(s, 2, sc, Some(Master * 256 + 0x35))
  }

  "master holds, absorbs stop/restart and accepts exactly one reply" in {
    run("WirelessMaster") { s =>
      start(s)
      for (sc <- Seq(0x81, 0x01, 0x81)) {
        s.poke("io_address", 2); s.poke("io_dataWrite", sc)
        s.poke("io_enabled", 1); s.poke("io_write", 1)
        s.expect("io_wireless_event_valid", 0); s.step()
      }
      s.poke("io_write", 0); s.poke("io_enabled", 0)
      s.step(8); s.expect("io_wireless_held", 1)
      s.expect("io_interruptRequest", 0); s.expect("io_serial_clockEnable", 0)
      s.expect("io_serial_clockOut", 1); s.expect("io_serial_out", 1)
      s.expect("io_debug_regData", 0x35)
      command(s, MasterReply, 0xA6); s.expect("io_interruptRequest", 1); endCommand(s)
      s.expect("io_wireless_held", 0); s.expect("io_debug_regData", 0xA6)
      s.expect("io_debug_regEnable", 0); s.expect("io_interruptRequest", 0)
      command(s, MasterReply, 0x77); s.expect("io_interruptRequest", 0); endCommand(s)
      s.expect("io_debug_regData", 0xA6)
    }
  }

  "drop is silent and a parked SB write lands once after the verdict" in {
    run("WirelessParkDrop") { s =>
      start(s)
      write(s, 1, 0x72, Some(Abandon * 256 + 0x72))
      s.expect("io_wireless_stall", 1); s.expect("io_debug_regData", 0x35)
      s.poke("io_clocker_enable", 0); s.step(5)
      s.expect("io_wireless_event_valid", 0)
      command(s, MasterDrop); s.expect("io_interruptRequest", 0); endCommand(s)
      s.expect("io_debug_regData", 0x35); s.expect("io_wireless_stall", 1)
      s.step(); s.expect("io_debug_regData", 0x72); s.expect("io_wireless_stall", 0)
      s.step(3); s.expect("io_wireless_event_valid", 0)
    }
  }

  "parked SC and a simultaneous verdict release the barrier" in {
    run("WirelessParkReply") { s =>
      start(s)
      command(s, MasterReply, 0xA2)
      write(s, 2, 0x80, Some(Abandon * 256 + 0x80))
      s.poke("io_wireless_command_valid", 0)
      s.expect("io_debug_regData", 0xA2); s.expect("io_wireless_stall", 1)
      s.expect("io_wireless_event_bits", Armed * 256 + 0xA2)
      s.step(); s.expect("io_wireless_armed", 1); s.expect("io_wireless_stall", 0)
    }
  }

  "slave reports the outgoing byte, disarms and NAKs unarmed offers" in {
    run("WirelessSlave", 5) { s =>
      write(s, 1, 0x42); write(s, 2, 0x80, Some(Armed * 256 + 0x42))
      command(s, SlaveXfer, 0x99)
      s.expect("io_interruptRequest", 1); s.expect("io_wireless_event_bits", SlaveDone * 256 + 0x42)
      endCommand(s); s.expect("io_debug_regData", 0x99); s.expect("io_wireless_armed", 0)
      command(s, SlaveXfer, 0x88)
      s.expect("io_interruptRequest", 0); s.expect("io_wireless_event_bits", SlaveNak * 256 + 0x99)
      endCommand(s); s.expect("io_debug_regData", 0x99)
      write(s, 2, 0x80); write(s, 2, 0, Some(Disarm * 256 + 0x99))
    }
  }

  "standing offer exchanges on the arm edge and withdrawal cancels it" in {
    run("WirelessStanding") { s =>
      write(s, 1, 0x41); command(s, SlaveXfer, 0xA1); endCommand(s)
      write(s, 2, 0x80, Some(Armed * 256 + 0x41))
      s.expect("io_debug_regData", 0xA1); s.expect("io_wireless_armed", 0)
      command(s, SlaveXfer, 0xA2); endCommand(s)
      command(s, SlaveWithdraw); s.expect("io_wireless_event_bits", SlaveNak * 256 + 0xA1); endCommand(s)
      write(s, 2, 0x80, Some(Armed * 256 + 0xA1)); s.expect("io_wireless_armed", 1)
    }
  }

  private def pair(s: MdTreadle.Sim, first: Int, second: Int): Unit = {
    s.expect("io_wireless_event_valid", 1)
    s.expect("io_wireless_event_bits", first)
    s.expect("io_wireless_eventNext_valid", 1)
    s.expect("io_wireless_eventNext_bits", second)
  }

  for (fast <- Seq(false, true); addr <- Seq(1, 2)) {
    s"abandon precedes withdrawal for fast=$fast address=$addr" in {
      run(s"WirelessAbandonWithdraw$fast$addr") { s =>
        start(s, if (fast) 0x83 else 0x81)
        command(s, SlaveXfer, 0x91); endCommand(s)
        command(s, SlaveWithdraw)
        s.poke("io_enabled", 1); s.poke("io_write", 1)
        s.poke("io_address", addr); s.poke("io_dataWrite", 0x72)
        pair(s, Abandon * 256 + 0x72, SlaveNak * 256 + 0x35)
        endCommand(s); s.poke("io_enabled", 0); s.poke("io_write", 0)
        s.expect("io_wireless_event_valid", 0); s.expect("io_wireless_eventNext_valid", 0)
        command(s, MasterDrop); s.expect("io_interruptRequest", 0); endCommand(s)
        s.step(); s.expect("io_wireless_held", 0)
      }
    }
  }

  for (fast <- Seq(false, true)) {
    s"abandon precedes incoming rejection for fast=$fast" in {
      run(s"WirelessAbandonIncoming$fast", 5) { s =>
        start(s, if (fast) 0x83 else 0x81)
        command(s, SlaveXfer, 0x91)
        s.poke("io_enabled", 1); s.poke("io_write", 1)
        s.poke("io_address", 1); s.poke("io_dataWrite", 0x72)
        pair(s, Abandon * 256 + 0x72, (if (fast) SlaveNakStalled else SlaveNak) * 256 + 0x35)
        endCommand(s); s.poke("io_enabled", 0); s.poke("io_write", 0)
        s.expect("io_wireless_event_valid", 0); s.expect("io_wireless_eventNext_valid", 0)
      }
    }
  }

  "master precedes an incoming NAK" in {
    run("WirelessMasterNak", 5) { s =>
      write(s, 1, 0x35); command(s, SlaveXfer, 0x91)
      s.poke("io_enabled", 1); s.poke("io_write", 1)
      s.poke("io_address", 2); s.poke("io_dataWrite", 0x81)
      pair(s, Master * 256 + 0x35, SlaveNak * 256 + 0x35)
      s.step(); s.expect("io_wireless_held", 1)
    }
  }

  for ((sb, withdraw) <- Seq((false, false), (false, true), (true, false))) {
    s"armed hint precedes slave response for SB=$sb withdrawal=$withdraw" in {
      run(s"WirelessArmedPair$sb$withdraw") { s =>
        write(s, 1, 0x41)
        // Keep the standing offer pending until the same edge as the CPU write.
        command(s, SlaveXfer, 0xA1); endCommand(s)
        if (sb) {
          // An already armed slave must use a fresh command rather than an offer.
          command(s, SlaveWithdraw); endCommand(s)
          write(s, 2, 0x80)
          command(s, SlaveXfer, 0xA1)
        } else if (withdraw) command(s, SlaveWithdraw)
        s.poke("io_enabled", 1); s.poke("io_write", 1)
        s.poke("io_address", if (sb) 1 else 2)
        s.poke("io_dataWrite", if (sb) 0x62 else 0x80)
        val done = sb || !withdraw
        pair(s, Armed * 256 + (if (sb) 0x62 else 0x41),
          (if (done) SlaveDone else SlaveNak) * 256 + (if (sb) 0x62 else 0x41))
        s.expect("io_interruptRequest", if (done) 1 else 0)
        endCommand(s); s.poke("io_enabled", 0); s.poke("io_write", 0)
        s.expect("io_wireless_eventNext_valid", 0)
      }
    }
  }

  "disarm precedes an incoming NAK" in {
    run("WirelessDisarmNak", 5) { s =>
      write(s, 1, 0x41); write(s, 2, 0x80)
      command(s, SlaveXfer, 0xA1)
      s.poke("io_enabled", 1); s.poke("io_write", 1)
      s.poke("io_address", 2); s.poke("io_dataWrite", 0)
      pair(s, Disarm * 256 + 0x41, SlaveNak * 256 + 0x41)
      s.expect("io_interruptRequest", 0)
      s.poke("io_wireless_clear", 1)
      s.expect("io_wireless_event_valid", 0); s.expect("io_wireless_eventNext_valid", 0)
    }
  }

  for (doubleSpeed <- Seq(false, true); fast <- Seq(false, true)) {
    s"guard uses module clocks with double speed = $doubleSpeed and fast = $fast" in {
      run(s"WirelessGuard${doubleSpeed}${fast}") { s =>
        s.poke("io_clocker_doubleSpeed", doubleSpeed)
        start(s, if (fast) 0x83 else 0x81); s.expect("io_wireless_stall", if (fast) 1 else 0)
        s.poke("io_clocker_enable", 0)
        command(s, SlaveXfer, 0x55)
        s.expect("io_wireless_event_valid", if (fast) 1 else 0)
        if (fast) s.expect("io_wireless_event_bits", SlaveNakStalled * 256 + 0x35)
        endCommand(s)
        for (_ <- 0 until 62) { s.expect("io_interruptRequest", 0); s.step() }
        s.expect("io_interruptRequest", 1); s.expect("io_wireless_guardExpired", 1); s.step()
        s.expect("io_wireless_guardExpired", 0)
        s.expect("io_debug_regData", 255); s.expect("io_wireless_held", 0)
        s.expect("io_wireless_stall", 0); s.expect("io_interruptRequest", 0)
      }
    }
  }


  for (fast <- Seq(false, true)) {
    s"guard reports expiry with a parked write for fast=$fast" in {
      run(s"WirelessParkGuard$fast") { s =>
        start(s, if (fast) 0x83 else 0x81)
        write(s, 1, 0x72)
        for (_ <- 0 until 62) { s.expect("io_wireless_guardExpired", 0); s.step() }
        s.expect("io_wireless_guardExpired", 1); s.expect("io_interruptRequest", 1)
        s.step(); s.expect("io_debug_regData", 255)
        s.expect("io_wireless_guardExpired", 0); s.expect("io_wireless_held", 0)
        s.step(); s.expect("io_debug_regData", 0x72); s.expect("io_wireless_stall", 0)
      }
    }
  }

  "master drop leaves SB unchanged without an interrupt" in {
    run("WirelessDrop") { s =>
      start(s); command(s, MasterDrop)
      s.expect("io_interruptRequest", 0); endCommand(s)
      s.expect("io_debug_regData", 0x35); s.expect("io_debug_regEnable", 0)
      s.expect("io_wireless_held", 0); s.expect("io_interruptRequest", 0)
    }
  }

  "channel clear completes a stalled normal master with exactly one IRQ" in {
    run("WirelessClear") { s =>
      start(s, 0x83); s.expect("io_wireless_stall", 1)
      s.poke("io_wireless_clear", 1); s.expect("io_interruptRequest", 1)
      s.step(); s.poke("io_wireless_clear", 0)
      s.expect("io_debug_regData", 255)
      s.expect("io_wireless_held", 0); s.expect("io_wireless_stall", 0)
      s.step(65); s.expect("io_interruptRequest", 0)
    }
  }

  "channel clear completes a master starting on the same edge" in {
    run("WirelessStartClear") { s =>
      write(s, 1, 0x35)
      s.poke("io_wireless_clear", 1)
      s.poke("io_enabled", 1); s.poke("io_write", 1)
      s.poke("io_address", 2); s.poke("io_dataWrite", 0x81)
      s.expect("io_interruptRequest", 1); s.expect("io_wireless_event_valid", 0)
      s.step(); s.poke("io_wireless_clear", 0)
      s.poke("io_enabled", 0); s.poke("io_write", 0)
      s.expect("io_wireless_held", 0); s.expect("io_debug_regData", 255)
      s.expect("io_debug_regEnable", 0); s.expect("io_interruptRequest", 0)
    }
  }

  for (fast <- Seq(false, true)) {
    s"channel clear drops an abandoned master silently for fast=$fast" in {
      run(s"WirelessParkClear$fast") { s =>
        start(s, if (fast) 0x83 else 0x81); write(s, 1, 0x72)
        s.poke("io_wireless_clear", 1); s.expect("io_interruptRequest", 0)
        s.step(); s.poke("io_wireless_clear", 0)
        s.expect("io_wireless_held", 0); s.expect("io_wireless_stall", 0)
        s.step(65); s.expect("io_interruptRequest", 0); s.expect("io_debug_regData", 0x35)
      }
    }
  }

  for (fast <- Seq(false, true)) {
    s"disabled wireless retains internal clock transfer, fast = $fast" in {
      run(s"WirelessPhysicalInternal$fast", 0) { s =>
        write(s, 1, 0); write(s, 2, if (fast) 0x83 else 0x81)
        s.expect("io_serial_clockEnable", 1)
        var interrupts = 0
        // Each divider falling edge toggles the output clock. Sixteen toggles
        // shift eight bits, with SI high producing the unplugged value 0xFF.
        for (_ <- 0 until 20) {
          s.poke(if (fast) "io_divSerialFast" else "io_divSerial", 1)
          if (s.peekBool("io_interruptRequest")) interrupts += 1
          s.step()
          s.poke(if (fast) "io_divSerialFast" else "io_divSerial", 0)
          if (s.peekBool("io_interruptRequest")) interrupts += 1
          s.step()
        }
        assert(interrupts == 1)
        s.expect("io_debug_regData", 255); s.expect("io_debug_regEnable", 0)
        s.expect("io_wireless_event_valid", 0)
      }
    }
  }

  "disabled wireless retains physical external-clock byte exchange" in {
    run("WirelessDisabled", 0) { s =>
      write(s, 1, 0x35); write(s, 2, 0x80)
      for (bit <- 7 to 0 by -1) {
        s.poke("io_serial_clockIn", 0); s.step()
        s.expect("io_serial_out", (0x35 >> bit) & 1)
        s.poke("io_serial_in", (0xA6 >> bit) & 1)
        s.poke("io_serial_clockIn", 1)
        s.expect("io_interruptRequest", if (bit == 0) 1 else 0)
        s.step()
      }
      s.expect("io_debug_regData", 0xA6); s.expect("io_debug_regEnable", 0)
      s.expect("io_wireless_event_valid", 0); s.expect("io_wireless_stall", 0)
    }
  }
}
