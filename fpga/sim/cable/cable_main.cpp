// SPDX-License-Identifier: GPL-3.0-only
// GB-142 prototype: two "consoles", each running two production GB cores joined
// by a local link cable. Buttons are applied on both consoles at the same
// emulated frame, a fixed number of frames late. Console Y additionally has its
// core clock enable gated at random (memory waits, crystal drift). The question:
// do both consoles stay identical, frame by frame?
#include <cstdio>
#include <verilated.h>
#include "VSimCableGameboy.h"
#include "VSimCableGameboy___024root.h"
#include "framebuffer.hpp"
#include <algorithm>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <thread>
#include <tuple>
#include <vector>

namespace fs = std::filesystem;
constexpr uint64_t Hz = 8388608, FrameCycles = 140448;
static std::vector<uint8_t> read_bytes(const fs::path &p) {
    std::ifstream f(p, std::ios::binary);
    if (!f) throw std::runtime_error("Cannot read " + p.string());
    return {std::istreambuf_iterator<char>(f), {}};
}
struct Options {
    fs::path bios, rom, out, inputs;
    unsigned frames=1800, checkpoint=60, delay=2, seed=17;
    unsigned gate_permille=5, gate_max=80; // console Y: chance per cycle, longest pause
    int skew_side=-1; unsigned skew=0;     // negative control: console Y applies one side late
    bool cycle_diff=false;                 // slow: lockstep per cycle, report the first differing cycle
    unsigned phase=0;                      // GB-150: player 2 starts this many core clocks after player 1, on both consoles
};
struct Input { unsigned frame, side, mask; };
static inline void mix(uint64_t &h, uint64_t v) { h=(h^v)*1099511628211ull; }
// GB-150: serial port trace of console X between two frames (stderr, lines starting with T).
static unsigned g_trace_from=0, g_trace_to=0, g_frame=0;

