package gba.link

import chisel3._
import net.gamebub.core.md.MdTreadle
import org.scalatest.freespec.AnyFreeSpec

class WirelessSioSpec extends AnyFreeSpec {
  private def run(name: String, guard: Int = 32)(f: MdTreadle.Sim => Unit): Unit = {
    val s = MdTreadle(new WirelessSio(guard), name)
    try {
      for (p <- Seq("io_start", "io_cancel", "io_master", "io_data", "io_port_clear",
        "io_multi", "io_multiMode", "io_multiLost", "io_multiCycles", "io_multiBaud",
        "io_port_command_valid", "io_port_command_bits_id", "io_port_command_bits_data",
        "io_port_command_bits_error", "io_port_command_bits_begin",
        "io_port_command_bits_multiEnabled", "io_port_command_bits_multiParent",
        "io_port_command_bits_sync", "io_port_command_bits_cycles",
        "io_port_command_bits_grantEnabled", "io_port_command_bits_grant", "io_port_command_bits_anchor",
        "io_port_command_bits_syncEnabled", "io_port_command_bits_multiReady",
        "io_port_command_bits_pairFromHost")) s.poke(p, 0)
      s.poke("io_multiWordCycles", 1)
      s.poke("io_enable", 1); s.poke("io_port_enabled", 1)
      s.poke("reset", 1); s.step(2); s.poke("reset", 0); s.step()
      f(s)
    } finally s.finish()
  }
  private def start(s: MdTreadle.Sim, master: Boolean): Unit = {
    s.poke("io_master", master); s.poke("io_data", BigInt("87654321",16)); s.poke("io_start",1)
    s.expect("io_port_event_valid",1)
    s.expect("io_port_event_bits_meta", BigInt("40000001",16)+(if (master) BigInt("20000000",16) else 0))
    s.expect("io_port_event_bits_data",BigInt("87654321",16))
    s.step(); s.poke("io_start",0); s.expect("io_port_busy",1)
  }
  for (master <- Seq(false,true)) {
    s"full word completion for master=$master waits for CPU enable and happens once" in {
      run(s"GbaWord$master") { s =>
        start(s,master); s.poke("io_enable",0)
        s.poke("io_port_command_bits_id",1); s.poke("io_port_command_bits_data",BigInt("fedcba98",16))
        s.poke("io_port_command_valid",1); s.step(); s.poke("io_port_command_valid",0)
        s.expect("io_port_busy",0); s.expect("io_done",0); s.step(4)
        s.poke("io_enable",1); s.expect("io_done",1)
        s.expect("io_received",BigInt("fedcba98",16)); s.step(); s.expect("io_done",0)
        s.poke("io_port_command_valid",1); s.step(); s.expect("io_done",0)
      }
    }
  }
  "cancellation beats a reply; next transfer rejects the old token" in {
    run("GbaCancel") { s =>
      start(s,true)
      s.poke("io_cancel",1); s.poke("io_port_command_valid",1); s.poke("io_port_command_bits_id",1)
      s.expect("io_port_event_bits_meta",BigInt("e0000001",16)); s.step()
      s.poke("io_cancel",0); s.poke("io_port_command_valid",0)
      s.expect("io_port_busy",0); s.expect("io_done",0)
      s.poke("io_start",1); s.step(); s.poke("io_start",0)
      s.poke("io_port_command_valid",1); s.step(); s.expect("io_port_busy",1)
      s.poke("io_port_command_bits_id",2); s.step(); s.expect("io_port_busy",0)
    }
  }
  "a Multiplayer START reports the SIOCNT baud above the 16-bit word" in {
    run("GbaMultiBaud") { s =>
      s.poke("io_multi", 1); s.poke("io_multiBaud", 2)
      s.poke("io_data", BigInt("87654321", 16)); s.poke("io_start", 1)
      s.expect("io_port_event_valid", 1)
      s.expect("io_port_event_bits_data", BigInt("00024321", 16))
      s.step(); s.poke("io_start", 0); s.expect("io_port_busy", 1)
    }
  }
  "an idle Multiplayer child does not time out or interrupt on peer loss" in {
    run("GbaMultiIdleChild") { s =>
      s.poke("io_multi", 1); s.poke("io_start", 1)
      s.expect("io_port_event_bits_meta", BigInt("50000001", 16))
      s.step(); s.poke("io_start", 0)
      s.step(40)
      s.expect("io_port_busy", 1); s.expect("io_multiBusy", 0); s.expect("io_done", 0)
      s.expect("io_port_multiWait", 0)
      s.poke("io_multiLost", 1); s.step()
      s.expect("io_port_busy", 0); s.expect("io_done", 0)
    }
  }
  for (parent <- Seq(false, true); timeout <- Seq(false, true)) {
    s"Multiplayer parent=$parent releases a stalled guest, timeout=$timeout" in {
      run(s"GbaMultiWait$parent$timeout") { s =>
        s.poke("io_multi", 1)
        s.poke("io_multiCycles", 6)
        s.poke("io_master", parent)
        s.poke("io_data", 0x1234)
        s.poke("io_start", 1)
        s.step()
        s.poke("io_start", 0)
        s.poke("io_port_command_bits_id", 1)
        if (!parent) {
          s.step(12)
          s.expect("io_port_multiWait", 0)
          s.poke("io_port_command_bits_begin", 1)
          s.poke("io_port_command_valid", 1)
          s.step()
          s.poke("io_port_command_valid", 0)
          s.poke("io_port_command_bits_begin", 0)
        }

        // Stop immediately before the peer's slot would become visible.
        val waitAt = if (parent) 4 else 0
        if (waitAt > 0) {
          s.step(waitAt - 1)
          s.expect("io_port_multiWait", 0)
          s.step()
        }
        s.expect("io_port_multiWait", 1)
        s.expect("io_multiBusy", 1)
        s.poke("io_enable", 0)
        s.step(4)
        s.expect("io_port_multiWait", 1)

        val reply = if (parent) BigInt("abcd1234", 16) else BigInt("abcd", 16)
        val expected = if (parent) reply else BigInt("1234abcd", 16)
        if (timeout) {
          s.step(32)
        } else {
          s.poke("io_port_command_bits_data", reply)
          s.poke("io_port_command_valid", 1)
          s.expect("io_port_event_valid", 1)
          s.step()
          s.poke("io_port_command_valid", 0)
        }

        s.expect("io_port_multiWait", 0)
        s.expect("io_port_busy", 0)
        s.expect("io_multiBusy", 1)
        s.expect("io_done", 0)
        s.poke("io_enable", 1)
        if (!timeout) {
          s.expect("io_done", 0)
          s.expect(if (parent) "io_multiSlot1_valid" else "io_multiSlot0_valid", 1)
          s.step(6 - waitAt)
        }
        s.expect("io_done", 1)
        s.expect("io_failed", if (timeout) 1 else 0)
        s.expect("io_received", if (timeout) BigInt("ffffffff", 16) else expected)
        s.step()
        s.expect("io_multiBusy", 0)
        s.expect("io_done", 0)
      }
    }
  }
  for (parent <- Seq(false, true)) {
    s"Multiplayer parent=$parent replies to transport before the local transfer ends" in {
      run(s"GbaMultiEarlyReply$parent") { s =>
        s.poke("io_multi", 1)
        s.poke("io_multiCycles", 6)
        s.poke("io_master", parent)
        s.poke("io_data", 0x1234)
        s.poke("io_start", 1)
        s.step()
        s.poke("io_start", 0)
        s.poke("io_port_command_bits_id", 1)
        if (!parent) {
          s.poke("io_port_command_bits_begin", 1)
          s.poke("io_port_command_valid", 1)
          s.step()
          s.poke("io_port_command_bits_begin", 0)
          s.poke("io_port_command_valid", 0)
        }

        if (parent) s.step()
        s.poke("io_enable", 0)
        s.poke("io_port_command_bits_data", BigInt("56781234", 16))
        s.poke("io_port_command_valid", 1)
        s.expect("io_port_event_valid", 1)
        s.expect("io_port_event_bits_data", 0x1234)
        s.step()
        s.poke("io_port_command_valid", 0)
        s.step(8)
        s.expect("io_port_busy", 0)
        s.expect("io_multiBusy", 1)
        s.expect("io_port_multiWait", 0)
        s.expect("io_done", 0)

        s.poke("io_enable", 1)
        s.step(if (parent) 4 else 5)
        s.expect("io_done", 0)
        s.step()
        s.expect("io_done", 1)
        s.expect("io_failed", 0)
        s.expect("io_received", if (parent) BigInt("56781234", 16) else BigInt("12341234", 16))
        s.step()
        s.expect("io_multiBusy", 0)
        s.expect("io_done", 0)
      }
    }
  }

