// SPDX-License-Identifier: GPL-3.0-only
// GB-146: two simulated consoles as built with GB_DUAL_CORE=1. Each runs a
// visible and a hidden production GB core on a local link cable, with the
// production DualCoreSync module applying buttons on frame boundaries. This
// program plays both hosts (ESP32): it pops local button samples, carries
// them to the other console with random latency and loss, and writes them as
// partner buttons. Console Y is the mirror image of X (its visible core is
// player 2) and has its core clock gated at random.
// The question: is X's visible core identical to Y's hidden core, and X's
// hidden core to Y's visible core, frame by frame?
#include <verilated.h>
#include "VSimDualGameboy.h"
#include "VSimDualGameboy___024root.h"
#include "framebuffer.hpp"
#include <cstring>
#include <deque>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <thread>
#include <vector>

namespace fs = std::filesystem;
constexpr unsigned Quantum = 2048;  // system clocks per exchange step (~0.24 ms)
constexpr unsigned PacketSamples = 8;
static std::vector<uint8_t> read_bytes(const fs::path &p) {
    std::ifstream f(p, std::ios::binary);
    if (!f) throw std::runtime_error("Cannot read " + p.string());
    return {std::istreambuf_iterator<char>(f), {}};
}
struct Options {
    fs::path bios, rom, out, inputs;
    unsigned frames=1200, checkpoint=300, delay=2, seed=17;
    unsigned gate_permille=5, gate_max=80;   // console Y: chance per clock, longest pause
    unsigned loss_permille=100;              // packets lost
    unsigned latency_min=4, latency_max=80;  // quanta (1..19 ms)
    unsigned resend=20;                      // quanta between resends of the latest packet
    unsigned start_late=300;                 // quanta by which Y starts after X
    unsigned skew=0;                         // negative control: Y uses the partner's previous buttons
    unsigned phase=0;                        // core clocks by which player 2's core starts late, on both consoles
};
struct Sample { unsigned apply; uint8_t buttons; uint32_t dv, dh; uint64_t ser[2]; };
struct Packet { uint64_t due; std::vector<Sample> samples; };

// Script masks: a=1 b=2 select=4 start=8 right=16 left=32 up=64 down=128.
// JoypadState as UInt: start[7] select[6] b[5] a[4] down[3] up[2] left[1] right[0].
static uint8_t to_joypad(unsigned m) {
    return ((m>>3&1)<<7)|((m>>2&1)<<6)|((m>>1&1)<<5)|((m&1)<<4)|((m>>7&1)<<3)|((m>>6&1)<<2)|((m>>5&1)<<1)|(m>>4&1);
}

struct Console {
    VerilatedContext context;
    std::unique_ptr<VSimDualGameboy> top;
    std::vector<uint8_t> rom, ram[2];
    Framebuffer screen{160,144};
    bool lcd=false, prev_irq[2]={false,false};
    uint64_t serial_irqs[2]={0,0}, steps=0, paused=0;
    std::vector<std::pair<unsigned,uint8_t>> script; size_t cursor=0; uint8_t held=0;
    unsigned rng=1, pause_left=0, permille=0, pause_max=1;
    // Host model.
    std::deque<uint32_t> writes;
    std::vector<Sample> history;
    size_t sent=0;            // history entries already announced in a packet
    unsigned expected=0;      // next partner apply frame to write
    uint8_t previous=0;       // partner buttons of the frame before (skew)
    uint64_t gaps=0;
    unsigned random() { return rng=rng*1664525u+1013904223u; }

