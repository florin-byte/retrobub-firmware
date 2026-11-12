package gameboy

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils

/** Real CPU, serial, timer, PPU and HDMA; a tiny local ROM, not a game/BIOS asset. */
class WirelessCpuStallHarness extends Module {
  val io = IO(new Bundle {
    val fast = Input(Bool())
    val run = Input(Bool())
    val load = Input(Bool())
    val doubleSpeed = Input(Bool())
    val regEnable = Input(Bool())
    val regWrite = Input(Bool())
    val regAddress = Input(UInt(8.W))
    val regData = Input(UInt(8.W))
    val commandValid = Input(Bool())
    val command = Input(UInt(11.W))
    val stall = Output(Bool())
    val held = Output(Bool())
    val pc = Output(UInt(16.W))
    val divider = Output(UInt(16.W))
    val ly = Output(UInt(8.W))
    val irqFlags = Output(UInt(5.W))
    val timerIrqs = Output(UInt(16.W))
    val sbWrites = Output(UInt(16.W))
    val scWrites = Output(UInt(16.W))
    val sentinelWrites = Output(UInt(16.W))
    val masters = Output(UInt(16.W))
    val dma = Output(Bool())
  })
  val gb = Module(new Gameboy(Gameboy.Configuration(skipBootrom = true,
    wirelessGuardCycles = 1 << 20)))
  gb.io.isCgb := true.B
  gb.io.clockConfig.enable := io.run
  gb.io.clockConfig.provide8Mhz := true.B
  gb.io.bootRom.data := 0.U
  gb.io.joypad := 0.U.asTypeOf(gb.io.joypad)
  gb.io.serial.in := true.B
  gb.io.serial.clockIn := true.B
  gb.io.wireless.config := 13.U
  gb.io.wireless.clear := false.B
  gb.io.wireless.command.valid := io.commandValid
  gb.io.wireless.command.bits := io.command
  gb.io.saveState.address := 0.U
  gb.io.saveState.enable := false.B
  gb.io.saveState.write := false.B
  gb.io.saveState.dataWrite := 0.U
  gb.io.saveState.regEnable := io.regEnable
  gb.io.saveState.regWrite := io.regWrite
  gb.io.saveState.regAddress := io.regAddress
  gb.io.saveState.regDataWrite := io.regData
  gb.io.saveState.palAddr := 0.U
  gb.io.saveState.palWrite := false.B
  gb.io.saveState.palDataWrite := 0.U
  gb.io.saveState.cpuLoad := false.B
  gb.io.saveState.cpuIn := 0.U.asTypeOf(gb.io.saveState.cpuIn)
  gb.io.saveState.miscLoad := io.load
  gb.io.saveState.miscIn := 0.U.asTypeOf(gb.io.saveState.miscIn)
  gb.io.saveState.miscIn.sysctrl.bootOff := true.B
  gb.io.saveState.miscIn.sysctrl.cgbMode := true.B
  gb.io.saveState.miscIn.sysctrl.wramBank := 1.U
  gb.io.saveState.miscIn.sysctrl.doubleSpeed := io.doubleSpeed

  // DI; stack; clear IF; TIMA=C0; TAC=05; LCD on; SB=35; SC=81/83;
  // SB=72 (parked on normal clock); one WRAM sentinel; then a local loop.
  val program = Seq(0xf3,0x31,0xf0,0xdf,0xaf,0xe0,0x0f,
    0x3e,0xc0,0xe0,0x05,0x3e,0x05,0xe0,0x07,
    0x3e,0x91,0xe0,0x40,0x3e,0x35,0xe0,0x01,
    0x3e,0x81,0xe0,0x02,
    0x3e,0x72,0xe0,0x01,0x3e,0x99,0xea,0x00,0xc0,0x18,0xfe)
  val contents = Array.fill(512)(0)
  for (base <- Seq(0,256); (b,i) <- program.zipWithIndex) contents(base+i)=b
  val rom = VecInit(contents.toSeq.zipWithIndex.map { case (b,i) =>
    if (i == 24 || i == 280) Mux(io.fast, 0x83.U(8.W), 0x81.U(8.W)) else b.U(8.W)
  })
  // Emulated-cartridge timing: data changes only after reqStart, rather than
  // following the physical address combinationally. This checks T0 restart.
  gb.io.cartridge.dataIn := RegEnable(rom(gb.io.cartridge.reqAddress(8,0)),
    0.U(8.W), gb.io.cartridge.reqStart)

  io.stall := gb.io.wireless.stall
  io.held := gb.io.wireless.held
  io.pc := gb.io.cpuDebug.regPc
  io.divider := BoringUtils.bore(gb.timer.regDivider)
  io.ly := gb.io.saveState.miscOut.ppu.ly
  io.irqFlags := gb.io.saveState.cpuOut.regIF
  io.dma := BoringUtils.bore(gb.vramDma.io.active)
  val serialEn = BoringUtils.bore(gb.serial.io.enabled)
  val serialWr = BoringUtils.bore(gb.serial.io.write)
  val serialAddr = BoringUtils.bore(gb.serial.io.address)
  val ramEn = BoringUtils.bore(gb.workRam.io.enabled)
  val ramWr = BoringUtils.bore(gb.workRam.io.write)
  val ramAddr = BoringUtils.bore(gb.workRam.io.address)
  val timerIrq = BoringUtils.bore(gb.timer.io.interruptRequest)
  val sb = RegInit(0.U(16.W)); val sc = RegInit(0.U(16.W))
  val writes = RegInit(0.U(16.W)); val masters = RegInit(0.U(16.W))
  val timerIrqs = RegInit(0.U(16.W))
  when (serialEn && serialWr && serialAddr === 1.U) { sb := sb + 1.U }
  when (serialEn && serialWr && serialAddr === 2.U) { sc := sc + 1.U }
  when (ramEn && ramWr && ramAddr === 0.U) { writes := writes + 1.U }
  when (gb.io.wireless.event.valid && gb.io.wireless.event.bits(10,8) === 3.U) {
    masters := masters + 1.U
  }
  when (timerIrq) { timerIrqs := timerIrqs + 1.U }
  io.sbWrites := sb; io.scWrites := sc; io.sentinelWrites := writes
  io.masters := masters; io.timerIrqs := timerIrqs
}

object WirelessCpuStallEmit extends App {
  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(new WirelessCpuStallHarness, args,
    firtoolOpts = Array("--preserve-aggregate=1d-vec"))
}
