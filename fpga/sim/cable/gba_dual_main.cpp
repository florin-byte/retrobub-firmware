// SPDX-License-Identifier: GPL-3.0-only
// GB-156: two consoles, each the production HandheldGba built with GBA_DUAL_CORE=1
// (two GBA cores, DualCoreSync, memory arbiters, SRAM and SDRAM controllers, register map).
// This program plays what is outside the FPGA: the SRAM and SDRAM chips, the clocks and
// the ESP32 (host bus, commands, the radio between the consoles).
// Console X: the visible core is player 1. Console Y: the visible core is player 2; it
// starts later and its host makes random memory reads, so its cores stop at other times.
// Question: do both consoles stay identical, frame by frame?
#include <cstdio>
#include <verilated.h>
#include <verilated_save.h>
#include "VSimDualGba.h"
#include "framebuffer.hpp"
#include "gba_board_models.hpp"
#include <algorithm>
#include <chrono>
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
constexpr uint64_t FrameCycles = 280896;
constexpr unsigned Quantum = 2048;       // system clocks between radio exchanges (~0.12 ms)
constexpr unsigned PacketSamples = 8;
constexpr uint32_t BackupBase = 0x1FC0000; // HandheldGba.DualBackupBase(0)

static std::vector<uint8_t> read_bytes(const fs::path &p) {
    std::ifstream f(p, std::ios::binary);
    if (!f) throw std::runtime_error("Cannot read " + p.string());
    return {std::istreambuf_iterator<char>(f), {}};
}
struct Options {
    fs::path bios, rom, out, inputs;
    unsigned frames=600, checkpoint=60, delay=2, seed=17;
    unsigned loss_permille=100;              // packets lost
    unsigned latency_min=4, latency_max=80;  // quanta
    unsigned resend=20;                      // quanta between resends of the latest packet
    unsigned start_late=300;                 // quanta by which Y starts after X
    unsigned skew=0;                         // negative control: Y uses the partner's previous buttons
    unsigned phase=0;                        // core clocks by which player 2's core starts late, on both consoles
    unsigned swap=0;                         // 1: X is player 2 and Y is player 1
    unsigned noise_ppm=2000;                 // console Y: chance per system clock of a host memory read
    unsigned spi_gap=50;                     // system clocks between host accesses while the game runs
    unsigned diff_from=0, diff_to=0;         // core frames kept cycle by cycle for the comparison
    unsigned save_every=0;
    std::string save, load;
};
struct Sample { uint32_t apply, buttons, dv, dh; };
struct Packet { uint64_t due; std::vector<Sample> samples; };

// File helpers for checkpoints.
struct Ck {
    FILE *f; bool saving; bool ok=true;
    template<class T> void pod(T &v) { if(saving) fwrite(&v,sizeof v,1,f); else ok=ok && fread(&v,sizeof v,1,f)==1; }
    template<class C> void seq(C &c) {
        uint64_t n=c.size(); pod(n);
        if(!saving) c.resize(n);
        for(auto &v:c) pod(v);
    }
};

struct Cart {
    std::vector<uint8_t> rom; size_t size=0;
    unsigned type=0, bsize=0; bool autodetect=false; const char *name="none";
    bool has(const char *s) const {
        size_t len=strlen(s);
        for(size_t i=0;i+len<=size;i+=4) if(!memcmp(rom.data()+i,s,len)) return true;
        return false;
    }
    void load(const fs::path &p) {
        rom=read_bytes(p); size=rom.size();
        if(size<192 || size>BackupBase) throw std::runtime_error("ROM must be below 31.75 MiB in two-core mode");
        // Same detection as sim/gba/cartridge.cpp.
        if(has("EEPROM_V")) {type=3;autodetect=true;name="EEPROM";}
        else if(has("SRAM_V") || has("SRAM_F_V")) {type=1;name="SRAM";}
        else if(has("FLASH_V") || has("FLASH512_V")) {type=2;bsize=0;name="Flash64";}
        else if(has("FLASH1M_V")) {type=2;bsize=1;name="Flash128";}
        rom.resize((size+3)&~size_t(3),0xff);
    }
    // EmulatedCartridge.Config as a register: enabled[0], backupType[2:1], backupSize[3], backupAutodetect[4].
    uint32_t config() const { return 1u|(type<<1)|(bsize<<3)|(uint32_t(autodetect)<<4); }
};

