package net.gamebub.core.gameboy

import chisel3._
import chisel3.util.Cat
import _root_.circt.stage.ChiselStage
import gameboy.cart.emu.{EmuCartConfig, EmuCartridgeDataAccess}
import gameboy.ppu.PpuOutput

/** GB-146: one simulated console as in HandheldGameboy with GB_DUAL_CORE=1:
  * a visible and a hidden production core on a local link cable, both clocked
  * by one enable, with the production DualCoreSync deciding buttons and stalls.
  * The simulator plays the host (ESP32) and the cartridge memories.
  */
class SimDualGameboy extends Module {
  val io = IO(new Bundle {
    /** Console-side clock gate (focus, memory waits, crystal drift). */
    val gate = Input(Bool())
    val enable = Input(Bool())
    val run = Input(Bool())
    val delay = Input(UInt(3.W))
    val localButtons = Input(UInt(8.W))
    val partnerWrite = Input(Bool())
    val partnerData = Input(UInt(24.W))
    val pop = Input(Bool())
    val phase = Input(UInt(18.W))
    val visibleLate = Input(Bool())
    val stall = Output(Bool())
    val request = Output(Bool())
    val local = Output(UInt(32.W))
    val digestVisible = Output(UInt(32.W))
    val digestHidden = Output(UInt(32.W))
    val status = Output(UInt(32.W))
    val waitCycles = Output(UInt(32.W))
    val serialStatVisible = Output(UInt(32.W))
    val serialStatHidden = Output(UInt(32.W))
    val serialStatCross = Output(UInt(32.W))
    val writeStat = Output(UInt(32.W))
    val rejectStat = Output(UInt(32.W))

    val cartConfig = Input(new EmuCartConfig())
    val dataAccess0 = new EmuCartridgeDataAccess()
    val dataAccess1 = new EmuCartridgeDataAccess()
    val ppu = new PpuOutput
    val pc0 = Output(UInt(16.W))
    val pc1 = Output(UInt(16.W))
    val serialIrq0 = Output(Bool())
    val serialIrq1 = Output(Bool())
  })

  val sync = Module(new DualCoreSync)
  val core0 = Module(new SimCableGameboy) // visible
  val core1 = Module(new SimCableGameboy) // hidden
  val cores = Seq(core0, core1)

  val tick = io.gate && !sync.io.stall
  sync.io.enable := io.enable
  sync.io.run := io.run
  sync.io.delay := io.delay
  sync.io.tick := tick
  sync.io.localButtons := io.localButtons
  sync.io.partnerWrite := io.partnerWrite
  sync.io.partnerData := io.partnerData
  sync.io.pop := io.pop
  sync.io.phase := io.phase
  sync.io.visibleLate := io.visibleLate
  sync.io.serialVisible := Cat(core0.io.serialDebug.regEnable, core0.io.serialDebug.regClockMode)
  sync.io.serialHidden := Cat(core1.io.serialDebug.regEnable, core1.io.serialDebug.regClockMode)
  io.serialStatVisible := sync.io.serialStatVisible
  io.serialStatHidden := sync.io.serialStatHidden
  io.serialStatCross := sync.io.serialStatCross
  io.writeStat := sync.io.writeStat
  io.rejectStat := sync.io.rejectStat
  io.stall := sync.io.stall
  io.request := sync.io.request
  io.local := sync.io.local
  io.digestVisible := sync.io.digestVisible
  io.digestHidden := sync.io.digestHidden
  io.status := sync.io.status
  io.waitCycles := sync.io.waitCycles

  core0.io.clockConfig.enable := tick && !sync.io.holdVisible
  core1.io.clockConfig.enable := tick && !sync.io.holdHidden
  for (core <- cores) {
    core.io.clockConfig.provide8Mhz := true.B
    core.io.cartConfig := io.cartConfig
    core.io.saveState.savePulse := false.B
    core.io.saveState.loadPulse := false.B
    core.io.saveState.resetCore := false.B
    core.io.saveState.sdram.done := false.B
    core.io.saveState.sdram.dataRead := 0.U
  }
  core0.io.joypad := sync.io.visibleButtons.asTypeOf(core0.io.joypad)
  core1.io.joypad := sync.io.hiddenButtons.asTypeOf(core1.io.joypad)
  sync.io.sampleVisible := DualCoreSync.traceSample(core0.io.ppu, core0.io.cpuDebug, core0.io.serialDebug, core0.io.apu)
  sync.io.sampleHidden := DualCoreSync.traceSample(core1.io.ppu, core1.io.cpuDebug, core1.io.serialDebug, core1.io.apu)

  // Local link cable, as in HandheldGameboy.
  core0.cable.in := core1.cable.out
  core0.cable.clockIn := !core1.cable.clockEnable || core1.cable.clockOut
  core1.cable.in := core0.cable.out
  core1.cable.clockIn := !core0.cable.clockEnable || core0.cable.clockOut

  io.dataAccess0 <> core0.io.dataAccess
  io.dataAccess1 <> core1.io.dataAccess
  io.ppu <> core0.io.ppu
  io.pc0 := core0.io.cpuDebug.regPc
  io.pc1 := core1.io.cpuDebug.regPc
  io.serialIrq0 := core0.serialInterrupt
  io.serialIrq1 := core1.serialInterrupt
}

object SimDualGameboy extends App {
  ChiselStage.emitSystemVerilogFile(new SimDualGameboy, args,
    firtoolOpts = Array("--preserve-aggregate=1d-vec"))
}