struct Core {
    VerilatedContext context;
    std::unique_ptr<VSimCableGameboy> top;
    std::vector<uint8_t> rom, ram;
    Framebuffer screen{160,144};
    bool lcd=false, prev_irq=false, prev_vblank=false;
    uint64_t trace=14695981039346656037ull; // every enabled cycle: PC, serial state, pixels
    uint64_t serial_irqs=0, video_frames=0, pixels_seen=0;
    std::vector<uint8_t> first_bytes;
    // What this core showed before its latest enabled edge (first-mismatch report).
    struct Seen { uint32_t pc, mem, pix, ser, sync; bool operator==(const Seen&) const = default; } seen{};
    void init(const Options &o) {
        top=std::make_unique<VSimCableGameboy>(&context);
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
        ram.resize(mbc==2?512:sizes[rom[0x149]],255);
        auto &t=*top;
        t.io_cartConfig_mbcType=mbc;t.io_cartConfig_hasRam=!ram.empty();
        t.io_cartConfig_hasRtc=rtc;t.io_cartConfig_hasRumble=rumble;
        t.io_cartConfig_multicart=mbc==1 && rom.size()==1048576 && !memcmp(rom.data()+0x104,rom.data()+0x40104,48);
        t.io_clockConfig_enable=1;t.io_clockConfig_provide8Mhz=1;
        t.io_saveState_savePulse=0;t.io_saveState_loadPulse=0;t.io_saveState_resetCore=0;
        t.io_saveState_sdram_done=0;t.io_saveState_sdram_dataRead=0;
        t.cable_in=1;t.cable_clockIn=1;
        buttons(0);
        auto bios=read_bytes(o.bios);
        if(bios.size()!=2304) throw std::runtime_error("A 2304-byte CGB boot ROM is required");
        std::memcpy(&t.rootp->SimCableGameboy__DOT__bootRom_ext__DOT__Memory,bios.data(),bios.size());
    }
    void buttons(unsigned m) {
        top->io_joypad_a=m&1;top->io_joypad_b=(m>>1)&1;
        top->io_joypad_select=(m>>2)&1;top->io_joypad_start=(m>>3)&1;
        top->io_joypad_right=(m>>4)&1;top->io_joypad_left=(m>>5)&1;
        top->io_joypad_up=(m>>6)&1;top->io_joypad_down=(m>>7)&1;
    }
    // Sampled only before an enabled clock edge, so a paused console observes
    // exactly the same sequence as one that never pauses.
    // The cartridge memory answers on every system clock: like the handheld's
    // SDRAM/SRAM path, it is outside the gated core clock.
    void memory() {
        auto &t=*top;
        // The observation register is ungated too: count its pulses on every clock.
        if(t.serialInterrupt && !prev_irq) {
            serial_irqs++;
            if(first_bytes.size()<24) first_bytes.push_back(t.io_serialDebug_regData);
        }
        prev_irq=t.serialInterrupt;
        t.io_dataAccess_valid=0;
        if(t.io_dataAccess_enable) {
            auto &mem=t.io_dataAccess_selectRom?rom:ram;
            if(t.io_dataAccess_write && !t.io_dataAccess_selectRom && !mem.empty())
                mem[t.io_dataAccess_address%mem.size()]=t.io_dataAccess_dataWrite;
            t.io_dataAccess_dataRead=mem.empty()?255:mem[t.io_dataAccess_address%mem.size()];
            t.io_dataAccess_valid=1;
        }
    }
    int trace_id=-1; unsigned t_prevbits=0, t_prev=99, t_edges=0, t_prevclk=1; uint64_t t_cycle=0;
    void sample() {
        auto &t=*top;
        t_cycle++;
        if(trace_id>=0 && g_frame>=g_trace_from && g_frame<g_trace_to) {
            const unsigned clk=t.io_serialDebug_regClockMode?t.io_serialDebug_clockOut:t.io_serialDebug_clockIn;
            if(clk!=t_prevclk) t_edges++;
            t_prevclk=clk;
            const unsigned st=(unsigned(t.io_serialDebug_regEnable)<<1)|unsigned(t.io_serialDebug_regClockMode);
            const unsigned bits=t.io_serialDebug_regBitsLeft;
            if(bits!=t_prevbits) {
                fprintf(stderr,"T   p%d cycle=%llu bits=%u sb=%02x clk=%u in=%u out=%u%c",trace_id+1,(unsigned long long)t_cycle,bits,
                    unsigned(t.io_serialDebug_regData),clk,unsigned(t.io_serialDebug_dataIn),unsigned(t.cable_out),10);
                t_prevbits=bits;
            }
            if(st!=t_prev) {
                fprintf(stderr,"T frame=%u p%d cycle=%llu en=%u internal=%u sb=%02x bits=%u edges=%u in=%u\n",g_frame,trace_id+1,
                    (unsigned long long)t_cycle,st>>1,st&1,unsigned(t.io_serialDebug_regData),unsigned(t.io_serialDebug_regBitsLeft),t_edges,unsigned(t.io_serialDebug_dataIn));
                t_prev=st;t_edges=0;
            }
        }
        seen.pc=t.io_cpuDebug_regPc;
        seen.mem=0; // requests are timed by the ungated cartridge: not comparable per cycle
        seen.pix=(t.io_ppu_valid && t.io_ppu_lcdEnable)?(0x10000u|t.io_ppu_pixel):0;
        seen.ser=(uint32_t(t.io_serialDebug_regData)<<8)|(uint32_t(t.io_serialDebug_regEnable)<<1)|
            uint32_t(t.io_serialDebug_regClockMode);
        seen.sync=(uint32_t(t.io_ppu_vblank)<<2)|(uint32_t(t.io_ppu_hblank)<<1)|uint32_t(t.io_ppu_lcdEnable);
        if(t.io_ppu_vblank && !prev_vblank) video_frames++;
        prev_vblank=t.io_ppu_vblank;
        screen.update(t.io_ppu_hblank,t.io_ppu_vblank);
        if(t.io_ppu_valid && t.io_ppu_lcdEnable) {
            screen.pushBGR(t.io_ppu_pixel);pixels_seen++;
            mix(trace,0x10000u|t.io_ppu_pixel);
        }
        if(lcd && !t.io_ppu_lcdEnable) screen.clear();
        lcd=t.io_ppu_lcdEnable;
        mix(trace,(uint64_t(t.io_cpuDebug_regPc)<<16)|(uint64_t(t.io_serialDebug_regData)<<8)|
            (uint64_t(t.io_serialDebug_regEnable)<<1)|uint64_t(t.io_serialDebug_regClockMode));
    }
    void ppm(const fs::path &p) {
        std::ofstream f(p,std::ios::binary);f<<"P6\n160 144\n255\n";
        const auto &b=screen.renderBuffer();
        for(size_t i=0;i<b.size();i+=4) {f.put(b[i+2]);f.put(b[i+1]);f.put(b[i]);}
    }
};

