package net.gamebub.core.gba

import chisel3._
import chisel3.util.experimental.BoringUtils
import _root_.circt.stage.ChiselStage
import _root_.gba.link.Link

/** GB-154: the headless GBA core with its physical link port brought out, so the
  * simulator can wire two cores together like a multiplayer cable. The wireless
  * bridge stays off (as in SimGba). Probes are observation only.
  */
class SimCableGba(linkEmulatedTime: Boolean) extends SimGba(linkEmulatedTime) {
  val cable = IO(new Link.Interface)
  cable.out := gba.io.link.out
  cable.dir := gba.io.link.dir
  // Last connection wins over SimGba's "nothing plugged in" constants.
  gba.io.link.in := cable.in

  val probe = IO(Output(new Bundle {
    val multiWait = Bool()
    val rcntMode = UInt(2.W)
    val siocnt = UInt(15.W)
    val start = Bool()
    val dataA = UInt(16.W)
    val dataB0 = UInt(16.W)
    val dataB1 = UInt(16.W)
    val irq = Bool()
    val ewramEnable = Bool()
    val ewramWrite = Bool()
    val ewramAddress = UInt(17.W)
    val romRead = Bool()
    val romAddress = UInt(24.W)
  }))
  probe.multiWait := gba.io.wireless.multiWait
  probe.rcntMode := BoringUtils.bore(gba.link.regControlMode)
  probe.siocnt := BoringUtils.bore(gba.link.regSiocnt)
  probe.start := BoringUtils.bore(gba.link.regSiocntStart)
  probe.dataA := BoringUtils.bore(gba.link.regDataA)
  probe.dataB0 := BoringUtils.bore(gba.link.regDataB0)
  probe.dataB1 := BoringUtils.bore(gba.link.regDataB1)
  probe.irq := BoringUtils.bore(gba.link.io.irq)
  probe.ewramEnable := gba.io.ewram.enable
  probe.ewramWrite := gba.io.ewram.write
  probe.ewramAddress := gba.io.ewram.address
  probe.romRead := emuCart.io.rom.enable && emuCart.io.rom.ready
  probe.romAddress := emuCart.io.rom.address
}

object SimCableGba extends App {
  // GB-158: SIM_LINK_REALTIME=1 keeps the stock UART timer (bit time in system clocks).
  ChiselStage.emitSystemVerilogFile(new SimCableGba(!sys.env.contains("SIM_LINK_REALTIME")), args,
    firtoolOpts = Array("--preserve-aggregate=1d-vec"))
}
