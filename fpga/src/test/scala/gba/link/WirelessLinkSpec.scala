package gba.link

import chisel3._
import gba.MmioTarget
import net.gamebub.core.md.MdTreadle
import org.scalatest.freespec.AnyFreeSpec

/** Exercise real SIOCNT byte masks, busy readback, receive registers and IRQ. */
class WirelessLinkSpec extends AnyFreeSpec {
  private def run(name: String)(f: MdTreadle.Sim => Unit): Unit = {
    val s = MdTreadle(new Link(128),name)
    try {
      for (p <- Seq("io_mmio_address","io_mmio_request","io_mmio_write","io_mmio_mask",
        "io_mmio_dataWrite","io_wireless_clear","io_wireless_command_valid",
        "io_wireless_command_bits_id","io_wireless_command_bits_data",
        "io_wireless_command_bits_error","io_wireless_command_bits_begin",
        "io_wireless_command_bits_multiEnabled","io_wireless_command_bits_multiParent",
        "io_wireless_command_bits_sync", "io_wireless_command_bits_cycles",
        "io_wireless_command_bits_grantEnabled", "io_wireless_command_bits_grant", "io_wireless_command_bits_anchor",
        "io_wireless_command_bits_syncEnabled", "io_wireless_command_bits_multiReady")) s.poke(p,0)
      for (p <- Seq("sc","sd","si","so")) s.poke(s"io_port_in_$p",1)
      s.poke("io_enable",1); s.poke("io_wireless_enabled",1)
      s.poke("reset",1); s.step(2); s.poke("reset",0); s.step()
      f(s)
    } finally s.finish()
  }
  private def write(s: MdTreadle.Sim, a: Int, d: BigInt, mask: Int = 15): Unit = {
    s.poke("io_mmio_address",a/4); s.poke("io_mmio_dataWrite",d)
    s.poke("io_mmio_mask",mask); s.poke("io_mmio_request",1); s.poke("io_mmio_write",1)
    s.step(); s.poke("io_mmio_request",0); s.poke("io_mmio_write",0)
  }
  private def read(s: MdTreadle.Sim, a: Int): BigInt = {
    s.poke("io_mmio_address",a/4); s.poke("io_mmio_request",1); s.poke("io_mmio_write",0)
    s.peek("io_mmio_dataRead")
  }
  private def waitForMultiIrq(s: MdTreadle.Sim): Unit = {
    s.expect("io_irq", 0)
    var remaining = 5760
    while (remaining > 0 && s.peek("io_irq") == 0) {
      s.step()
      remaining -= 1
    }
    s.expect("io_irq", 1)
  }

