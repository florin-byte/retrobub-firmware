// GB-156, simulation only: stands in for the Xilinx clock generator of HandheldGba.
// The simulator drives CLKIN1 with the SDRAM clock (4x the system clock) and the
// module clock of the top with the system clock, rising edges aligned. Every output
// follows CLKIN1; only CLKOUT1 (SDRAM) clocks logic inside the core.
module MMCME2_BASE #(
  parameter real CLKIN1_PERIOD = 0.0,
  parameter integer DIVCLK_DIVIDE = 1,
  parameter real CLKFBOUT_MULT_F = 1.0,
  parameter real CLKOUT0_DIVIDE_F = 1.0,
  parameter real CLKOUT0_PHASE = 0.0,
  parameter real CLKOUT0_DUTY_CYCLE = 0.5,
  parameter integer CLKOUT1_DIVIDE = 1,
  parameter real CLKOUT1_PHASE = 0.0,
  parameter real CLKOUT1_DUTY_CYCLE = 0.5,
  parameter integer CLKOUT2_DIVIDE = 1,
  parameter real CLKOUT2_PHASE = 0.0,
  parameter real CLKOUT2_DUTY_CYCLE = 0.5,
  parameter integer CLKOUT3_DIVIDE = 1,
  parameter real CLKOUT3_PHASE = 0.0,
  parameter real CLKOUT3_DUTY_CYCLE = 0.5
) (
  input CLKFBIN,
  input CLKIN1,
  input PWRDWN,
  input RST,
  output CLKFBOUT,
  output CLKFBOUTB,
  output CLKOUT0,
  output CLKOUT0B,
  output CLKOUT1,
  output CLKOUT1B,
  output CLKOUT2,
  output CLKOUT2B,
  output CLKOUT3,
  output CLKOUT3B,
  output CLKOUT4,
  output CLKOUT5,
  output CLKOUT6,
  output LOCKED
);
  assign CLKFBOUT = 1'b0;
  assign CLKFBOUTB = 1'b0;
  assign CLKOUT0 = CLKIN1;
  assign CLKOUT0B = 1'b0;
  assign CLKOUT1 = CLKIN1;
  assign CLKOUT1B = 1'b0;
  assign CLKOUT2 = CLKIN1;
  assign CLKOUT2B = 1'b0;
  assign CLKOUT3 = CLKIN1;
  assign CLKOUT3B = 1'b0;
  assign CLKOUT4 = 1'b0;
  assign CLKOUT5 = 1'b0;
  assign CLKOUT6 = 1'b0;
  assign LOCKED = 1'b1;
endmodule
