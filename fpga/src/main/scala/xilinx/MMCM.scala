package xilinx

import chisel3._

object MMCM {
    case class ClockOut(divide: Int, dutyCycle: Double = 0.5, phase: Double = 0.0)
}

class MMCM(
    clockInHz: Int,
    divide: Int,
    multiply: Double,
    clockOutConfig: Seq[MMCM.ClockOut],
    // Index of the output with dynamic fine phase shift (PSEN / PSINCDEC, one step = 1/56 VCO period
    // from its static phase). None: the MMCME2_BASE instance of every other core, unchanged.
    fineShiftOutput: Option[Int] = None,
) extends RawModule {
  val io = IO(new Bundle {
    val clockIn = Input(Clock())
    val powerDown = Input(Bool())
    val clockOuts = Output(Vec(clockOutConfig.length, Clock()))
    val locked = Output(Bool())
  })
  val phaseShift = fineShiftOutput.map(_ => IO(new Bundle {
    val clock = Input(Clock())
    val enable = Input(Bool())
    val increment = Input(Bool())
    val done = Output(Bool())
  }))

  private val params = Map(
    "CLKIN1_PERIOD" -> DoubleParam(1_000_000_000.toDouble / clockInHz),
    "DIVCLK_DIVIDE" -> IntParam(divide),
    "CLKFBOUT_MULT_F" -> DoubleParam(multiply),
  ) ++ clockOutConfig.zipWithIndex.flatMap { case (x, i) =>
    val divideKey = if (i == 0) s"CLKOUT${i}_DIVIDE_F" else s"CLKOUT${i}_DIVIDE"
    val divideVal = if (i == 0) DoubleParam(x.divide.toDouble) else IntParam(x.divide)
    Seq(
        divideKey -> divideVal,
        s"CLKOUT${i}_PHASE" -> DoubleParam(x.phase),
        s"CLKOUT${i}_DUTY_CYCLE" -> DoubleParam(x.dutyCycle),
    )
  }
  // noPrefix: the instance must stay "mmcm" (the constraint files select */pll/mmcm/CLKOUTn).
  private val mmcmClocks = chisel3.experimental.noPrefix { fineShiftOutput match {
    case None =>
      val mmcm = Module(new MMCME2_BASE(params))
      mmcm.io.CLKFBIN := mmcm.io.CLKFBOUT
      mmcm.io.CLKIN1 := io.clockIn
      mmcm.io.PWRDWN := io.powerDown
      mmcm.io.RST := false.B
      io.locked := mmcm.io.LOCKED
      Seq(mmcm.io.CLKOUT0, mmcm.io.CLKOUT1, mmcm.io.CLKOUT2, mmcm.io.CLKOUT3, mmcm.io.CLKOUT4,
        mmcm.io.CLKOUT5, mmcm.io.CLKOUT6)
    case Some(index) =>
      require(index >= 0 && index < clockOutConfig.length)
      val mmcm = Module(new MMCME2_ADV(params ++ Map(s"CLKOUT${index}_USE_FINE_PS" -> StringParam("TRUE"))))
      mmcm.io.CLKFBIN := mmcm.io.CLKFBOUT
      mmcm.io.CLKIN1 := io.clockIn
      mmcm.io.CLKIN2 := false.B.asClock
      mmcm.io.CLKINSEL := true.B
      mmcm.io.PWRDWN := io.powerDown
      mmcm.io.RST := false.B
      mmcm.io.DADDR := 0.U
      mmcm.io.DCLK := false.B.asClock
      mmcm.io.DEN := false.B
      mmcm.io.DI := 0.U
      mmcm.io.DWE := false.B
      mmcm.io.PSCLK := phaseShift.get.clock
      mmcm.io.PSEN := phaseShift.get.enable
      mmcm.io.PSINCDEC := phaseShift.get.increment
      phaseShift.get.done := mmcm.io.PSDONE
      io.locked := mmcm.io.LOCKED
      Seq(mmcm.io.CLKOUT0, mmcm.io.CLKOUT1, mmcm.io.CLKOUT2, mmcm.io.CLKOUT3, mmcm.io.CLKOUT4,
        mmcm.io.CLKOUT5, mmcm.io.CLKOUT6)
  } }
  io.clockOuts := VecInit(mmcmClocks.take(clockOutConfig.length))
}

private class MMCME2_BASE(
    params: Map[String, Param],
) extends ExtModule(params) {
  val io = FlatIO(new Bundle {
    val CLKFBIN = Input(Clock())
    val CLKIN1 = Input(Clock())
    val PWRDWN = Input(Bool())
    val RST = Input(Bool())

    val CLKFBOUT = Output(Clock())
    val CLKFBOUTB = Output(Clock())
    val CLKOUT0 = Output(Clock())
    val CLKOUT0B = Output(Clock())
    val CLKOUT1 = Output(Clock())
    val CLKOUT1B = Output(Clock())
    val CLKOUT2 = Output(Clock())
    val CLKOUT2B = Output(Clock())
    val CLKOUT3 = Output(Clock())
    val CLKOUT3B = Output(Clock())
    val CLKOUT4 = Output(Clock())
    val CLKOUT5 = Output(Clock())
    val CLKOUT6 = Output(Clock())

    val LOCKED = Output(Bool())
  })
}

private class MMCME2_ADV(
    params: Map[String, Param],
) extends ExtModule(params) {
  val io = FlatIO(new Bundle {
    val CLKFBIN = Input(Clock())
    val CLKIN1 = Input(Clock())
    val CLKIN2 = Input(Clock())
    val CLKINSEL = Input(Bool())
    val PWRDWN = Input(Bool())
    val RST = Input(Bool())
    val DADDR = Input(UInt(7.W))
    val DCLK = Input(Clock())
    val DEN = Input(Bool())
    val DI = Input(UInt(16.W))
    val DWE = Input(Bool())
    val PSCLK = Input(Clock())
    val PSEN = Input(Bool())
    val PSINCDEC = Input(Bool())

    val CLKFBOUT = Output(Clock())
    val CLKOUT0 = Output(Clock())
    val CLKOUT1 = Output(Clock())
    val CLKOUT2 = Output(Clock())
    val CLKOUT3 = Output(Clock())
    val CLKOUT4 = Output(Clock())
    val CLKOUT5 = Output(Clock())
    val CLKOUT6 = Output(Clock())

    val LOCKED = Output(Bool())
    val PSDONE = Output(Bool())
  })
}