  "Normal 32 start, busy, reply, IRQ and byte-masked abort" in {
    run("GbaLinkRegisters") { s =>
      write(s,0x128,0x5001); write(s,0x120,BigInt("87654321",16))
      write(s,0x128,0x81,1); s.step()
      s.expect("io_wireless_busy",1)
      assert((read(s,0x128)&0x80)==0x80)
      s.poke("io_mmio_request",0)
      for (p <- Seq("sc","sd","si","so")) s.expect(s"io_port_dir_$p",0)
      s.poke("io_wireless_command_bits_id",1)
      s.poke("io_wireless_command_bits_data",BigInt("fedcba98",16)); s.poke("io_wireless_command_valid",1)
      s.step(); s.poke("io_wireless_command_valid",0); s.expect("io_irq",1)
      s.step(); s.expect("io_irq",0)
      assert(read(s,0x120)==BigInt("fedcba98",16)); assert((read(s,0x128)&0x80)==0)
      s.poke("io_mmio_request",0)
      write(s,0x128,0x81,1); s.step(); s.expect("io_wireless_busy",1)
      write(s,0x128,0x01,1); s.expect("io_wireless_busy",0); s.expect("io_irq",0)
    }
  }
  for (parent <- Seq(false, true)) {
    s"Multiplayer parent=$parent exposes a coherent round and serial IRQ" in {
      run(s"GbaMultiRegisters$parent") { s =>
        s.poke("io_wireless_command_bits_multiEnabled", 1)
        s.poke("io_wireless_command_bits_multiParent", parent)
        s.poke("io_wireless_command_bits_multiReady", 1)
        write(s, 0x128, 0x6003)
        write(s, 0x128, (if (parent) BigInt("12340000", 16) else BigInt("abcd0000", 16)), 12)
        val idle = if (parent) 0x0b else 0x0f
        assert((read(s, 0x128) & 0xff) == idle)
        s.poke("io_mmio_request", 0)
        s.expect("io_wireless_multiMode", 1)
        s.poke("io_wireless_command_bits_id", 1)
        if (parent) {
          write(s, 0x128, 0x83, 1)
        } else {
          s.poke("io_wireless_command_bits_begin", 1)
          s.poke("io_wireless_command_valid", 1)
        }
        s.step()
        s.poke("io_wireless_command_valid", 0)
        s.poke("io_wireless_command_bits_begin", 0)
        assert((read(s, 0x128) & 0xff) == (idle | 0x80))
        s.poke("io_mmio_request", 0)
        val reply = if (parent) BigInt("abcd1234", 16) else BigInt("1234", 16)
        s.poke("io_wireless_command_bits_data", reply)
        s.poke("io_wireless_command_valid", 1)
        s.step()
        s.poke("io_wireless_command_valid", 0)
        assert((read(s, 0x128) & 0x80) != 0)
        s.poke("io_mmio_request", 0)
        waitForMultiIrq(s)
        val expectedResult = BigInt("abcd1234", 16)
        assert((read(s, 0x128) & 0x80) != 0)
        assert(read(s, 0x120) == expectedResult)
        s.poke("io_mmio_request", 0)
        s.step()
        s.expect("io_irq", 0)
        assert(read(s, 0x120) == expectedResult)
        assert(read(s, 0x124) == BigInt("ffffffff", 16))
        assert((read(s, 0x128) & 0xff) == (idle | (if (parent) 0 else 0x10)))
      }
    }
  }
  private class MultiPair(wireless: Boolean) extends Module {
    val io = IO(new Bundle {
      val mmio = Vec(2, new MmioTarget())
      val irq = Output(Vec(2, Bool()))
      val valid = Input(Vec(2, Bool()))
      val begin = Input(Vec(2, Bool()))
      val data = Input(Vec(2, UInt(32.W)))
      val token = Input(Vec(2, UInt(28.W)))
      val guestEnable = Output(Vec(2, Bool()))
      val eventValid = Output(Vec(2, Bool()))
      val eventData = Output(Vec(2, UInt(32.W)))
    })
    val links = Seq.fill(2)(Module(new Link(40000)))
    for ((link, i) <- links.zipWithIndex) {
      link.io.mmio <> io.mmio(i)
      link.io.enable := !link.io.wireless.multiWait
      io.irq(i) := link.io.irq
      io.guestEnable(i) := link.io.enable
      val port = link.io.wireless
      port.enabled := wireless.B
      port.clear := false.B
      port.command.valid := io.valid(i)
      port.command.bits.id := io.token(i)
      port.command.bits.data := io.data(i)
      port.command.bits.error := false.B
      port.command.bits.begin := io.begin(i)
      port.command.bits.multiEnabled := wireless.B
      port.command.bits.multiParent := (i == 0).B
      port.command.bits.multiReady := wireless.B
      port.command.bits.sync := false.B
      port.command.bits.cycles := 0.U
      port.command.bits.syncEnabled := false.B
      port.command.bits.grantEnabled := false.B
      port.command.bits.pairFromHost := false.B
      port.command.bits.grant := false.B
      port.command.bits.anchor := 0.U
      io.eventValid(i) := port.event.valid
      io.eventData(i) := port.event.bits.data
    }

    // SC and SD are shared lines with pull-ups. SO daisy-chains into SI.
    val parent = links.head.io.port
    val child = links(1).io.port
    val sc = !parent.dir.sc || parent.out.sc
    val sd = (!parent.dir.sd || parent.out.sd) && (!child.dir.sd || child.out.sd)
    parent.in.sc := sc
    child.in.sc := sc
    parent.in.sd := sd
    child.in.sd := sd
    parent.in.si := false.B
    child.in.si := !parent.dir.so || parent.out.so
    parent.in.so := true.B
    child.in.so := true.B
  }

  private case class MultiRound(
    idle: Seq[BigInt],
    busy: Seq[Int],
    irq: Seq[Int],
    firstIdle: Seq[Int],
    slotsAtIrq: Seq[BigInt],
    slotsAtIdle: Seq[BigInt],
    slots23: Seq[BigInt],
    finalStatus: Seq[BigInt],
    phases: Seq[Seq[(Int, BigInt, BigInt)]]
  )