  for (cancel <- Seq(false, true)) {
    s"Multiplayer buffered result is invalidated on abort or peer loss, cancel=$cancel" in {
      run(s"GbaMultiBufferedAbort$cancel") { s =>
        s.poke("io_multi", 1)
        s.poke("io_multiCycles", 6)
        s.poke("io_master", 1)
        s.poke("io_start", 1)
        s.step()
        s.poke("io_start", 0)
        s.poke("io_port_command_bits_id", 1)
        s.poke("io_port_command_bits_data", 0x1234)
        s.poke("io_port_command_valid", 1)
        s.step()
        s.poke("io_port_command_valid", 0)
        s.expect("io_multiBusy", 1)
        s.expect("io_done", 0)

        val abort = if (cancel) "io_cancel" else "io_multiLost"
        s.poke(abort, 1)
        s.step()
        s.expect("io_done", if (cancel) 0 else 1)
        if (!cancel) {
          s.expect("io_failed", 1)
          s.expect("io_received", BigInt("ffffffff", 16))
        }
        s.step()
        s.poke(abort, 0)
        s.expect("io_multiBusy", 0)

        s.poke("io_start", 1)
        s.step()
        s.poke("io_start", 0)
        s.poke("io_port_command_valid", 1)
        s.expect("io_port_event_valid", 0)
        s.step()
        s.expect("io_port_busy", 1)
        s.poke("io_port_command_bits_id", 2)
        s.expect("io_port_event_valid", 1)
        s.step()
        s.poke("io_port_command_valid", 0)
        s.step(4)
        s.expect("io_done", 1)
        s.expect("io_failed", 0)
      }
    }
  }