// What one core shows on an enabled clock (the fields the cycle comparison names).
struct CycleRecord { uint64_t a, b; };
static const char *diff_field(const CycleRecord &x,const CycleRecord &y) {
    const uint64_t a=x.a^y.a, b=x.b^y.b;
    if(a&0x3) return "rcnt mode";
    if(a&(0x7fffull<<2)) return "SIOCNT";
    if(a&(1ull<<17)) return "SIOCNT start";
    if(a&(0xffffull<<18)) return "SIO send data";
    if(a&(0xffffull<<34)) return "SIOMULTI0";
    if(b&0xffff) return "SIOMULTI1";
    if(a&(1ull<<50)) return "link interrupt";
    if(b&(0xffffull<<16)) return "pixel";
    if(b&(3ull<<32)) return "hblank/vblank";
    if(b&(0xfffffull<<34)) return "audio";
    return nullptr;
}

struct CoreView {
    Framebuffer screen{240,160};
    uint64_t trace=14695981039346656037ull, enabled=0, irqs=0;
    std::vector<uint64_t> frame_trace;       // trace after each FrameCycles enabled clocks
    std::vector<CycleRecord> cycles;         // the cycle window
    bool prev_irq=false;
    void ppm(const fs::path &p) {
        std::ofstream f(p,std::ios::binary);f<<"P6\n240 160\n255\n";
        const auto &b=screen.renderBuffer();
        for(size_t i=0;i<b.size();i+=4) {f.put(b[i+2]);f.put(b[i+1]);f.put(b[i]);}
    }
};
static inline void mix(uint64_t &h, uint64_t v) { h=(h^v)*1099511628211ull; }

enum { OpWrite, OpRead, OpRequest, OpWait };
enum { TagNone, TagLocal, TagDv, TagDh, TagNoise, TagCheck };
struct Op { uint32_t kind, addr, data, tag, gap; };

struct Console {
    VerilatedContext context;
    std::unique_ptr<VSimDualGba> top;
    SdramChip sdram;
    SramChip sram;
    CoreView c[2];                           // 0: visible core, 1: hidden core
    const Options *o=nullptr;
    const char *name="x";
    bool player2=false, noisy=false;
    uint64_t steps=0;
    unsigned rng=1;
    // Host bus (as SpiReceiverFifo: enable for at least two clocks, done taken from the second on).
    std::deque<Op> ops; Op cur{}; bool active=false; uint32_t op_clocks=0, gap_left=0;
    uint64_t host_reads=0, host_writes=0, noise_reads=0, check_errors=0;
    // ESP32.
    bool booted=false, run_allowed=false, run_written=false;
    std::deque<uint32_t> writes;
    std::vector<Sample> history; Sample pending{};
    uint64_t sent=0; uint32_t expected=0, previous=0; uint64_t gaps=0;
    std::vector<std::pair<unsigned,unsigned>> script; uint64_t cursor=0; uint32_t held=0;
    uint32_t rom_bytes=0; std::vector<uint32_t> check_expect;

