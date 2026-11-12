package net.gamebub.core.gba

import chisel3._
import chisel3.reflect.DataMirror
import chisel3.util.experimental.BoringUtils
import _root_.circt.stage.ChiselStage
import _root_.gba.GBA

/** GB-156: the console's own GBA module (HandheldGba built with GBA_DUAL_CORE=1) as the top
  * of a simulation: two production cores, DualCoreSync, the memory arbiters, both memory
  * controllers and the register map. The simulator plays what is outside the FPGA: the SRAM
  * and SDRAM chips, the clock generator (MMCME2_BASE model) and the ESP32 on the host bus.
  * Probes are observation only.
  */
class SimDualGba extends Module {
  require(HandheldGba.DualCore, "Generate with GBA_DUAL_CORE=1")
  val core = Module(new HandheldGba)
  // Only what the simulator plays; the other board interfaces get constant inputs.
  val io = IO(new Bundle {
    val clockIn50M = Input(Clock())
    val host = chiselTypeOf(core.io.host)
    val input = chiselTypeOf(core.io.input)
    val video = chiselTypeOf(core.io.video)
    val sram = chiselTypeOf(core.io.sram)
    val sdram = chiselTypeOf(core.io.sdram)
  })
  private def tie(d: Data): Unit = d match {
    case r: Record => r.elements.values.foreach(tie)
    case v: Vec[_] => v.foreach(tie)
    case e: Element =>
      if (DataMirror.directionOf(e) == ActualDirection.Input) {
        e match {
          case c: Clock => c := false.B.asClock
          case r: Reset => r := false.B
          case _ => e := 0.U.asTypeOf(e)
        }
      }
  }
  tie(core.io)
  core.io.clocks.clockIn50M := io.clockIn50M
  io.host <> core.io.host
  io.input <> core.io.input
  io.video <> core.io.video
  io.sram <> core.io.sram
  io.sdram <> core.io.sdram

  class CoreProbe extends Bundle {
    val enable = Bool()
    val rcntMode = UInt(2.W)
    val siocnt = UInt(15.W)
    val start = Bool()
    val dataA = UInt(16.W)
    val dataB0 = UInt(16.W)
    val dataB1 = UInt(16.W)
    val irq = Bool()
    val pixel = UInt(15.W)
    val pixelValid = Bool()
    val hblank = Bool()
    val vblank = Bool()
    val apuLeft = UInt(10.W)
    val apuRight = UInt(10.W)
    val ewramEnable = Bool()
    val ewramWrite = Bool()
    val ewramAddress = UInt(17.W)
    val ewramStall = Bool()
  }
  private def tap(gba: GBA): CoreProbe = {
    val p = Wire(new CoreProbe)
    p.enable := BoringUtils.bore(gba.io.enable)
    p.rcntMode := BoringUtils.bore(gba.link.regControlMode)
    p.siocnt := BoringUtils.bore(gba.link.regSiocnt)
    p.start := BoringUtils.bore(gba.link.regSiocntStart)
    p.dataA := BoringUtils.bore(gba.link.regDataA)
    p.dataB0 := BoringUtils.bore(gba.link.regDataB0)
    p.dataB1 := BoringUtils.bore(gba.link.regDataB1)
    p.irq := BoringUtils.bore(gba.link.io.irq)
    p.pixel := BoringUtils.bore(gba.io.ppu.pixel)
    p.pixelValid := BoringUtils.bore(gba.io.ppu.valid)
    p.hblank := BoringUtils.bore(gba.io.ppu.hblank)
    p.vblank := BoringUtils.bore(gba.io.ppu.vblank)
    p.apuLeft := BoringUtils.bore(gba.io.apu.left).asUInt
    p.apuRight := BoringUtils.bore(gba.io.apu.right).asUInt
    p.ewramEnable := BoringUtils.bore(gba.io.ewram.enable)
    p.ewramWrite := BoringUtils.bore(gba.io.ewram.write)
    p.ewramAddress := BoringUtils.bore(gba.io.ewram.address)
    p.ewramStall := BoringUtils.bore(gba.io.ewramStall)
    p
  }

  val probe = IO(Output(new Bundle {
    /** The visible core and the hidden one. */
    val c0 = new CoreProbe
    val c1 = new CoreProbe
    val frame = UInt(16.W)
    val syncStatus = UInt(32.W)
    val focus = Bool()
    val stalls = UInt(32.W)
    val cycles = UInt(32.W)
    val stallEwram1 = UInt(32.W)
    val stallCart1 = UInt(32.W)
    val stallEwram2 = UInt(32.W)
    val stallCart2 = UInt(32.W)
    val stallSync = UInt(32.W)
    val multiWait = Bool()
  }))
  probe.c0 := tap(core.gba)
  probe.c1 := tap(core.gba2.get)
  val sync = core.dualSync.get
  probe.frame := BoringUtils.bore(sync.frame)
  probe.syncStatus := BoringUtils.bore(sync.io.status)
  probe.focus := BoringUtils.bore(core.regCoreFocus)
  probe.stalls := BoringUtils.bore(core.statRegStalls)
  probe.cycles := BoringUtils.bore(core.statRegCycles)
  val stats = core.dualStallStats.get
  probe.stallEwram1 := BoringUtils.bore(stats(0))
  probe.stallCart1 := BoringUtils.bore(stats(1))
  probe.stallEwram2 := BoringUtils.bore(stats(2))
  probe.stallCart2 := BoringUtils.bore(stats(3))
  probe.stallSync := BoringUtils.bore(stats(4))
  probe.multiWait := BoringUtils.bore(core.gba.io.wireless.multiWait)
}

object SimDualGba extends App {
  // As HandheldTop does for board revision 2. The display clock only sets an MMCM divider.
  net.gamebub.framework.interface.ClocksV0.getClockDisplayHz = platform.handheld.display.ILI9488.getClockDisplayHz
  net.gamebub.framework.interface.SdramV0.numChips = 1
  ChiselStage.emitSystemVerilogFile(new SimDualGba, args,
    firtoolOpts = Array("--preserve-aggregate=1d-vec"))
}