  for (clear <- Seq(false,true)) {
    s"guard or channel clear releases a paused CPU, clear=$clear" in {
      run(s"GbaGuard$clear") { s =>
        start(s,true); s.poke("io_enable",0)
        if (clear) { s.poke("io_port_clear",1); s.step(); s.poke("io_port_clear",0) }
        else s.step(32)
        s.expect("io_port_busy",0); s.expect("io_done",0)
        s.poke("io_enable",1); s.expect("io_done",1); s.expect("io_received",BigInt("ffffffff",16))
        s.step(); s.expect("io_done",0)
      }
    }
  }
  "synchronized child catches up in guest cycles and samples once at the target" in {
    run("GbaSyncCapture") { s =>
      s.poke("io_multi", 1); s.poke("io_multiMode", 1)
      s.poke("io_port_command_bits_syncEnabled", 1)
      s.poke("io_multiCycles", 6); s.poke("io_data", 0x1111)
      s.poke("io_start", 1); s.step(); s.poke("io_start", 0)
      s.expect("io_port_multiWait", 1); s.expect("io_multiBusy", 0)
      s.poke("io_enable", 0); s.step(5)
      s.poke("io_port_command_bits_id", 1)
      s.poke("io_port_command_bits_data", 0xabcd)
      s.poke("io_port_command_bits_cycles", 4)
      s.poke("io_port_command_bits_sync", 1)
      s.poke("io_port_command_valid", 1); s.step()
      s.poke("io_port_command_valid", 0)
      s.expect("io_port_multiWait", 0); s.expect("io_port_event_valid", 0)
      // Wall-clock cycles while cartridge/CPU enable is absent do not consume credit.
      s.step(3); s.expect("io_port_event_bits_cycles", 0)
      s.poke("io_enable", 1); s.step(2)
      s.poke("io_data", 0x2222); s.step(2)
      s.expect("io_port_multiWait", 1)
      s.expect("io_port_event_valid", 1)
      s.expect("io_port_event_bits_meta", BigInt("90000001", 16))
      s.expect("io_port_event_bits_cycles", 4)
      s.expect("io_port_event_bits_data", 0x2222)
      s.poke("io_enable", 0); s.step()
      s.expect("io_port_multiWait", 0); s.expect("io_port_event_valid", 0)
      s.expect("io_received", BigInt("2222abcd", 16))
      // A duplicate command cannot capture again or replace the cached outgoing word.
      s.poke("io_data", 0x3333); s.poke("io_port_command_valid", 1); s.step()
      s.poke("io_port_command_valid", 0); s.expect("io_port_event_valid", 0)
      s.poke("io_enable", 1); s.step(6); s.expect("io_done", 1)
      s.step(); s.expect("io_done", 0); s.expect("io_port_event_bits_cycles", 0)
    }
  }

