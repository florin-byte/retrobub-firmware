// GB-156, simulation only: Xilinx ODDR in SAME_EDGE mode, as AsyncSramController uses it
// for the SRAM write enable (D1 in the first half of the cycle, D2 in the second).
module ODDR #(
  parameter DDR_CLK_EDGE = "SAME_EDGE",
  parameter integer INIT = 0
) (
  output Q,
  input C,
  input CE,
  input D1,
  input D2,
  input R,
  input S
);
  reg q1 = INIT[0];
  reg q2 = INIT[0];
  always @(posedge C) begin
    if (S) begin
      q1 <= 1'b1;
      q2 <= 1'b1;
    end else if (R) begin
      q1 <= 1'b0;
      q2 <= 1'b0;
    end else if (CE) begin
      q1 <= D1;
      q2 <= D2;
    end
  end
  assign Q = C ? q1 : q2;
endmodule