    unsigned random() { return rng=rng*1664525u+1013904223u; }
    void op(uint32_t kind,uint32_t addr,uint32_t data=0,uint32_t tag=TagNone,int gap=-1) {
        ops.push_back({kind,addr,data,tag,gap<0?(booted?o->spi_gap:0u):uint32_t(gap)});
    }
    void command(uint32_t cmd,uint32_t arg=0) {
        op(OpWrite,0xF0000000u,cmd); op(OpWrite,0xF0000004u,arg); op(OpRequest,0);
    }
    void init(const Options &opt,const Cart &cart,const std::vector<uint8_t> &bios,bool p2) {
        o=&opt; player2=p2;
        top=std::make_unique<VSimDualGba>(&context);
        auto &t=*top;
        t.clock=0;t.io_clockIn50M=0;t.reset=1;
        t.io_host_mem_enable=0;t.io_host_mem_write=0;t.io_host_mem_address=0;t.io_host_mem_dataWrite=0;
        t.io_host_commandHost_request=0;
        t.io_host_commandCore_busy=0;t.io_host_commandCore_done=0;t.io_host_commandCore_error=0;
        buttons(0);
        t.io_sram_dataIn=0;t.io_sdram_dataIn=0;
        for(size_t i=0;i<cart.rom.size();i+=4) {
            uint32_t w; memcpy(&w,cart.rom.data()+i,4); sdram.poke32(uint32_t(i),w);
        }
        // Both saves erased.
        for(uint32_t a=BackupBase;a<0x2000000u;a+=4) sdram.poke32(a,0xffffffffu);
        rom_bytes=uint32_t(cart.rom.size());
        expected=opt.delay;
        // What the firmware does before a game starts.
        for(uint32_t i=0;i<bios.size();i+=4) { uint32_t w; memcpy(&w,bios.data()+i,4); op(OpWrite,0x10000000u+i,w); }
        // The ROM is put into the chip model directly; read a few words back through the real path.
        for(uint32_t i=0;i<8;i++) {
            const uint32_t a=(i*0x9E3779B1u)%rom_bytes&~3u;
            uint32_t w; memcpy(&w,cart.rom.data()+a,4); check_expect.push_back(w);
            op(OpRead,0x30000000u+a,0,TagCheck);
        }
        op(OpWrite,0x0000,cart.config());
        op(OpWrite,0x0004,uint32_t(cart.size-1));
        op(OpWrite,0x0008,0);
        command(0x0102);                                     // setup complete
        op(OpWrite,0x00C8,opt.phase);
        op(OpWrite,0x00A4,control(false));
        command(0x0200,1);                                   // focus
        command(0x0100);                                     // core run
    }
    uint32_t control(bool run) const { return 1u|(run?2u:0u)|(o->delay<<4)|(player2?0x80u:0u); }
    // Mask bits as in KEYINPUT: A B Select Start Right Left Up Down R L.
    void buttons(unsigned m) {
        auto &t=*top;
        t.io_input_buttons_a=m&1;t.io_input_buttons_b=(m>>1)&1;t.io_input_buttons_select=(m>>2)&1;
        t.io_input_buttons_start=(m>>3)&1;t.io_input_buttons_right=(m>>4)&1;t.io_input_buttons_left=(m>>5)&1;
        t.io_input_buttons_up=(m>>6)&1;t.io_input_buttons_down=(m>>7)&1;
        t.io_input_buttons_r=(m>>8)&1;t.io_input_buttons_l=(m>>9)&1;
        t.io_input_buttons_x=0;t.io_input_buttons_y=0;
    }
    void on_read(uint32_t tag,uint32_t v) {
        switch(tag) {
        case TagLocal:
            if(v>>31) { pending.apply=(v>>10)&0xffff; pending.buttons=v&0x3ff; op(OpRead,0x00B4,0,TagDv); }
            break;
        case TagDv: pending.dv=v; op(OpRead,0x00B8,0,TagDh); break;
        case TagDh: pending.dh=v; history.push_back(pending); op(OpWrite,0x00BC,1); break;
        case TagCheck:
            if(v!=check_expect[check_errors>>32]) check_errors++;
            check_errors+=1ull<<32;
            break;
        default: break;
        }
    }
    // Before a system clock edge: take what the bus shows, then choose the pins of the next clock.
    void host() {
        auto &t=*top;
        if(active) {
            op_clocks++;
            bool done=false;
            if(cur.kind==OpWrite || cur.kind==OpRead) {
                if(op_clocks>=2 && t.io_host_mem_done) {
                    done=true;
                    if(cur.kind==OpRead) { host_reads++; on_read(cur.tag,t.io_host_mem_dataRead); } else host_writes++;
                }
            } else if(cur.kind==OpRequest) {
                if(t.io_host_commandHost_error) throw std::runtime_error("Host command refused");
                done=t.io_host_commandHost_done;
            } else done=op_clocks>=cur.data;
            if(done) { active=false; gap_left=1+cur.gap; }
        } else if(gap_left) gap_left--;
        if(!active && !gap_left) {
            if(ops.empty()) esp();
            if(!ops.empty()) { cur=ops.front(); ops.pop_front(); active=true; op_clocks=0; }
        }
    }
    // After the edge: the pins of the clock that starts now.
    void host_pins() {
        auto &t=*top;
        const bool mem=active && (cur.kind==OpWrite || cur.kind==OpRead);
        t.io_host_mem_enable=mem; t.io_host_mem_write=mem && cur.kind==OpWrite;
        t.io_host_mem_address=cur.addr; t.io_host_mem_dataWrite=cur.data;
        t.io_host_commandHost_request=active && cur.kind==OpRequest;
    }
    // The bus is free: what the firmware would do next.
    void esp() {
        auto &t=*top;
        booted=true;
        if(run_allowed && !run_written) { run_written=true; op(OpWrite,0x00A4,control(true)); return; }
        if(t.io_host_commandCore_request) { op(OpRead,0x00B0,0,TagLocal); return; }
        if(!writes.empty()) { op(OpWrite,0x00AC,writes.front()); writes.pop_front(); return; }
        if(noisy && run_written && random()%1000000u<o->noise_ppm) {
            noise_reads++;
            const unsigned r=random();
            if(r&1) op(OpRead,0x40000000u+((r>>1)%0x80000u&~1u),0,TagNoise,0);
            else op(OpRead,0x30000000u+((r>>1)%rom_bytes&~3u),0,TagNoise,0);
        }
    }
    void probe(CoreView &v,bool enable,unsigned rcnt,unsigned siocnt,bool start,unsigned a,unsigned b0,unsigned b1,
               bool irq,unsigned pixel,bool valid,bool hblank,bool vblank,unsigned left,unsigned right) {
        if(!enable) return;
        const CycleRecord r{uint64_t(rcnt)|(uint64_t(siocnt)<<2)|(uint64_t(start)<<17)|(uint64_t(a)<<18)|
            (uint64_t(b0)<<34)|(uint64_t(irq)<<50),
            uint64_t(b1)|(uint64_t(valid?(0x8000u|pixel):0u)<<16)|(uint64_t(hblank)<<32)|(uint64_t(vblank)<<33)|
            (uint64_t(left)<<34)|(uint64_t(right)<<44)};
        const uint64_t frame=v.enabled/FrameCycles;
        if(frame>=o->diff_from && frame<o->diff_to) v.cycles.push_back(r);
        mix(v.trace,r.a); mix(v.trace,r.b);
        if(irq && !v.prev_irq) v.irqs++;
        v.prev_irq=irq;
        v.screen.update(hblank,vblank);
        if(valid && !hblank && !vblank) v.screen.pushBGR(pixel);
        if(++v.enabled%FrameCycles==0) v.frame_trace.push_back(v.trace);
    }
    void chips() {
        auto &t=*top;
        t.io_sram_dataIn=sram.pins(t.io_sram_weN,t.io_sram_oeN,t.io_sram_writeMaskN,t.io_sram_address,
            t.io_sram_dataOut,t.io_sram_dataDir);
    }
    // One SDRAM clock; `system` is the level of the system clock in it.
    void fast(bool system) {
        auto &t=*top;
        sdram.clock(t.io_sdram_cke,(unsigned(t.io_sdram_cs)<<3)|(unsigned(t.io_sdram_ras)<<2)|
            (unsigned(t.io_sdram_cas)<<1)|unsigned(t.io_sdram_we),t.io_sdram_bank,t.io_sdram_address,
            t.io_sdram_dataOut,t.io_sdram_dqm);
        t.clock=system;t.io_clockIn50M=1;t.eval();
        t.io_sdram_dataIn=sdram.dq;
        chips();
    }
    void low() { auto &t=*top; t.io_clockIn50M=0;t.eval();chips(); }
    // One system clock = four SDRAM clocks, rising edges aligned.
    void step() {
        auto &t=*top;
        steps++;
        probe(c[0],t.probe_c0_enable,t.probe_c0_rcntMode,t.probe_c0_siocnt,t.probe_c0_start,t.probe_c0_dataA,
            t.probe_c0_dataB0,t.probe_c0_dataB1,t.probe_c0_irq,t.probe_c0_pixel,t.probe_c0_pixelValid,
            t.probe_c0_hblank,t.probe_c0_vblank,t.probe_c0_apuLeft,t.probe_c0_apuRight);
        probe(c[1],t.probe_c1_enable,t.probe_c1_rcntMode,t.probe_c1_siocnt,t.probe_c1_start,t.probe_c1_dataA,
            t.probe_c1_dataB0,t.probe_c1_dataB1,t.probe_c1_irq,t.probe_c1_pixel,t.probe_c1_pixelValid,
            t.probe_c1_hblank,t.probe_c1_vblank,t.probe_c1_apuLeft,t.probe_c1_apuRight);
        // Buttons for the frame they will be applied in (the module adds its delay).
        const unsigned apply=unsigned(t.probe_frame)+o->delay;
        while(cursor<script.size() && script[cursor].first<=apply) held=script[cursor++].second;
        host();
        fast(true);
        host_pins();
        buttons(held);
        low();
        fast(true); low();
        fast(false); low();
        fast(false); low();
        context.timeInc(1);
    }
    // Partner samples from one packet: only the next frames in order, never across a gap.
    void receive(const Packet &p,unsigned skew) {
        for(const auto &s:p.samples) {
            if(s.apply<expected) continue;
            if(s.apply>expected) {gaps++;break;}
            writes.push_back((s.apply<<10)|(skew?previous:s.buttons));
            previous=s.buttons;
            expected++;
        }
    }
    void checkpoint(const std::string &file,bool saving) {
        if(saving) { VerilatedSave os; os.open((file+".model").c_str()); os<<*top; os.close(); }
        else { VerilatedRestore is; is.open((file+".model").c_str()); if(!is.isOpen()) throw std::runtime_error("Cannot read "+file+".model"); is>>*top; is.close(); }
        FILE *f=fopen((file+".state").c_str(),saving?"wb":"rb");
        if(!f) throw std::runtime_error("Cannot open "+file+".state");
        Ck k{f,saving};
        if(saving) { sdram.save(f,BackupBase); sram.save(f); }
        else k.ok=sdram.load(f,BackupBase) && sram.load(f);
        k.pod(steps);k.pod(rng);k.seq(ops);k.pod(cur);k.pod(active);k.pod(op_clocks);k.pod(gap_left);
        k.pod(host_reads);k.pod(host_writes);k.pod(noise_reads);k.pod(check_errors);
        k.pod(booted);k.pod(run_allowed);k.pod(run_written);k.seq(writes);k.seq(history);k.pod(pending);
        k.pod(sent);k.pod(expected);k.pod(previous);k.pod(gaps);k.pod(cursor);k.pod(held);
        for(auto &v:c) { k.pod(v.trace);k.pod(v.enabled);k.pod(v.irqs);k.pod(v.prev_irq);k.seq(v.frame_trace); }
        fclose(f);
        if(!k.ok) throw std::runtime_error("Checkpoint "+file+" is not usable");
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
            else if(a=="--seed")o.seed=std::stoul(v);else if(a=="--loss-permille")o.loss_permille=std::stoul(v);
            else if(a=="--latency-min")o.latency_min=std::stoul(v);else if(a=="--latency-max")o.latency_max=std::stoul(v);
            else if(a=="--resend")o.resend=std::stoul(v);else if(a=="--start-late")o.start_late=std::stoul(v);
            else if(a=="--skew")o.skew=std::stoul(v);else if(a=="--phase")o.phase=std::stoul(v);
            else if(a=="--swap")o.swap=std::stoul(v);else if(a=="--noise-ppm")o.noise_ppm=std::stoul(v);
            else if(a=="--spi-gap")o.spi_gap=std::stoul(v);
            else if(a=="--cycle-diff-from")o.diff_from=std::stoul(v);else if(a=="--cycle-diff-to")o.diff_to=std::stoul(v);
            else if(a=="--save")o.save=v;else if(a=="--save-every")o.save_every=std::stoul(v);else if(a=="--load")o.load=v;
            else throw std::runtime_error("Unknown option "+a);
        }
        if(o.bios.empty() || o.rom.empty() || o.out.empty() || !o.frames || !o.checkpoint ||
           !o.resend || o.latency_max<o.latency_min || o.delay<1 || o.delay>4 || o.phase>=(1u<<18) || o.swap>1)
            throw std::runtime_error("Required: --bios PATH --rom PATH --out DIR [options]; delay 1..4");
        fs::create_directories(o.out);
        auto bios=read_bytes(o.bios);
        if(bios.size()!=16384) throw std::runtime_error("A 16384-byte GBA BIOS is required");
        Cart cart; cart.load(o.rom);
        std::cerr<<"rom_bytes="<<cart.size<<" backup="<<cart.name<<'\n';
        auto consoles=std::make_unique<Console[]>(2);
        auto &X=consoles[0], &Y=consoles[1];
        X.name="x"; Y.name="y";
        X.init(o,cart,bios,o.swap==1);
        Y.init(o,cart,bios,o.swap==0);
        Y.noisy=true; Y.rng=o.seed;
        if(!o.inputs.empty()) {
            std::ifstream f(o.inputs);
            if(!f) throw std::runtime_error("Cannot read input script");
            std::string line;
            while(std::getline(f,line)) {
                if(line.empty() || line[0]=='#') continue;
                std::istringstream row(line); unsigned frame,side,mask;
                if(!(row>>frame>>side>>mask) || side>1 || mask>1023)
                    throw std::runtime_error("Bad script row: "+line);
                // Script side 0 is player 1.
                consoles[side^o.swap].script.push_back({frame,mask});
            }
        }
        std::deque<Packet> inbox[2];
        unsigned radio=o.seed*2654435761u+1;
        auto rnd=[&]{ return radio=radio*1664525u+1013904223u; };
        uint64_t packets=0,lost=0,idle=0,q=0,compared=0,diff_frames=0;
        long first_diff=-1, first_trace_diff[2]={-1,-1};
        auto checkpoint=[&](const std::string &prefix,bool saving) {
            for(int k=0;k<2;k++) consoles[k].checkpoint(prefix+"."+consoles[k].name,saving);
            FILE *f=fopen((prefix+".main").c_str(),saving?"wb":"rb");
            if(!f) throw std::runtime_error("Cannot open "+prefix+".main");
            Ck k{f,saving};
            k.pod(q);k.pod(compared);k.pod(diff_frames);k.pod(first_diff);k.pod(first_trace_diff);
            k.pod(radio);k.pod(packets);k.pod(lost);
            for(auto &in:inbox) {
                uint64_t n=in.size(); k.pod(n); if(!saving) in.resize(n);
                for(auto &p:in) { k.pod(p.due); k.seq(p.samples); }
            }
            fclose(f);
            if(!k.ok) throw std::runtime_error("Checkpoint "+prefix+" is not usable");
        };
        if(o.load.empty()) {
            // Reset; Y waits a little longer, so its SDRAM refresh runs at another phase.
            for(int k=0;k<2;k++) {
                auto &c=consoles[k];
                const unsigned n=16+(k?o.seed*7919u%20000u:0u);
                // No host activity in reset.
                auto ops=std::move(c.ops); c.ops.clear(); c.ops.push_back({OpWait,0,n+8,TagNone,0});
                for(unsigned i=0;i<n;i++) { if(i==16) c.top->reset=0; c.step(); }
                c.top->reset=0;
                c.ops=std::move(ops); c.active=false; c.gap_left=0; c.steps=0;
            }
        } else {
            for(int k=0;k<2;k++) consoles[k].top->reset=0;
            checkpoint(o.load,false);
            std::cerr<<"loaded frame="<<compared<<'\n';
        }
        const uint64_t loaded=compared;
        std::ofstream csv(o.out/"frames.csv",o.load.empty()?std::ios::trunc:std::ios::app);
        if(o.load.empty())
            csv<<"frame,x_visible,y_hidden,x_hidden,y_visible,equal,trace_p1_equal,trace_p2_equal,"
                 "irq_x_visible,irq_x_hidden,x_cycles,x_stalls,y_cycles,y_stalls\n";
        const auto t0=std::chrono::steady_clock::now();
        // Which core view of each console is player 1.
        const int xp1=o.swap?1:0, yp1=o.swap?0:1;
        for(;;q++) {
            // Not in the first quantum: the module loads the start phase while run is low.
            if(q>=1) X.run_allowed=true;
            if(q>=o.start_late) Y.run_allowed=true;
            for(int k=0;k<2;k++) {
                auto &in=inbox[k];
                for(auto it=in.begin();it!=in.end();) {
                    if(it->due>q) {++it;continue;}
                    consoles[k].receive(*it,k==1?o.skew:0);
                    it=in.erase(it);
                }
            }
            std::exception_ptr failed;
            std::thread ty([&]{ try { for(unsigned i=0;i<Quantum;i++) Y.step(); } catch(...) { failed=std::current_exception(); } });
            try { for(unsigned i=0;i<Quantum;i++) X.step(); } catch(...) { ty.join(); throw; }
            ty.join();
            if(failed) std::rethrow_exception(failed);
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
            const uint64_t done=std::min(X.history.size(),Y.history.size());
            idle=done==compared?idle+1:0;
            if(idle>40000) throw std::runtime_error("No progress: consoles stuck at frame "+std::to_string(done));
            bool save_now=false;
            for(;compared<done && compared<=o.frames;compared++) {
                const auto &x=X.history[compared], &y=Y.history[compared];
                if(x.apply!=compared+o.delay || y.apply!=compared+o.delay) throw std::runtime_error("Frame numbering broke");
                const bool equal=x.dv==y.dh && x.dh==y.dv;
                if(!equal) {diff_frames++; if(first_diff<0) first_diff=long(compared);}
                // The simulator's own trace of each player's core, frame by frame of that core.
                bool teq[2]={true,true};
                const int xi[2]={xp1,1-xp1}, yi[2]={yp1,1-yp1};
                for(int p=0;p<2;p++) {
                    const auto &a=X.c[xi[p]].frame_trace, &b=Y.c[yi[p]].frame_trace;
                    if(compared && compared<=a.size() && compared<=b.size()) {
                        teq[p]=a[compared-1]==b[compared-1];
                        if(!teq[p] && first_trace_diff[p]<0) first_trace_diff[p]=long(compared);
                    }
                }
                csv<<compared<<std::hex<<','<<x.dv<<','<<y.dh<<','<<x.dh<<','<<y.dv<<std::dec<<','<<equal
                   <<','<<teq[0]<<','<<teq[1]<<','<<X.c[0].irqs<<','<<X.c[1].irqs
                   <<','<<X.top->probe_cycles<<','<<X.top->probe_stalls<<','<<Y.top->probe_cycles<<','<<Y.top->probe_stalls<<'\n';
                if(compared && (compared%o.checkpoint==0 || compared==o.frames || first_diff==long(compared))) {
                    for(int k=0;k<2;k++) for(int i=0;i<2;i++)
                        consoles[k].c[i].ppm(o.out/(std::string(consoles[k].name)+(i?"-hidden-":"-visible-")+std::to_string(compared)+".ppm"));
                    csv.flush();
                    std::cerr<<"frame="<<compared<<" equal="<<equal<<" irq_x="<<X.c[0].irqs<<'/'<<X.c[1].irqs
                        <<" irq_y="<<Y.c[0].irqs<<'/'<<Y.c[1].irqs<<" seconds="
                        <<std::chrono::duration<double>(std::chrono::steady_clock::now()-t0).count()<<'\n';
                }
                if(o.save_every && compared && compared%o.save_every==0) save_now=true;
            }
            if(save_now && !o.save.empty()) { q++; checkpoint(o.save+"-"+std::to_string(compared-1),true); q--; }
            if(compared>o.frames) break;
        }
        if(!o.save.empty() && !o.save_every) { q++; checkpoint(o.save,true); }
        // Cycle by cycle: the same player's core on the two consoles.
        if(o.diff_to>o.diff_from) {
            std::ofstream d(o.out/"cycle-diff.txt");
            const int xi[2]={xp1,1-xp1}, yi[2]={yp1,1-yp1};
            for(int p=0;p<2;p++) {
                const auto &a=X.c[xi[p]].cycles, &b=Y.c[yi[p]].cycles;
                const size_t n=std::min(a.size(),b.size());
                size_t i=0; const char *field=nullptr;
                for(;i<n;i++) if((field=diff_field(a[i],b[i]))) break;
                d<<"player"<<p+1<<" cycles="<<n;
                if(i<n) d<<" first_diff_cycle="<<uint64_t(o.diff_from)*FrameCycles+i<<" frame="<<o.diff_from+i/FrameCycles
                    <<" cycle_in_frame="<<i%FrameCycles<<" field=\""<<field<<"\""<<std::hex<<" x="<<a[i].a<<'/'<<a[i].b
                    <<" y="<<b[i].a<<'/'<<b[i].b<<std::dec;
                else d<<" identical";
                d<<'\n';
            }
        }
        const double secs=std::chrono::duration<double>(std::chrono::steady_clock::now()-t0).count();
        std::ostringstream s;
        s<<"frames="<<o.frames<<" delay="<<o.delay<<" swap="<<o.swap<<" phase="<<o.phase<<" skew="<<o.skew
         <<" first_diff="<<first_diff<<" diff_frames="<<diff_frames
         <<" first_trace_diff_p1="<<first_trace_diff[0]<<" first_trace_diff_p2="<<first_trace_diff[1]
         <<" packets="<<packets<<" lost="<<lost<<" gaps="<<X.gaps+Y.gaps;
        for(int k=0;k<2;k++) {
            auto &c=consoles[k]; auto &t=*c.top; const char *n=c.name;
            s<<" irq_"<<n<<"="<<c.c[0].irqs<<'/'<<c.c[1].irqs
             <<" steps_"<<n<<"="<<c.steps<<" cycles_"<<n<<"="<<t.probe_cycles<<" stalls_"<<n<<"="<<t.probe_stalls
             <<" stall_ewram1_"<<n<<"="<<t.probe_stallEwram1<<" stall_cart1_"<<n<<"="<<t.probe_stallCart1
             <<" stall_ewram2_"<<n<<"="<<t.probe_stallEwram2<<" stall_cart2_"<<n<<"="<<t.probe_stallCart2
             <<" stall_sync_"<<n<<"="<<t.probe_stallSync
             <<" sdram_reads_"<<n<<"="<<c.sdram.reads<<" sdram_writes_"<<n<<"="<<c.sdram.writes
             <<" sram_writes_"<<n<<"="<<c.sram.writes<<" host_reads_"<<n<<"="<<c.host_reads
             <<" noise_reads_"<<n<<"="<<c.noise_reads<<" rom_check_"<<n<<"="<<(c.check_errors>>32)-(c.check_errors&0xffffffffu)<<"/"<<(c.check_errors>>32);
        }
        s<<" seconds="<<secs<<" s_per_frame="<<secs/std::max<uint64_t>(1,compared-loaded)<<" loaded_from="<<loaded;
        std::ofstream(o.out/"result.txt")<<s.str()<<'\n';
        std::cout<<"RESULT "<<s.str()<<'\n';
        return first_diff>=0 ? 1 : 0;
    } catch(const std::exception &e) {std::cerr<<"ERROR: "<<e.what()<<'\n';return 4;}
}
