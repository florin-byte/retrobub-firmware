package net.gamebub.core.gameboy

import chisel3._
import chisel3.util.experimental.BoringUtils
import _root_.circt.stage.ChiselStage
import _root_.gameboy.SerialIo

/** GB-142: the headless core with its physical link port brought out, so the
  * simulator can wire two cores together like a link cable. No wireless bridge.
  */
class SimCableGameboy extends SimGameboy {
  val cable = IO(new SerialIo)
  cable.out := gameboy.io.serial.out
  cable.clockEnable := gameboy.io.serial.clockEnable
  cable.clockOut := gameboy.io.serial.clockOut
  // Last connection wins over SimGameboy's "disconnected serial" constants.
  gameboy.io.serial.in := cable.in
  gameboy.io.serial.clockIn := cable.clockIn
  // Registered observation of the serial interrupt, as in SimLinkGameboy.
  val serialInterrupt = IO(Output(Bool()))
  serialInterrupt := RegNext(BoringUtils.bore(gameboy.serial.io.interruptRequest), false.B)
}

object SimCableGameboy extends App {
  ChiselStage.emitSystemVerilogFile(new SimCableGameboy, args,
    firtoolOpts = Array("--preserve-aggregate=1d-vec"))
}