struct Console {
    Core c[2];
    uint64_t enabled=0, steps=0, paused=0, last_pause_end=0, last_pause_len=0;
    bool was_paused=false;
    unsigned rng=1, pause_left=0, permille=0, pause_max=1, phase_left=0;
    unsigned random() { return rng=rng*1664525u+1013904223u; }
    void init(const Options &o) { for(auto &x:c) x.init(o); }
    void reset(bool on) { for(auto &x:c) x.top->reset=on; }
    // One system clock. Returns true when the cores advanced.
    bool step(bool allow_pause) {
        bool run=true;
        if(allow_pause && permille) {
            if(pause_left) {pause_left--;run=false;}
            else if(random()%1000<permille) {pause_left=random()%pause_max;run=false;}
        }
        steps++;
        if(run) {
            if(was_paused) last_pause_end=enabled;
            was_paused=false;
            // Cable: each side sees what the other drove after the previous edge.
            bool out[2], cke[2], cko[2];
            for(int i=0;i<2;i++) {out[i]=c[i].top->cable_out;cke[i]=c[i].top->cable_clockEnable;cko[i]=c[i].top->cable_clockOut;}
            for(int i=0;i<2;i++) {
                auto &t=*c[i].top;
                t.cable_in=out[1-i];
                t.cable_clockIn=cke[1-i]?cko[1-i]:1;
                t.io_clockConfig_enable=!(i==1 && phase_left);
                t.eval();
            }
            c[0].memory();c[0].sample();
            c[1].memory();if(!phase_left) c[1].sample();
            if(phase_left) phase_left--;
            enabled++;
        } else {
            for(auto &x:c) {x.top->io_clockConfig_enable=0;x.top->eval();x.memory();}
            paused++;
            last_pause_len=was_paused?last_pause_len+1:1;
            was_paused=true;
        }
        for(auto &x:c) {x.top->clock=0;x.top->eval();}
        for(auto &x:c) {x.top->clock=1;x.top->eval();x.context.timeInc(1);}
        return run;
    }
};