  private def compareRound(wireless: Boolean): MultiRound = {
    val s = MdTreadle(new MultiPair(wireless), s"GbaMultiCompare$wireless")
    val sides = 0 until 2
    val busyAt = Array.fill(2)(-1)
    val irqAt = Array.fill(2)(-1)
    val idleAt = Array.fill(2)(-1)
    val irqSlots = Array.fill(2)(BigInt(0))
    val idleSlots = Array.fill(2)(BigInt(0))
    val checkpoints = Set(1, 2621, 2622, 2623, 5244, 5245, 5246, 5247, 5757)
    val phases = Seq.fill(2)(scala.collection.mutable.ArrayBuffer.empty[(Int, BigInt, BigInt)])
    val guestCycles = Array.fill(2)(0)
    val busyGuestCycle = Array.fill(2)(0)
    var cycle = 0

    def mmio(i: Int, field: String) = s"io_mmio_${i}_$field"
    def address(i: Int, a: Int): Unit = s.poke(mmio(i, "address"), a / 4)
    def status(i: Int): BigInt = s.peek(mmio(i, "dataRead")) & 0xffff
    def readSlots(i: Int, a: Int): BigInt = {
      address(i, a)
      val result = s.peek(mmio(i, "dataRead"))
      address(i, 0x128)
      result
    }
    def step(afterEdge: => Unit = ()): Unit = {
      for (i <- sides if s.peek(s"io_guestEnable_$i") != 0) guestCycles(i) += 1
      s.step()
      afterEdge
      cycle += 1
      for (i <- sides) {
        val busy = (status(i) & 0x80) != 0
        if (busy && busyAt(i) < 0) {
          busyAt(i) = cycle
          busyGuestCycle(i) = guestCycles(i)
        }
        val elapsed = guestCycles(i) - busyGuestCycle(i)
        if (busy && checkpoints(elapsed) && phases(i).lastOption.forall(_._1 != elapsed))
          phases(i) += ((elapsed, status(i) & 0x30, readSlots(i, 0x120)))
        if (!busy && busyAt(i) >= 0 && idleAt(i) < 0) {
          idleAt(i) = cycle
          idleSlots(i) = readSlots(i, 0x120)
        }
        if (s.peek(s"io_irq_$i") != 0) {
          assert(irqAt(i) < 0, s"Duplicate IRQ on side $i")
          irqAt(i) = cycle
          irqSlots(i) = readSlots(i, 0x120)
        }
      }
    }
    def writeWord(i: Int, value: BigInt, mask: Int): Unit = {
      s.poke(mmio(i, "dataWrite"), value)
      s.poke(mmio(i, "mask"), mask)
      s.poke(mmio(i, "write"), 1)
      step(s.poke(mmio(i, "write"), 0))
    }

    try {
      for (i <- sides) {
        for (field <- Seq("address", "request", "write", "mask", "dataWrite")) s.poke(mmio(i, field), 0)
        s.poke("io_token_" + i, 1)
        for (field <- Seq("valid", "begin", "data")) s.poke(s"io_${field}_$i", 0)
      }
      s.poke("reset", 1); s.step(2); s.poke("reset", 0)
      for (i <- sides) {
        address(i, 0x128)
        s.poke(mmio(i, "request"), 1)
        writeWord(i, 0x6003, 3)
        writeWord(i, if (i == 0) BigInt(0x1234) << 16 else BigInt(0), 12)
      }
      s.step(2)
      val idle = sides.map(status)
      cycle = 0
      writeWord(0, 0x83, 1)
      while (busyAt(0) < 0 && cycle < 16) step()
      assert(busyAt(0) >= 0)
      writeWord(0, BigInt(0x5678) << 16, 12)
      if (wireless) {
        while (cycle < 7500) step()
        s.poke("io_begin_1", 1); s.poke("io_valid_1", 1)
        step()
        s.poke("io_begin_1", 0); s.poke("io_valid_1", 0)
      }
      while (busyAt(1) < 0 && cycle < 10000) step()
      assert(busyAt.forall(_ >= 0), s"Both sides must enter BUSY, wireless=$wireless")

      // Change SEND after the wire word was captured, then again after the
      // wireless reply was issued but before the cable child's TX ends.
      writeWord(1, BigInt(0x55aa) << 16, 12)
      for (_ <- 0 until 64) step()
      var childReply = BigInt(0)
      if (wireless) {
        s.poke("io_data_1", 0x1234); s.poke("io_valid_1", 1)
        s.expect("io_eventValid_1", 1)
        childReply = s.peek("io_eventData_1") & 0xffff
      }
      step()
      s.poke("io_valid_1", 0)
      writeWord(1, BigInt(0x3ede) << 16, 12)
      if (wireless) {
        for (_ <- 0 until 9500) step()
        s.poke("io_data_0", (childReply << 16) | 0x1234)
        s.poke("io_valid_0", 1)
        step()
        s.poke("io_valid_0", 0)
      }
      while (idleAt.exists(_ < 0) && cycle < 25000) step()
      assert(idleAt.forall(_ >= 0) && irqAt.forall(_ >= 0), s"Both rounds must finish, wireless=$wireless")
      val finalStatus = sides.map(status)
      MultiRound(idle, busyAt.toSeq, irqAt.toSeq, idleAt.toSeq, irqSlots.toSeq, idleSlots.toSeq,
        sides.map(readSlots(_, 0x124)), finalStatus, phases.map(_.toVector))
    } finally s.finish()
  }