    void init(const Options &o) {
        top=std::make_unique<VSimDualGameboy>(&context);
        rom=read_bytes(o.rom);
        if(rom.size()<32768 || rom.size()>4194304) throw std::runtime_error("ROM must be 32 KiB to 4 MiB");
        unsigned mbc=0; bool rtc=false,rumble=false;
        switch(rom[0x147]) {
        case 0:case 8:case 9: break;
        case 1:case 2:case 3:mbc=1;break;
        case 5:case 6:mbc=2;break;
        case 0xf:case 0x10:rtc=true;[[fallthrough]];
        case 0x11:case 0x12:case 0x13:mbc=3;break;
        case 0x1c:case 0x1d:case 0x1e:rumble=true;[[fallthrough]];
        case 0x19:case 0x1a:case 0x1b:mbc=4;break;
        default:throw std::runtime_error("Unsupported cartridge type");
        }
        const unsigned sizes[]={0,0,8192,32768,131072,65536};
        if(rom[0x149]>=std::size(sizes)) throw std::runtime_error("Unsupported RAM size");
        for(auto &r:ram) r.assign(mbc==2?512:sizes[rom[0x149]],255);
        auto &t=*top;
        t.io_cartConfig_mbcType=mbc;t.io_cartConfig_hasRam=!ram[0].empty();
        t.io_cartConfig_hasRtc=rtc;t.io_cartConfig_hasRumble=rumble;
        t.io_cartConfig_multicart=mbc==1 && rom.size()==1048576 && !memcmp(rom.data()+0x104,rom.data()+0x40104,48);
        t.io_gate=1;t.io_enable=1;t.io_run=0;t.io_delay=o.delay;
        t.io_localButtons=0;t.io_partnerWrite=0;t.io_partnerData=0;t.io_pop=0;t.io_phase=0;t.io_visibleLate=0;
        expected=o.delay;
        auto bios=read_bytes(o.bios);
        if(bios.size()!=2304) throw std::runtime_error("A 2304-byte CGB boot ROM is required");
        std::memcpy(&t.rootp->SimDualGameboy__DOT__core0__DOT__bootRom_ext__DOT__Memory,bios.data(),bios.size());
        std::memcpy(&t.rootp->SimDualGameboy__DOT__core1__DOT__bootRom_ext__DOT__Memory,bios.data(),bios.size());
    }
    // The cartridge memories answer on every system clock, outside the gated core clock.
    void memory() {
        auto &t=*top;
        const bool irq[2]={bool(t.io_serialIrq0),bool(t.io_serialIrq1)};
        for(int i=0;i<2;i++) { if(irq[i] && !prev_irq[i]) serial_irqs[i]++; prev_irq[i]=irq[i]; }
#define SERVE(N,RAM) \
        t.io_dataAccess##N##_valid=0; \
        if(t.io_dataAccess##N##_enable) { \
            auto &mem=t.io_dataAccess##N##_selectRom?rom:RAM; \
            if(t.io_dataAccess##N##_write && !t.io_dataAccess##N##_selectRom && !mem.empty()) \
                mem[t.io_dataAccess##N##_address%mem.size()]=t.io_dataAccess##N##_dataWrite; \
            t.io_dataAccess##N##_dataRead=mem.empty()?255:mem[t.io_dataAccess##N##_address%mem.size()]; \
            t.io_dataAccess##N##_valid=1; \
        }
        SERVE(0,ram[0])
        SERVE(1,ram[1])
#undef SERVE
    }
    // One system clock.
    void step() {
        auto &t=*top;
        bool gate=true;
        if(permille) {
            if(pause_left) {pause_left--;gate=false;}
            else if(random()%1000<permille) {pause_left=random()%pause_max;gate=false;}
        }
        steps++; if(!gate) paused++;
        t.io_gate=gate;t.io_partnerWrite=0;t.io_pop=0;
        t.eval();
        memory();
        // Host: one register access per clock at most.
        if(t.io_request) {
            const uint32_t local=t.io_local;
            history.push_back({(local>>8)&0xffff,uint8_t(local),t.io_digestVisible,t.io_digestHidden,
                {serial_irqs[0],serial_irqs[1]}});
            t.io_pop=1;
        } else if(!writes.empty()) {
            t.io_partnerWrite=1;t.io_partnerData=writes.front();writes.pop_front();
        }
        const unsigned frame=t.io_status&0xffff;
        while(cursor<script.size() && script[cursor].first<=frame) held=script[cursor++].second;
        t.io_localButtons=held;
        t.eval();
        if(gate && !t.io_stall) {
            screen.update(t.io_ppu_hblank,t.io_ppu_vblank);
            if(t.io_ppu_valid && t.io_ppu_lcdEnable) screen.pushBGR(t.io_ppu_pixel);
            if(lcd && !t.io_ppu_lcdEnable) screen.clear();
            lcd=t.io_ppu_lcdEnable;
        }
        t.clock=0;t.eval();
        t.clock=1;t.eval();context.timeInc(1);
    }
    // Partner samples from one packet: only the next frames in order, never across a gap.
    void receive(const Packet &p,unsigned skew) {
        for(const auto &s:p.samples) {
            if(s.apply<expected) continue;
            if(s.apply>expected) {gaps++;break;}
            writes.push_back((uint32_t(s.apply)<<8)|(skew?previous:s.buttons));
            previous=s.buttons;
            expected++;
        }
    }
    void ppm(const fs::path &p) {
        std::ofstream f(p,std::ios::binary);f<<"P6\n160 144\n255\n";
        const auto &b=screen.renderBuffer();
        for(size_t i=0;i<b.size();i+=4) {f.put(b[i+2]);f.put(b[i+1]);f.put(b[i]);}
    }
};

int main(int argc,char **argv) {
    try {
        Options o;
        for(int i=1;i<argc;i++) {
            std::string a=argv[i];
            if(i+1>=argc) throw std::runtime_error("Missing value for "+a);
            std::string v=argv[++i];
            if(a=="--bios")o.bios=v;else if(a=="--rom")o.rom=v;else if(a=="--out")o.out=v;
            else if(a=="--inputs")o.inputs=v;else if(a=="--frames")o.frames=std::stoul(v);
            else if(a=="--checkpoint")o.checkpoint=std::stoul(v);else if(a=="--delay")o.delay=std::stoul(v);
            else if(a=="--seed")o.seed=std::stoul(v);else if(a=="--gate-permille")o.gate_permille=std::stoul(v);
            else if(a=="--gate-max")o.gate_max=std::stoul(v);else if(a=="--loss-permille")o.loss_permille=std::stoul(v);
            else if(a=="--latency-min")o.latency_min=std::stoul(v);else if(a=="--latency-max")o.latency_max=std::stoul(v);
            else if(a=="--resend")o.resend=std::stoul(v);else if(a=="--start-late")o.start_late=std::stoul(v);
            else if(a=="--skew")o.skew=std::stoul(v);else if(a=="--phase")o.phase=std::stoul(v);
            else throw std::runtime_error("Unknown option "+a);
        }
        if(o.bios.empty() || o.rom.empty() || o.out.empty() || !o.frames || !o.checkpoint || !o.gate_max ||
           !o.resend || o.latency_max<o.latency_min || o.delay<1 || o.delay>4)
            throw std::runtime_error("Required: --bios PATH --rom PATH --out DIR [options]; delay 1..4");
        fs::create_directories(o.out);
        // Console 0 = X (player 1 visible, never paused), 1 = Y (player 2 visible, random pauses).
        auto consoles=std::make_unique<Console[]>(2);
        for(int k=0;k<2;k++) consoles[k].init(o);
        if(!o.inputs.empty()) {
            std::ifstream f(o.inputs);
            if(!f) throw std::runtime_error("Cannot read input script");
            std::string line;
            while(std::getline(f,line)) {
                if(line.empty() || line[0]=='#') continue;
                std::istringstream row(line); unsigned frame,side,mask;
                if(!(row>>frame>>side>>mask) || side>1 || mask>255)
                    throw std::runtime_error("Bad script row: "+line);
                consoles[side].script.push_back({frame,to_joypad(mask)});
            }
        }
        auto &X=consoles[0], &Y=consoles[1];
        // X is player 1; on Y the local (visible) core is player 2 and starts late.
        X.top->io_phase=o.phase;X.top->io_visibleLate=0;
        Y.top->io_phase=o.phase;Y.top->io_visibleLate=1;
        Y.rng=o.seed;Y.permille=o.gate_permille;Y.pause_max=o.gate_max;
        for(int k=0;k<2;k++) {
            auto &c=consoles[k];
            const unsigned permille=c.permille; c.permille=0;
            c.top->reset=1; for(int i=0;i<16;i++) c.step();
            c.top->reset=0; c.permille=permille; c.steps=c.paused=0;
        }
        std::ofstream csv(o.out/"frames.csv");
        csv<<"frame,x_visible,y_hidden,x_hidden,y_visible,equal,serial_x_v,serial_x_h,serial_y_v,serial_y_h\n";
        std::deque<Packet> inbox[2];
        unsigned radio=o.seed*2654435761u+1;
        auto rnd=[&]{ return radio=radio*1664525u+1013904223u; };
        uint64_t packets=0,lost=0,idle=0;
        long first_diff=-1; unsigned diff_frames=0; size_t compared=0;
        for(uint64_t q=0;;q++) {
            // Not in the first quantum: the module loads the start phase while run is low.
            if(q>=1) X.top->io_run=1;
            if(q>=o.start_late) Y.top->io_run=1;
            for(int k=0;k<2;k++) {
                auto &in=inbox[k];
                for(auto it=in.begin();it!=in.end();) {
                    if(it->due>q) {++it;continue;}
                    consoles[k].receive(*it,k==1?o.skew:0);
                    it=in.erase(it);
                }
            }
            std::thread ty([&]{ for(unsigned i=0;i<Quantum;i++) Y.step(); });
            for(unsigned i=0;i<Quantum;i++) X.step();
            ty.join();
            for(int k=0;k<2;k++) {
                auto &c=consoles[k];
                const bool fresh=c.sent!=c.history.size();
                if(c.history.empty() || (!fresh && q%o.resend)) continue;
                c.sent=c.history.size();
                packets++;
                if(rnd()%1000<o.loss_permille) {lost++;continue;}
                Packet p;
                p.due=q+o.latency_min+rnd()%(o.latency_max-o.latency_min+1);
                const size_t n=std::min<size_t>(PacketSamples,c.history.size());
                p.samples.assign(c.history.end()-n,c.history.end());
                inbox[1-k].push_back(std::move(p));
            }
            // History entry i holds the digests at frame i (its buttons apply at i+delay).
            const size_t done=std::min(X.history.size(),Y.history.size());
            idle=done==compared?idle+1:0;
            if(idle>40000) throw std::runtime_error("No progress: consoles stuck at frame "+std::to_string(done));
            for(;compared<done && compared<=o.frames;compared++) {
                const auto &x=X.history[compared], &y=Y.history[compared];
                if(x.apply!=compared+o.delay || y.apply!=compared+o.delay) throw std::runtime_error("Frame numbering broke");
                const bool equal=x.dv==y.dh && x.dh==y.dv;
                if(!equal) {diff_frames++; if(first_diff<0) first_diff=long(compared);}
                csv<<compared<<std::hex<<','<<x.dv<<','<<y.dh<<','<<x.dh<<','<<y.dv<<std::dec<<','<<equal
                   <<','<<x.ser[0]<<','<<x.ser[1]<<','<<y.ser[0]<<','<<y.ser[1]<<'\n';
                if(compared && (compared%o.checkpoint==0 || compared==o.frames || first_diff==long(compared))) {
                    X.ppm(o.out/("x-"+std::to_string(compared)+".ppm"));
                    Y.ppm(o.out/("y-"+std::to_string(compared)+".ppm"));
                    csv.flush();
                    std::cerr<<"frame="<<compared<<" serial_x="<<x.ser[0]<<'/'<<x.ser[1]
                        <<" serial_y="<<y.ser[0]<<'/'<<y.ser[1]<<" equal="<<equal<<'\n';
                }
            }
            if(compared>o.frames) break;
        }
        const auto &x=X.history[o.frames], &y=Y.history[o.frames];
        std::ostringstream s;
        s<<"frames="<<o.frames<<" delay="<<o.delay<<" first_diff="<<first_diff<<" diff_frames="<<diff_frames
         <<" serial_x="<<x.ser[0]<<'/'<<x.ser[1]<<" serial_y="<<y.ser[0]<<'/'<<y.ser[1]
         <<" wait_x="<<X.top->io_waitCycles<<" wait_y="<<Y.top->io_waitCycles
         <<" steps_x="<<X.steps<<" steps_y="<<Y.steps<<" y_paused="<<Y.paused
         <<" packets="<<packets<<" lost="<<lost<<" gaps="<<X.gaps+Y.gaps<<" skew="<<o.skew<<" phase="<<o.phase<<std::hex<<" cable_x="<<X.top->io_serialStatVisible<<"/"<<X.top->io_serialStatHidden<<"/"<<X.top->io_serialStatCross
         <<" cable_y="<<Y.top->io_serialStatVisible<<"/"<<Y.top->io_serialStatHidden<<"/"<<Y.top->io_serialStatCross
         <<" writes_x="<<X.top->io_writeStat<<" rejects_x="<<X.top->io_rejectStat<<" writes_y="<<Y.top->io_writeStat<<" rejects_y="<<Y.top->io_rejectStat<<std::dec;
        std::ofstream(o.out/"result.txt")<<s.str()<<'\n';
        std::cout<<"RESULT "<<s.str()<<'\n';
        return first_diff>=0 ? 1 : (!x.ser[0] ? 2 : 0);
    } catch(const std::exception &e) {std::cerr<<"ERROR: "<<e.what()<<'\n';return 4;}
}