int main(int argc,char **argv) {
    try {
        Options o;
        for(int i=1;i<argc;i++) {
            std::string a=argv[i];
            if(a=="--cycle-diff") {o.cycle_diff=true;continue;}
            if(i+1>=argc) throw std::runtime_error("Missing value for "+a);
            std::string v=argv[++i];
            if(a=="--bios")o.bios=v;else if(a=="--rom")o.rom=v;else if(a=="--out")o.out=v;
            else if(a=="--inputs")o.inputs=v;else if(a=="--frames")o.frames=std::stoul(v);
            else if(a=="--checkpoint")o.checkpoint=std::stoul(v);else if(a=="--delay")o.delay=std::stoul(v);
            else if(a=="--seed")o.seed=std::stoul(v);else if(a=="--gate-permille")o.gate_permille=std::stoul(v);
            else if(a=="--gate-max")o.gate_max=std::stoul(v);
            else if(a=="--skew-side")o.skew_side=std::stoi(v);else if(a=="--skew")o.skew=std::stoul(v);else if(a=="--phase")o.phase=std::stoul(v);else if(a=="--trace-from")g_trace_from=std::stoul(v);else if(a=="--trace-to")g_trace_to=std::stoul(v);
            else throw std::runtime_error("Unknown option "+a);
        }
        if(o.bios.empty() || o.rom.empty() || o.out.empty() || !o.frames || !o.checkpoint || !o.gate_max)
            throw std::runtime_error("Required: --bios PATH --rom PATH --out DIR [options]");
        fs::create_directories(o.out);
        std::vector<Input> input;
        if(!o.inputs.empty()) {
            std::ifstream f(o.inputs);
            if(!f) throw std::runtime_error("Cannot read input script");
            std::string line;
            while(std::getline(f,line)) {
                if(line.empty() || line[0]=='#') continue;
                std::istringstream row(line); Input v;
                if(!(row>>v.frame>>v.side>>v.mask) || v.side>1 || v.mask>255)
                    throw std::runtime_error("Bad script row: "+line);
                input.push_back(v);
            }
        }
        // Console index 0 = X (never paused), 1 = Y (random pauses, optional skew).
        auto consoles=std::make_unique<Console[]>(2);
        std::vector<Input> plan[2];
        for(int k=0;k<2;k++) {
            consoles[k].init(o);
            for(auto v:input) {
                v.frame+=o.delay;
                if(k==1 && int(v.side)==o.skew_side) v.frame+=o.skew;
                plan[k].push_back(v);
            }
            std::stable_sort(plan[k].begin(),plan[k].end(),[](auto a,auto b){return a.frame<b.frame;});
        }
        consoles[1].rng=o.seed;consoles[1].permille=o.gate_permille;consoles[1].pause_max=o.gate_max;
        for(int k=0;k<2;k++) {
            consoles[k].reset(true);
            for(unsigned i=0;i<16;i++) consoles[k].step(false);
            consoles[k].reset(false);
            consoles[k].enabled=0;
            consoles[k].phase_left=o.phase;
        }
        std::ofstream csv(o.out/"frames.csv");
        csv<<"frame,x_p1,y_p1,x_p2,y_p2,equal,serial_x_p1,serial_x_p2,serial_y_p1,serial_y_p2,pc_x_p1,pc_x_p2,y_paused_cycles\n";
        size_t cursor[2]={0,0};
        long first_diff=-1; unsigned diff_frames=0, frames_done=0;
        bool cycle_diff=false; std::string diff_note="cycle_diff none";
        consoles[0].c[0].trace_id=0;consoles[0].c[1].trace_id=1;
        for(unsigned frame=0;frame<o.frames;frame++) {
            g_frame=frame;
            for(int k=0;k<2;k++) {
                while(cursor[k]<plan[k].size() && plan[k][cursor[k]].frame==frame) {
                    auto v=plan[k][cursor[k]++];
                    consoles[k].c[v.side].buttons(v.mask);
                }
            }
            // Both consoles advance one enabled cycle at a time, so the first
            // cycle at which they show different signals can be reported.
            const uint64_t target=uint64_t(frame+1)*FrameCycles;
            if(!o.cycle_diff) {
                // Normal runs: the consoles are independent, one thread each.
                std::thread ty([&]{ while(consoles[1].enabled<target) consoles[1].step(true); });
                while(consoles[0].enabled<target) consoles[0].step(false);
                ty.join();
            }
            while(consoles[0].enabled<target) {
                consoles[0].step(false);
                while(!consoles[1].step(true)) {}
                if(!cycle_diff) for(int i=0;i<2;i++) {
                    auto &a=consoles[0].c[i].seen; auto &b=consoles[1].c[i].seen;
                    if(a==b) continue;
                    cycle_diff=true;
                    std::ostringstream d;
                    d<<"cycle_diff enabled_cycle="<<consoles[0].enabled<<" frame="<<frame+1<<" core=p"<<i+1<<std::hex
                     <<" x(pc,mem,pix,ser,sync)="<<a.pc<<','<<a.mem<<','<<a.pix<<','<<a.ser<<','<<a.sync
                     <<" y="<<b.pc<<','<<b.mem<<','<<b.pix<<','<<b.ser<<','<<b.sync<<std::dec
                     <<" y_last_pause_len="<<consoles[1].last_pause_len
                     <<" y_cycles_since_pause="<<consoles[1].enabled-consoles[1].last_pause_end;
                    diff_note=d.str();
                    break;
                }
            }
            frames_done=frame+1;
            auto &X=consoles[0], &Y=consoles[1];
            bool equal=X.c[0].trace==Y.c[0].trace && X.c[1].trace==Y.c[1].trace;
            if(!equal) {diff_frames++; if(first_diff<0) first_diff=frame+1;}
            csv<<frame+1<<std::hex<<','<<X.c[0].trace<<','<<Y.c[0].trace<<','<<X.c[1].trace<<','<<Y.c[1].trace<<std::dec
               <<','<<equal<<','<<X.c[0].serial_irqs<<','<<X.c[1].serial_irqs<<','<<Y.c[0].serial_irqs<<','<<Y.c[1].serial_irqs
               <<','<<X.c[0].top->io_cpuDebug_regPc<<','<<X.c[1].top->io_cpuDebug_regPc<<','<<Y.paused<<'\n';
            bool shot=(frame+1)%o.checkpoint==0 || frame+1==o.frames || (!equal && first_diff==long(frame+1));
            if(shot) {
                const char *names[2]={"x","y"};
                for(int k=0;k<2;k++) for(int i=0;i<2;i++)
                    consoles[k].c[i].ppm(o.out/(std::string(names[k])+"-p"+std::to_string(i+1)+"-"+std::to_string(frame+1)+".ppm"));
                csv.flush();
                std::cerr<<"frame="<<frame+1<<" serial="<<X.c[0].serial_irqs<<'/'<<X.c[1].serial_irqs
                    <<" equal="<<equal<<" y_paused="<<Y.paused<<'\n';
            }
        }
        auto &X=consoles[0], &Y=consoles[1];
        std::ofstream result(o.out/"result.txt");
        std::ostringstream s;
        s<<"frames="<<frames_done<<" delay="<<o.delay<<" first_diff="<<first_diff<<" diff_frames="<<diff_frames
         <<" serial_x="<<X.c[0].serial_irqs<<'/'<<X.c[1].serial_irqs<<" serial_y="<<Y.c[0].serial_irqs<<'/'<<Y.c[1].serial_irqs
         <<" video_x="<<X.c[0].video_frames<<'/'<<X.c[1].video_frames
         <<" y_steps="<<Y.steps<<" y_paused="<<Y.paused<<" skew_side="<<o.skew_side<<" skew="<<o.skew;
        result<<s.str()<<'\n';
        for(int i=0;i<2;i++) {
            result<<"first_bytes_x_p"<<i+1<<'=';
            for(auto b:X.c[i].first_bytes) result<<std::hex<<unsigned(b)<<' '<<std::dec;
            result<<'\n';
        }
        result<<diff_note<<'\n';
        std::cout<<"RESULT "<<s.str()<<'\n'<<diff_note<<'\n';
        return first_diff>=0 ? 1 : (!X.c[0].serial_irqs ? 2 : 0);
    } catch(const std::exception &e) {std::cerr<<"ERROR: "<<e.what()<<'\n';return 4;}
}