  "Multiplayer wireless round exposes cable words and ID in phase order" in {
    val cable = compareRound(wireless = false)
    val wifi = compareRound(wireless = true)
    info(s"Cable: $cable")
    info(s"Wi-Fi: $wifi")
    assert(cable.slotsAtIdle == Seq(BigInt(0x5678), BigInt("3ede1234", 16)))
    assert(wifi.idle == cable.idle)
    assert(cable.phases.forall(_.head._2 == 0))
    assert(wifi.phases == cable.phases)
    assert(wifi.slotsAtIrq == cable.slotsAtIrq)
    assert((cable.finalStatus.head & 0x30) == 0 && (cable.finalStatus(1) & 0x30) == 0x10)
    assert((cable.slots23 ++ wifi.slots23).forall(_ == BigInt("ffffffff", 16)))
    assert(wifi.slotsAtIdle == cable.slotsAtIdle)
    assert(wifi.busy(1) - wifi.busy.head > cable.busy(1) - cable.busy.head)
  }

  private case class Cycle(sim: Int, gba: Int)

  private class SideRound {
    var busy: Option[Cycle] = None
    var irq: Option[Cycle] = None
    var idle: Option[Cycle] = None
    val visible = Array.fill[Option[Cycle]](2)(None)
    var atIrq = ""
    var atIdle = ""

    override def toString: String =
      s"busy=$busy visible=" + visible.mkString("/") +
        s" irq=$irq ($atIrq) idle=$idle ($atIdle)"
  }

