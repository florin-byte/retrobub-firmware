package net.gamebub.core.gba

import chisel3._
import gba.link.WirelessSioPort
import lib.mem.MemoryInterface
import net.gamebub.core.md.MdTreadle
import net.gamebub.framework.interface.ClocksV0
import platform.handheld.display.ILI9488
import org.scalatest.freespec.AnyFreeSpec

class RoutedGbaLinkHostBridge extends Module {
  val io = IO(new Bundle {
    val mem = new MemoryInterface(16, 32)
    val link = Flipped(new WirelessSioPort)
    val channelReset = Input(Bool())
    val coreRequest = Output(Bool())
    val regularSelected = Output(Bool())
  })
  val bridge = Module(new GbaLinkHostBridge)
  val regular = Wire(new MemoryInterface(16, 32))
  regular.dataRead := "hbad0cafe".U
  regular.done := regular.enable
  GbaLinkHostBridge.route(io.mem, regular, bridge.io.mem)
  io.link <> bridge.io.link
  bridge.io.channelReset := io.channelReset
  io.coreRequest := bridge.io.coreRequest
  io.regularSelected := regular.enable
}

class GbaLinkHostBridgeSpec extends AnyFreeSpec {
  "complete handheld GBA core elaborates with the dedicated host bridge" in {
    val previous = ClocksV0.getClockDisplayHz
    ClocksV0.getClockDisplayHz = ILI9488.getClockDisplayHz
    try {
      val rtl = circt.stage.ChiselStage.emitCHIRRTL(new HandheldGba)
      assert(rtl.contains("module GbaLinkHostBridge"))
      assert(rtl.contains("module WirelessSio"))
    } finally {
      ClocksV0.getClockDisplayHz = previous
    }
  }

  for (routed <- Seq(false, true)) {
  s"${if (routed) "outer-routed" else "direct"} ABI, stable FIFO words, single write launch, overflow and reset" in {
    val s = if (routed) MdTreadle(new RoutedGbaLinkHostBridge,"GbaLinkRoutedHost")
      else MdTreadle(new GbaLinkHostBridge,"GbaLinkHost")
    def read(a: Int): BigInt = {
      s.poke("io_mem_enable",1); s.poke("io_mem_write",0); s.poke("io_mem_address",a)
      if (routed) s.expect("io_regularSelected", if (a >= 0x60 && a <= 0x9c) 0 else 1)
      s.peek("io_mem_dataRead")
    }
    def write(a: Int,d: BigInt): Unit = {
      s.poke("io_mem_enable",1); s.poke("io_mem_write",1)
      s.poke("io_mem_address",a); s.poke("io_mem_dataWrite",d)
      s.step(); s.expect("io_link_command_valid",0); s.step()
      s.poke("io_mem_enable",0); s.poke("io_mem_write",0)
    }
    try {
      for(p <- Seq("io_mem_enable","io_mem_write","io_mem_address","io_mem_dataWrite",
        "io_link_progress_anchor","io_link_progress_cycles","io_link_progress_valid","io_link_progress_running",
        "io_channelReset","io_link_event_valid","io_link_event_bits_cycles","io_link_event_bits_meta","io_link_event_bits_data","io_link_busy","io_link_multiMode","io_link_sampled")) s.poke(p,0)
      s.poke("io_mem_writeStrobe",15); s.poke("reset",1); s.step(2); s.poke("reset",0); s.step()
      if (routed) {
        assert(read(0x5c) == BigInt("bad0cafe",16))
        assert(read(0xa0) == BigInt("bad0cafe",16))
      }
      assert(read(0x60)==BigInt("57470101",16)); write(0x64,1); s.expect("io_link_enabled",1)
      val generation = read(0x68) >> 16
      assert(read(0x64) == BigInt("f0000001",16))
      write(0x64,31)
      s.expect("io_link_command_bits_multiEnabled",1)
      s.expect("io_link_command_bits_multiParent",1)
      s.expect("io_link_command_bits_multiReady",1)
      s.expect("io_link_command_bits_syncEnabled",1)
      s.expect("io_link_command_bits_pairFromHost",0)
      write(0x64,65); s.expect("io_link_command_bits_pairFromHost",1)
      assert(read(0x64) == BigInt("f0000041",16))
      s.poke("io_link_sampled",BigInt("cafe1234",16)); assert(read(0x9c) == BigInt("cafe1234",16))
      write(0x64,31); s.expect("io_link_command_bits_pairFromHost",0)
      s.poke("io_link_multiMode",1)
      assert((read(0x68) & 0x800) == 0x800 && (read(0x68) >> 16) == generation)
      s.poke("io_link_multiMode",0)
      for(i <- 0 until 17) {
        s.poke("io_link_event_valid",1); s.poke("io_link_event_bits_meta",BigInt("60000000",16)+i)
        s.poke("io_link_event_bits_data",BigInt("87654321",16)+i)
        s.poke("io_link_event_bits_cycles",1000+i); s.step()
      }
      s.poke("io_link_event_valid",0)
      assert((read(0x68)&0x21f)==0x210)
      for(i <- 0 until 16) {
        assert(read(0x6c)==BigInt("60000000",16)+i)
        assert(read(0x70)==BigInt("87654321",16)+i)
        assert(read(0x88)==1000+i)
        write(0x74,1)
      }
      s.expect("io_coreRequest",0)
      write(0x78,BigInt("fedcba98",16)); write(0x7c,42); write(0x8c,123456)
      s.poke("io_mem_enable",1); s.poke("io_mem_write",1); s.poke("io_mem_address",0x80); s.poke("io_mem_dataWrite",9)
      s.expect("io_link_command_valid",1); s.expect("io_link_command_bits_id",42)
      s.expect("io_link_command_bits_data",BigInt("fedcba98",16))
      s.expect("io_link_command_bits_cycles",123456); s.expect("io_link_command_bits_sync",1)
      s.step(); s.expect("io_link_command_valid",0); s.step(); s.poke("io_mem_enable",0)
      // A transition between host reads must not tear the frozen tuple.
      s.poke("io_link_progress_anchor",7); s.poke("io_link_progress_cycles",250000)
      s.poke("io_link_progress_valid",1); s.poke("io_link_progress_running",1)
      write(0x90,1)
      assert(read(0x90)==BigInt("c0000007",16))
      s.poke("io_link_progress_anchor",8); s.poke("io_link_progress_cycles",20)
      s.step()
      assert(read(0x94)==250000)
      assert(read(0x90)==BigInt("c0000007",16))
      write(0x90,1)
      assert(read(0x90)==BigInt("c0000008",16)); assert(read(0x94)==20)
      write(0x98,7); write(0x8c,123)
      s.poke("io_mem_enable",1); s.poke("io_mem_write",1)
      s.poke("io_mem_address",0x80); s.poke("io_mem_dataWrite",17)
      s.expect("io_link_command_bits_grant",1)
      s.expect("io_link_command_bits_anchor",7); s.expect("io_link_command_bits_cycles",123)
      s.step(); s.poke("io_mem_enable",0); s.step()
      write(0x84,1); s.expect("io_link_enabled",0)
      assert((read(0x68)&0x21f)==0)
      val gen=read(0x68)>>16
      s.poke("io_channelReset",1); s.step(); s.poke("io_channelReset",0)
      assert((read(0x68)>>16)==gen+1)
    } finally s.finish()
  }
}
}