  "synchronized parent freezes from START and preserves its interval in the FIFO event" in {
    run("GbaSyncParent") { s =>
      s.poke("io_multi", 1); s.poke("io_multiMode", 1)
      s.poke("io_port_command_bits_syncEnabled", 1)
      s.poke("io_master", 1); s.poke("io_multiCycles", 6)
      s.step(); s.step(4)
      s.poke("io_start", 1); s.expect("io_port_event_bits_cycles", 4)
      s.step(); s.poke("io_start", 0)
      s.expect("io_port_multiWait", 1)
      s.poke("io_enable", 0); s.step(5)
      s.poke("io_port_command_bits_id", 1)
      s.poke("io_port_command_bits_data", BigInt("22221111", 16))
      s.poke("io_port_command_valid", 1); s.step()
      s.poke("io_port_command_valid", 0); s.expect("io_port_multiWait", 0)
      s.poke("io_enable", 1); s.step(6); s.expect("io_done", 1)
      s.step(); s.expect("io_port_event_bits_cycles", 0)
    }
  }

  for (clear <- Seq(false, true)) {
    s"synchronized idle child releases the CPU on guard or clear, clear=$clear" in {
      run(s"GbaSyncRecovery$clear") { s =>
        s.poke("io_multi", 1); s.poke("io_multiMode", 1)
        s.poke("io_port_command_bits_syncEnabled", 1)
        s.poke("io_start", 1); s.step(); s.poke("io_start", 0)
        s.poke("io_enable", 0); s.expect("io_port_multiWait", 1)
        if (clear) { s.poke("io_port_clear", 1); s.step(); s.poke("io_port_clear", 0) }
        else s.step(32)
        s.expect("io_port_multiWait", 0); s.expect("io_port_busy", 0)
        s.expect("io_done", 0)
      }
    }
  }

  "a target behind executed progress fails without sampling a later word" in {
    run("GbaSyncLateTarget") { s =>
      s.poke("io_multi", 1); s.poke("io_multiMode", 1)
      s.poke("io_port_command_bits_syncEnabled", 1)
      s.poke("io_multiCycles", 6)
      s.step(); s.step(4)
      s.poke("io_start", 1); s.step(); s.poke("io_start", 0)
      s.poke("io_enable", 0)
      s.poke("io_port_command_bits_id", 1)
      s.poke("io_port_command_bits_cycles", 2)
      s.poke("io_port_command_bits_sync", 1)
      s.poke("io_port_command_valid", 1); s.step()
      s.poke("io_port_command_valid", 0)
      s.expect("io_port_event_bits_meta", BigInt("d0000001", 16))
      s.step(); s.expect("io_port_multiWait", 0)
      s.poke("io_enable", 1); s.expect("io_done", 1)
      s.expect("io_failed", 1); s.expect("io_received", BigInt("ffffffff", 16))
      s.step(); s.expect("io_done", 0)
    }
  }