  private def traceRounds(delays: Option[(Int, Int)], baud: Int = 3, rounds: Int = 3): Seq[Seq[SideRound]] = {
    val s = MdTreadle(new MultiPair(delays.isDefined), "GbaMultiRounds" + delays.fold("Cable")(_._1.toString))
    val sides = 0 until 2
    val trace = Seq.fill(rounds, 2)(new SideRound)
    val gba = Array.fill(2)(0)
    val started = Array.fill(2)(0)
    val busyBefore = Array.fill(2)(false)
    val sendNext = Array.fill[Option[Int]](2)(None)
    var sim = 0
    var startNext = true
    var beginAt = -1
    var childDoneAt = -1
    var parentDoneAt = -1
    var parentWord = BigInt(0)
    var result = BigInt(0)

    def port(field: String, i: Int) = s"io_" + field + "_" + i
    def mmio(i: Int, field: String) = s"io_mmio_" + i + "_" + field
    def pokeMmio(i: Int, field: String, value: BigInt): Unit = s.poke(mmio(i, field), value)
    def write(i: Int, data: BigInt, mask: Int): Unit = {
      pokeMmio(i, "dataWrite", data)
      pokeMmio(i, "mask", mask)
      pokeMmio(i, "write", 1)
    }
    def word(i: Int, round: Int): Int = (if (i == 0) 0x3100 else 0x7200) + round
    def slots(i: Int): BigInt = {
      pokeMmio(i, "address", 0x120 / 4)
      val data = s.peek(mmio(i, "dataRead"))
      pokeMmio(i, "address", 0x128 / 4)
      data
    }
    def command(i: Int, id: Int, data: BigInt, begin: Boolean): Unit = {
      s.poke(port("token", i), id)
      s.poke(port("data", i), data)
      s.poke(port("begin", i), begin)
      s.poke(port("valid", i), 1)
    }

    try {
      for (i <- sides) {
        for (field <- Seq("valid", "begin", "data", "token")) s.poke(port(field, i), 0)
        pokeMmio(i, "address", 0x128 / 4)
        pokeMmio(i, "request", 1)
        write(i, 0, 0)
      }
      s.poke("reset", 1); s.step(2); s.poke("reset", 0)
      for (i <- sides) write(i, (BigInt(word(i, 1)) << 16) | (0x6000 + baud), 15)
      s.step()
      for (i <- sides) pokeMmio(i, "write", 0)
      s.step(2)

      while (sim < 100000 && trace.last.exists(_.idle.isEmpty)) {
        for (i <- sides) {
          pokeMmio(i, "write", 0)
          s.poke(port("valid", i), 0)
        }
        if (startNext) {
          write(0, 0x80 + baud, 1)
          startNext = false
        }
        for (i <- sides; next <- sendNext(i) if s.peek(port("guestEnable", i)) != 0) {
          write(i, BigInt(word(i, next)) << 16, 12)
          sendNext(i) = None
          if (i == 0) startNext = true
        }
        delays.foreach { case (_, replyDelay) =>
          if (sim == beginAt) {
            command(1, started(0), 0, begin = true)
            childDoneAt = sim + 8
          }
          if (sim == childDoneAt) {
            command(1, started(0), parentWord, begin = false)
            s.expect(port("eventValid", 1), 1)
            result = ((s.peek(port("eventData", 1)) & 0xffff) << 16) | parentWord
            parentDoneAt = sim + replyDelay
          }
          if (sim == parentDoneAt) command(0, started(0), result, begin = false)
        }
        for (i <- sides if s.peek(port("guestEnable", i)) != 0) gba(i) += 1
        s.step()
        sim += 1

        for (i <- sides) {
          val status = s.peek(mmio(i, "dataRead"))
          val busy = (status & 0x80) != 0
          val rising = busy && !busyBefore(i)
          val time = Cycle(sim, gba(i))
          if (rising) {
            assert(started(i) < rounds, s"Unexpected extra round on side $i at $time")
            started(i) += 1
            trace(started(i) - 1)(i).busy = Some(time)
            if (i == 0) delays.foreach { case (requestDelay, _) =>
              beginAt = sim + requestDelay
              parentWord = status >> 16
            }
          }
          if (started(i) > 0) {
            val round = trace(started(i) - 1)(i)
            val irq = s.peek(port("irq", i)) != 0
            val falling = !busy && busyBefore(i)
            if (!rising && (falling || irq || (busy && round.visible.exists(_.isEmpty)))) {
              val data = slots(i)
              for (slot <- sides if round.visible(slot).isEmpty && ((data >> (16 * slot)) & 0xffff) != 0xffff)
                round.visible(slot) = Some(time)
              if (irq) {
                assert(round.irq.isEmpty, s"Duplicate IRQ on side $i in round " + started(i))
                round.irq = Some(time)
                round.atIrq = data.toString(16)
                if (started(i) < rounds) sendNext(i) = Some(started(i) + 1)
              }
              if (falling) {
                round.idle = Some(time)
                round.atIdle = data.toString(16)
              }
            }
          }
          busyBefore(i) = busy
        }
      }
      for ((round, n) <- trace.zipWithIndex; (side, i) <- round.zipWithIndex) {
        assert(side.irq.isDefined && side.idle.isDefined,
          s"Round " + (n + 1) + s" did not complete on side $i at simulator cycle $sim: $side")
      }
      trace
    } finally s.finish()
  }

  "Multiplayer cable completion timing at all baud settings" in {
    for (baud <- 0 until 4) {
      val sides = traceRounds(None, baud = baud, rounds = 1).head
      val duration = sides.map(side => side.idle.get.gba - side.busy.get.gba)
      val parent = Seq(63431, 16245, 11001, 5759)(baud)
      assert(duration == Seq(parent, parent + 1))
      info(s"Cable baud $baud BUSY duration: $duration")
    }
  }

