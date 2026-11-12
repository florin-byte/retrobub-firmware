package net.gamebub.core.gameboy

import chisel3._
import lib.mem.{MemoryInterface, MemoryMap, RegisterMap}
import net.gamebub.core.md.MdTreadle
import org.scalatest.freespec.AnyFreeSpec

/** Exercise the system-domain memory interface driven by SpiReceiverFifo. */
class LinkRegisterPathHarness extends Module {
  val io = IO(new Bundle {
    val mem = new MemoryInterface(32, 32)
    val cpuWrite = Input(Bool())
    val cpuAddress = Input(UInt(8.W))
    val cpuData = Input(UInt(8.W))
    val sb = Output(UInt(8.W))
    val irqCount = Output(UInt(8.W))
    val commandCount = Output(UInt(8.W))
    val config = Output(UInt(4.W))
    val commandValid = Output(Bool())
    val command = Output(UInt(11.W))
  })
  val registers = Wire(new MemoryInterface(16, 32))
  io.mem <> MemoryMap(32, 32, Seq(0.U(4.W) -> registers))
  val regular = Wire(new MemoryInterface(16, 32))
  regular <> RegisterMap(16, 32, Seq(0 -> RegisterMap.Entry.r(0x1234.U(32.W))))
  val bridge = Module(new LinkHostBridge)
  LinkRegisterRouter.connect(registers, regular, bridge.io.mem)
  bridge.io.channelReset := false.B
  val serial = Module(new gameboy.Serial(gameboy.Gameboy.Configuration(skipBootrom = true)))
  serial.io.wireless <> bridge.io.link
  serial.io.enabled := io.cpuWrite
  serial.io.write := io.cpuWrite
  serial.io.address := io.cpuAddress
  serial.io.dataWrite := io.cpuData
  serial.io.clocker := 0.U.asTypeOf(new gameboy.Clocker)
  serial.io.cgbMode := false.B
  serial.io.divSerial := false.B
  serial.io.divSerialFast := false.B
  serial.io.serial.in := true.B
  serial.io.serial.clockIn := true.B
  io.sb := serial.io.debug.regData
  val irqs = RegInit(0.U(8.W))
  when (serial.io.interruptRequest) { irqs := irqs + 1.U }
  io.irqCount := irqs
  val commands = RegInit(0.U(8.W))
  when (bridge.io.link.command.valid) { commands := commands + 1.U }
  io.commandCount := commands
  io.config := bridge.io.link.config
  io.commandValid := bridge.io.link.command.valid
  io.command := bridge.io.link.command.bits
}

class LinkRegisterPathSpec extends AnyFreeSpec {
  "SPI receiver memory path writes CONFIG and COMMAND through the GB register decoder" in {
    val s = MdTreadle(new LinkRegisterPathHarness, "LinkRegisterPath")
    try {
      for (p <- Seq("io_mem_enable", "io_mem_write", "io_mem_address", "io_mem_dataWrite")) s.poke(p, 0)
      s.poke("io_cpuWrite", 0); s.poke("io_cpuAddress", 0); s.poke("io_cpuData", 0)
      s.poke("io_mem_writeStrobe", 15)
      s.poke("reset", 1); s.step(2); s.poke("reset", 0); s.step()
      def read(a: Int): BigInt = {
        s.poke("io_mem_enable", 1); s.poke("io_mem_write", 0); s.poke("io_mem_address", a)
        s.peek("io_mem_dataRead")
      }
      def write(a: Int, v: Int): Unit = {
        s.poke("io_mem_enable", 1); s.poke("io_mem_write", 1)
        s.poke("io_mem_address", a); s.poke("io_mem_dataWrite", v)
        // SpiReceiverFifo holds enable during its busy/ack cycles.
        s.step(2); s.poke("io_mem_enable", 0); s.poke("io_mem_write", 0)
      }
      assert(read(0) == 0x1234)
      assert(read(0x40) == BigInt("574c021f", 16))
      write(0x58, 1)
      write(0x44, 0x0d)
      assert(read(0x44) == 0x0d)
      s.expect("io_config", 0x0d)
      s.poke("io_mem_enable", 1); s.poke("io_mem_write", 1)
      s.poke("io_mem_address", 0x54); s.poke("io_mem_dataWrite", 0x2a5)
      s.expect("io_commandValid", 1); s.expect("io_command", 0x2a5)
      s.step(); s.expect("io_commandValid", 0); s.step(); s.poke("io_mem_enable", 0); s.expect("io_commandValid", 0)
      write(0x54, 0x305) // CONFIG opcode through COMMAND also changes the register.
      assert(read(0x44) == 5)
      write(0x58, 1)
      assert(read(0x44) == 0)
      assert(((read(0x48) >> 16) & 255) == 3) // CONTROL was accepted once.

      def cpu(a: Int, b: Int): Unit = {
        s.poke("io_mem_enable", 0); s.poke("io_mem_write", 0)
        s.poke("io_cpuWrite", 1); s.poke("io_cpuAddress", a); s.poke("io_cpuData", b)
        s.step(); s.poke("io_cpuWrite", 0)
      }
      def pop(op: Int): BigInt = {
        val e = read(0x4c)
        assert(((e >> 8) & 7) == op)
        write(0x50, 1)
        e
      }
      write(0x44, 13)
      val bytes = Seq(0x29, 0x00, 0x04, 0x08, 0x0c, 0x10, 0x14, 0x18,
        0x00, 0x08, 0x04, 0x10, 0x0c, 0x18, 0x14, 0x00)
      val commandsBefore = s.peek("io_commandCount")
      val irqsBefore = s.peek("io_irqCount")
      for ((b, i) <- bytes.zipWithIndex) {
        // Queue an ARMED hint before MASTER: a repeated POP used to lose MASTER.
        cpu(1, b); cpu(2, 0x80); cpu(2, 0x81)
        pop(1)
        val master = pop(3)
        assert((master & 255) == b)
        assert((read(0x48) & 31) == 0)
        val reply = if (i == 0) 0x55 else 0x80 + i
        write(0x54, ((master >> 24).toInt << 24) | 0x100 | reply)
        assert(s.peek("io_sb") == reply)
        assert(s.peek("io_irqCount") == irqsBefore + i + 1)
      }
      assert(s.peek("io_commandCount") == commandsBefore + 16)
      write(0x58, 1); write(0x44, 13)
      val slaveIrqs = s.peek("io_irqCount")
      for ((b, i) <- bytes.zipWithIndex) {
        val outgoing = if (i == 0) 0x55 else 0x40 + i
        cpu(1, outgoing); cpu(2, 0x80)
        pop(1)
        write(0x54, 0x200 | b)
        assert(s.peek("io_sb") == b)
        val done = pop(4)
        assert((done & 255) == outgoing)
        assert((read(0x48) & 31) == 0)
        assert(s.peek("io_irqCount") == slaveIrqs + i + 1)
        // No copied offer may complete the next arm before its REQ.
      }
      cpu(1, 0x66); cpu(2, 0x80); s.step()
      assert(s.peek("io_sb") == 0x66)
      assert(s.peek("io_irqCount") == slaveIrqs + 16)
      pop(1)
      assert((read(0x48) & 31) == 0)
    } finally s.finish()
  }
}