  "GRANT advances before ROUND, caps execution, rejects stale origins and captures once" in {
    run("GbaGrantCredit", 1000) { s =>
      def stepGuest(n: Int = 1): Unit = for (_ <- 0 until n) {
        s.poke("io_enable", if (s.peek("io_port_multiWait")==0) 1 else 0)
        s.step()
      }
      def cmd(grant: Boolean, token: Int, budget: Int): Unit = {
        s.poke("io_enable",0)
        s.poke("io_port_command_bits_grant",grant)
        s.poke("io_port_command_bits_sync",!grant)
        s.poke("io_port_command_bits_id",token)
        s.poke("io_port_command_bits_anchor",token)
        s.poke("io_port_command_bits_cycles",budget)
        s.poke("io_port_command_valid",1); s.step()
        s.poke("io_port_command_valid",0)
      }
      s.poke("io_multi",1); s.poke("io_multiMode",1)
      s.poke("io_multiCycles",6)
      s.poke("io_port_command_bits_syncEnabled",1)
      s.poke("io_port_command_bits_grantEnabled",1)
      s.poke("io_port_command_bits_data",0xabcd)
      s.poke("io_start",1); s.step(); s.poke("io_start",0)
      s.poke("io_enable",0)
      cmd(true,0,500) // No common completed round: bootstrap must stay stopped.
      s.expect("io_port_multiWait",1)
      cmd(false,1,4)
      stepGuest(4)
      s.expect("io_port_event_valid",1); s.expect("io_port_event_bits_cycles",4)
      stepGuest() // capture; no guest cycle advances
      stepGuest(7) // cable phases, then local completion
      s.expect("io_port_progress_valid",1); s.expect("io_port_progress_anchor",1)
      s.expect("io_port_progress_cycles",0)
      s.poke("io_start",1); stepGuest(); s.poke("io_start",0)
      s.expect("io_port_multiWait",1)
      cmd(true,1,5)
      s.expect("io_port_multiWait",0)
      stepGuest(4)
      s.expect("io_port_progress_cycles",5); s.expect("io_port_multiWait",1)
      s.expect("io_port_event_valid",0) // Advance happened before the next ROUND.
      cmd(true,0,500); cmd(true,1,3) // stale origin and reordered lower grant
      stepGuest(3)
      s.expect("io_port_progress_cycles",5); s.expect("io_port_multiWait",1)
      cmd(false,2,9)
      cmd(true,1,100) // ROUND's exact target wins over credit.
      s.poke("io_data",0x5678); stepGuest(4)
      s.expect("io_port_progress_cycles",9); s.expect("io_port_multiWait",1)
      s.expect("io_port_event_valid",1); s.expect("io_port_event_bits_data",0x5678)
      stepGuest()
      s.expect("io_received",BigInt("5678abcd",16))
      stepGuest(6)
      // Race a stale SPI grant with the exact local completion edge.
      s.poke("io_enable",1); s.expect("io_done",1)
      s.poke("io_port_command_valid",1); s.poke("io_port_command_bits_grant",1)
      s.poke("io_port_command_bits_anchor",1); s.poke("io_port_command_bits_cycles",10000)
      s.step(); s.poke("io_port_command_valid",0)
      s.expect("io_port_progress_anchor",2)
      s.poke("io_start",1); stepGuest(); s.poke("io_start",0)
      cmd(true,1,20000)
      s.expect("io_port_multiWait",1); s.expect("io_port_progress_cycles",1)
      // Mode exit invalidates the origin even when no host command intervenes.
      s.poke("io_multiMode",0); s.step()
      s.expect("io_port_progress_valid",0)
    }
  }