  "Consecutive Multiplayer rounds trace when cable and wireless guests see each result" in {
    val cable = traceRounds(None)
    for ((label, rounds) <- Seq("Cable" -> cable,
      "Wi-Fi 7500/9500" -> traceRounds(Some((7500, 9500))),
      "Wi-Fi 2000/14000" -> traceRounds(Some((2000, 14000))))) {
      for ((round, n) <- rounds.zipWithIndex) {
        info(label + " round " + (n + 1) + ": " + round.mkString(" | "))
        val expected = ((BigInt(0x7201 + n) << 16) | (0x3101 + n)).toString(16)
        for ((side, reference) <- round.zip(cable(n))) {
          assert(side.atIrq == expected && side.atIdle == expected)
          assert(side.idle.get.gba - side.busy.get.gba == reference.idle.get.gba - reference.busy.get.gba)
        }
      }
    }
  }

  "Multiplayer child latches once at BEGIN and takes a fresh word after abort" in {
    run("GbaMultiChildLatch") { s =>
      def send(word: Int): Unit = write(s, 0x128, BigInt(word) << 16, 12)
      def ready(value: Int): Unit = s.poke("io_wireless_command_bits_multiReady", value)
      def begin(id: Int): Unit = {
        s.poke("io_wireless_command_bits_id", id)
        s.poke("io_wireless_command_bits_begin", 1)
        s.poke("io_wireless_command_valid", 1)
        s.step()
        s.poke("io_wireless_command_valid", 0)
        s.poke("io_wireless_command_bits_begin", 0)
      }

      s.poke("io_wireless_command_bits_multiEnabled", 1)
      write(s, 0x128, 0x6003)
      send(0x1111)
      ready(1); s.step()
      s.expect("io_wireless_busy", 1)
      begin(1)

      ready(0)
      s.expect("io_wireless_event_valid", 1)
      assert((s.peek("io_wireless_event_bits_meta") >> 30) == 3)
      s.step(2)
      s.expect("io_wireless_busy", 0)

      send(0x2222)
      ready(1); s.step()
      s.expect("io_wireless_busy", 1)
      send(0xabcd) // The armed child has not received BEGIN yet.
      begin(2)
      assert((read(s, 0x128) & 0x80) == 0x80)
      s.poke("io_mmio_request", 0)
      send(0x55aa)
      begin(2) // A repeated BEGIN must not replace the captured word.

      s.poke("io_wireless_command_bits_data", 0x1234)
      s.poke("io_wireless_command_valid", 1)
      s.expect("io_wireless_event_valid", 1)
      assert((s.peek("io_wireless_event_bits_meta") >> 30) == 2)
      s.expect("io_wireless_event_bits_data", 0xabcd)
      s.step()
      s.poke("io_wireless_command_valid", 0)
      waitForMultiIrq(s)
      assert(read(s, 0x120) == BigInt("55aa1234", 16))
    }
  }

  "wireless disabled retains physical Normal 32 clock and unsupported 8-bit mode" in {
    run("GbaLinkPhysical") { s =>
      s.poke("io_wireless_enabled",0)
      write(s,0x128,0x5083); s.step(); s.expect("io_wireless_busy",0)
      s.expect("io_port_dir_sc",1); s.expect("io_wireless_event_valid",0)
    }
  }
  "synchronized parent retains a START issued before peer READY" in {
    run("GbaSyncEarlyStart") { s =>
      s.poke("io_wireless_command_bits_multiEnabled", 1)
      s.poke("io_wireless_command_bits_multiParent", 1)
      s.poke("io_wireless_command_bits_syncEnabled", 1)
      s.poke("io_wireless_command_bits_multiReady", 0)
      write(s, 0x134, 0)
      write(s, 0x128, 0x12346083L)
      s.step()
      s.expect("io_wireless_busy", 1)
      s.expect("io_wireless_multiWait", 1)
      assert((read(s, 0x128) & 0x80) != 0)
      s.poke("io_enable", 0); s.step(8)
      s.expect("io_wireless_busy", 1)
      s.poke("io_wireless_command_bits_multiReady", 1)
      s.poke("io_wireless_command_bits_id", 1)
      s.poke("io_wireless_command_bits_data", BigInt("abcd1234", 16))
      s.poke("io_wireless_command_valid", 1); s.step()
      s.poke("io_wireless_command_valid", 0)
      s.expect("io_wireless_multiWait", 0)
      s.poke("io_enable", 1)
      waitForMultiIrq(s)
      assert(read(s, 0x120) == BigInt("abcd1234", 16))
    }
  }

}
