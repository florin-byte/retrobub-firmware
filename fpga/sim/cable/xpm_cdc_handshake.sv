// GB-156, simulation only: Xilinx xpm_cdc_handshake (four-phase handshake across clocks).
// In HandheldGba it carries colour-correction table writes to the display clock domain,
// which the two-console simulation does not clock: the path stays idle.
module xpm_cdc_handshake #(
  parameter integer DEST_EXT_HSK = 1,
  parameter integer DEST_SYNC_FF = 2,
  parameter integer INIT_SYNC_FF = 1,
  parameter integer SIM_ASSERT_CHK = 0,
  parameter integer SRC_SYNC_FF = 2,
  parameter integer WIDTH = 1
) (
  input dest_ack,
  input dest_clk,
  output [WIDTH-1:0] dest_out,
  output dest_req,
  input src_clk,
  input [WIDTH-1:0] src_in,
  output src_rcv,
  input src_send
);
  reg [WIDTH-1:0] hold = '0;
  reg send_r = 1'b0;
  reg [1:0] dest_sync = 2'b00;
  reg [WIDTH-1:0] out_r = '0;
  reg req_r = 1'b0;
  reg [1:0] src_sync = 2'b00;
  wire ack = (DEST_EXT_HSK != 0) ? dest_ack : req_r;
  always @(posedge src_clk) begin
    if (src_send && !send_r) hold <= src_in;
    send_r <= src_send;
    src_sync <= {src_sync[0], ack};
  end
  always @(posedge dest_clk) begin
    dest_sync <= {dest_sync[0], send_r};
    if (dest_sync[1] && !req_r) out_r <= hold;
    req_r <= dest_sync[1];
  end
  assign src_rcv = src_sync[1];
  assign dest_out = out_r;
  assign dest_req = req_r;
endmodule