  "parent publishes progress only between successful completed rounds" in {
    run("GbaGrantParent",1000) { s =>
      s.poke("io_multi",1); s.poke("io_multiMode",1); s.poke("io_master",1)
      s.poke("io_multiCycles",6)
      s.poke("io_port_command_bits_syncEnabled",1)
      s.poke("io_port_command_bits_grantEnabled",1)
      s.poke("io_port_command_bits_multiParent",1)
      s.step(3)
      s.expect("io_port_progress_valid",0)
      s.poke("io_start",1); s.step(); s.poke("io_start",0)
      s.poke("io_enable",0)
      s.poke("io_port_command_bits_id",1); s.poke("io_port_command_valid",1)
      s.step(); s.poke("io_port_command_valid",0)
      s.expect("io_port_progress_running",0) // RESULT accepted but IRQ not committed.
      s.poke("io_enable",1); s.step(7)
      s.expect("io_port_progress_running",1); s.expect("io_port_progress_anchor",1)
      s.step(10); s.expect("io_port_progress_cycles",10)
      s.poke("io_start",1)
      s.expect("io_port_event_bits_cycles",10)
      s.step(); s.poke("io_start",0); s.poke("io_enable",0)
      s.expect("io_port_progress_running",0)
    }
  }

  "GB-117: a Multiplayer child BEGIN samples SEND and exposes it before DONE" in {
    run("GbaChildSampled") { s =>
      s.poke("io_multi", 1); s.poke("io_multiCycles", 6); s.poke("io_master", 0)
      s.poke("io_data", 0x1111); s.poke("io_start", 1); s.step(); s.poke("io_start", 0)
      s.expect("io_port_sampled", 0x1111)
      // The game rewrites SEND while armed; BEGIN takes the latest word, like the cable.
      s.poke("io_data", 0x2222); s.step(3)
      s.poke("io_port_command_bits_id", 1); s.poke("io_port_command_bits_begin", 1)
      s.poke("io_port_command_valid", 1); s.step()
      s.poke("io_port_command_bits_begin", 0); s.poke("io_port_command_valid", 0)
      s.expect("io_port_sampled", 0x2222); s.expect("io_done", 0)
      s.poke("io_port_command_bits_data", 0xabcd); s.poke("io_port_command_valid", 1); s.step()
      s.poke("io_port_command_valid", 0)
      var done = false
      for (_ <- 0 until 12 if !done) { if (s.peek("io_done") == 1) done = true else s.step() }
      assert(done); s.expect("io_received", BigInt("2222abcd", 16))
    }
  }
  for (parent <- Seq(false, true)) {
    s"GB-117: pair from host fills both slots from COMPLETE, parent=$parent" in {
      run(s"GbaPairFromHost$parent") { s =>
        s.poke("io_port_command_bits_pairFromHost", 1)
        s.poke("io_multi", 1); s.poke("io_multiCycles", 6); s.poke("io_master", parent)
        s.poke("io_data", 0x1234); s.poke("io_start", 1); s.step(); s.poke("io_start", 0)
        s.poke("io_port_command_bits_id", 1)
        if (!parent) {
          s.poke("io_port_command_bits_begin", 1); s.poke("io_port_command_valid", 1); s.step()
          s.poke("io_port_command_bits_begin", 0); s.poke("io_port_command_valid", 0)
        }
        // Without the host pair, even the parent stops before its own slot.
        s.step(3); s.expect("io_port_multiWait", 1); s.expect("io_multiSlot0_valid", 0)
        s.poke("io_port_command_bits_data", BigInt("55557777", 16)); s.poke("io_port_command_valid", 1); s.step()
        s.poke("io_port_command_valid", 0)
        var slot0 = BigInt(-1); var slot1 = BigInt(-1); var done = false
        for (_ <- 0 until 16 if !done) {
          if (s.peek("io_multiSlot0_valid") == 1) slot0 = s.peek("io_multiSlot0_bits")
          if (s.peek("io_multiSlot1_valid") == 1) slot1 = s.peek("io_multiSlot1_bits")
          if (s.peek("io_done") == 1) done = true else s.step()
        }
        assert(done && slot0 == 0x7777 && slot1 == 0x5555, s"slot0=$slot0 slot1=$slot1")
        s.expect("io_received", BigInt("55557777", 16)); s.expect("io_failed", 0)
      }
    }
  }
}
