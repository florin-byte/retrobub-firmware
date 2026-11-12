// SPDX-License-Identifier: GPL-3.0-only
// GB-154: two "consoles", each running two production GBA cores joined by a
// local multiplayer cable on their physical link port (wireless bridge off).
// Buttons are applied on both consoles at the same emulated frame. Console Y
// additionally has its core clock enable gated at random (memory waits).
// Questions: do the games link over the physical port, and do both consoles
// stay identical frame by frame? Also counts what the games ask of memory.
#include <cstdio>
#include <verilated.h>
#include <verilated_save.h>
#include "VSimCableGba.h"
#include "VSimCableGba___024root.h"
#include "framebuffer.hpp"
#include <algorithm>
#include <chrono>
#include <cstring>
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
static std::vector<uint8_t> read_bytes(const fs::path &p) {
    std::ifstream f(p, std::ios::binary);
    if (!f) throw std::runtime_error("Cannot read " + p.string());
    return {std::istreambuf_iterator<char>(f), {}};
}
struct Options {
    fs::path bios, rom, out, inputs;
    unsigned frames=600, checkpoint=60, delay=0, seed=17, consoles=2;
    unsigned gate_permille=5, gate_max=80; // console Y: chance per cycle, longest pause
    unsigned phase=0;                      // core 2 starts this many core clocks after core 1, on both consoles
    unsigned parent=0;                     // which core has SI tied low (the cable's parent end)
    bool separate_stall=false;             // each core stalls alone instead of both together
    bool trace_pins=false;
    std::string save, load;                // checkpoint files (prefix); for finding button scripts only
};
struct Input { unsigned frame, side, mask; };
static inline void mix(uint64_t &h, uint64_t v) { h=(h^v)*1099511628211ull; }
static unsigned g_trace_from=0, g_trace_to=0;

struct Cart {
    std::vector<uint8_t> rom; size_t size=0;
    unsigned type=0, bsize=0; bool autodetect=false; size_t backup_bytes=0; const char *name="none";
    bool has(const char *s) const {
        size_t len=strlen(s);
        for(size_t i=0;i+len<=size;i+=4) if(!memcmp(rom.data()+i,s,len)) return true;
        return false;
    }
    void load(const fs::path &p) {
        rom=read_bytes(p); size=rom.size();
        if(size<192 || size>32u*1024*1024) throw std::runtime_error("ROM must be at most 32 MiB");
        // Same detection as sim/gba/cartridge.cpp.
        if(has("EEPROM_V")) {type=3;autodetect=true;backup_bytes=8*1024;name="EEPROM";}
        else if(has("SRAM_V") || has("SRAM_F_V")) {type=1;backup_bytes=32*1024;name="SRAM";}
        else if(has("FLASH_V") || has("FLASH512_V")) {type=2;bsize=0;backup_bytes=64*1024;name="Flash64";}
        else if(has("FLASH1M_V")) {type=2;bsize=1;backup_bytes=128*1024;name="Flash128";}
    }
};

struct Core {
    VerilatedContext context;
    std::unique_ptr<VSimCableGba> top;
    std::vector<uint8_t> backup;
    Framebuffer screen{240,160};
    uint64_t trace=14695981039346656037ull; // every enabled cycle: link registers, pixels
    uint64_t enabled=0, irqs=0, video_frames=0, multiwait=0;
    uint64_t ew_starts=0, ew_writes=0, rom_reads=0, rom_miss=0, last_ew_start=0;
    uint32_t last_rom_word=~0u, prev_ew_addr=0; bool prev_ew=false, prev_vblank=false, ew_started=false;
    uint64_t t_prev=~0ull; int trace_id=-1;
    void init(const Options &o, const Cart &cart, const std::vector<uint8_t> &bios) {
        top=std::make_unique<VSimCableGba>(&context);
        auto &t=*top;
        std::memcpy(&t.rootp->SimCableGba__DOT__biosRom_ext__DOT__Memory,bios.data(),bios.size());
        auto *romMem=reinterpret_cast<uint8_t*>(&t.rootp->SimCableGba__DOT__cartRom_ext__DOT__Memory);
        std::memset(romMem,0xff,32u*1024*1024);
        std::memcpy(romMem,cart.rom.data(),cart.size);
        backup.assign(cart.backup_bytes,0xff);
        t.io_enable=0;t.io_configGBPlayer=0;
        t.io_emuCartConfig_enabled=1;t.io_emuCartConfig_backupType=cart.type;
        t.io_emuCartConfig_backupSize=cart.bsize;t.io_emuCartConfig_backupAutodetect=cart.autodetect;
        t.io_emuCartConfig_hasGpio=0;t.io_emuCartConfig_hasGyro=0;t.io_emuCartConfig_hasAccel=0;
        t.io_emuCartConfig_hasRtc=0;t.io_emuCartConfig_hasRumble=0;
        t.io_emuCartRomSize=cart.size-1;
        t.io_emuCartBackup_done=0;t.io_emuCartBackup_dataRead=0xff;
        t.cable_in_so=1;t.cable_in_si=1;t.cable_in_sd=1;t.cable_in_sc=1;
        buttons(0);
    }
    // Mask bits as in KEYINPUT: A B Select Start Right Left Up Down R L.
    void buttons(unsigned m) {
        auto &t=*top;
        t.io_keypad_a=m&1;t.io_keypad_b=(m>>1)&1;t.io_keypad_select=(m>>2)&1;t.io_keypad_start=(m>>3)&1;
        t.io_keypad_right=(m>>4)&1;t.io_keypad_left=(m>>5)&1;t.io_keypad_up=(m>>6)&1;t.io_keypad_down=(m>>7)&1;
        t.io_keypad_r=(m>>8)&1;t.io_keypad_l=(m>>9)&1;
    }
    // Every system clock: the memories are outside the gated core clock.
    void memory(uint64_t step) {
        auto &t=*top;
        if(t.io_emuCartBackup_enable) {
            size_t a=t.io_emuCartBackup_address;
            if(a<backup.size()) {
                if(t.io_emuCartBackup_write) backup[a]=t.io_emuCartBackup_dataWrite;
                else t.io_emuCartBackup_dataRead=backup[a];
            }
            t.io_emuCartBackup_done=1;
        }
        if(t.probe_multiWait) multiwait++;
        const bool ew=t.probe_ewramEnable; const uint32_t addr=t.probe_ewramAddress;
        ew_started=ew && (!prev_ew || addr!=prev_ew_addr);
        if(ew_started) {ew_starts++;last_ew_start=step;if(t.probe_ewramWrite) ew_writes++;}
        prev_ew=ew;prev_ew_addr=addr;
        if(t.probe_romRead) {
            rom_reads++;
            // The handheld's MiniCache keeps one 32-bit word (two ROM words).
            const uint32_t word=uint32_t(t.probe_romAddress)>>1;
            if(word!=last_rom_word) rom_miss++;
            last_rom_word=word;
        }
    }
    // Only before an enabled clock edge, so a paused console observes exactly
    // the same sequence as one that never pauses.
    void sample(unsigned frame) {
        auto &t=*top;
        enabled++;
        const uint64_t link=(uint64_t(t.probe_rcntMode)<<48)|(uint64_t(t.probe_start)<<47)|
            (uint64_t(t.probe_siocnt)<<32)|(uint64_t(t.probe_dataA)<<16)|uint64_t(t.probe_dataB0);
        if(trace_id>=0 && frame>=g_trace_from && frame<g_trace_to) {
            const uint64_t key=link>>16;
            if(key!=t_prev) {
                fprintf(stderr,"T frame=%u p%d cycle=%llu rcnt=%u siocnt=%04x start=%u send=%04x\n",frame,trace_id+1,
                    (unsigned long long)enabled,unsigned(t.probe_rcntMode),unsigned(t.probe_siocnt),unsigned(t.probe_start),unsigned(t.probe_dataA));
                t_prev=key;
            }
            if(t.probe_irq)
                fprintf(stderr,"T frame=%u p%d cycle=%llu IRQ multi0=%04x multi1=%04x send=%04x\n",frame,trace_id+1,
                    (unsigned long long)enabled,unsigned(t.probe_dataB0),unsigned(t.probe_dataB1),unsigned(t.probe_dataA));
        }
        if(t.probe_irq) irqs++;
        if(t.io_ppu_vblank && !prev_vblank) video_frames++;
        prev_vblank=t.io_ppu_vblank;
        screen.update(t.io_ppu_hblank,t.io_ppu_vblank);
        if(t.io_ppu_valid && !t.io_ppu_hblank && !t.io_ppu_vblank) {
            screen.pushBGR(t.io_ppu_pixel);
            mix(trace,0x10000u|t.io_ppu_pixel);
        }
        mix(trace,link);
        mix(trace,(uint64_t(t.probe_dataB1)<<1)|uint64_t(t.probe_irq));
    }
    void ppm(const fs::path &p) {
        std::ofstream f(p,std::ios::binary);f<<"P6\n240 160\n255\n";
        const auto &b=screen.renderBuffer();
        for(size_t i=0;i<b.size();i+=4) {f.put(b[i+2]);f.put(b[i+1]);f.put(b[i]);}
    }
    void checkpoint(const std::string &file,bool load) {
        if(load) {
            VerilatedRestore is; is.open(file.c_str()); if(!is.isOpen()) throw std::runtime_error("Cannot read "+file);
            is>>*top; is.close();
            auto b=read_bytes(file+".backup"); if(b.size()==backup.size()) backup=b;
        } else {
            VerilatedSave os; os.open(file.c_str()); if(!os.isOpen()) throw std::runtime_error("Cannot write "+file);
            os<<*top; os.close();
            std::ofstream(file+".backup",std::ios::binary).write(reinterpret_cast<const char*>(backup.data()),backup.size());
        }
    }
    const void *ewram() const { return &top->rootp->SimCableGba__DOT__ewram_sram_ext__DOT__Memory; }
    static size_t ewram_bytes() { return 256*1024; }
};

struct Console {
    Core c[2];
    uint64_t steps=0, paused=0, ew_same=0, ew_near=0;
    unsigned rng=1, pause_left=0, permille=0, pause_max=1, phase_left=0, parent=0, frame=0;
    bool separate_stall=false, trace_pins=false; unsigned p_prev=99;
    unsigned random() { return rng=rng*1664525u+1013904223u; }
    void reset(bool on) { for(auto &x:c) x.top->reset=on; }
    // One system clock. Returns true when core 1 advanced.
    bool step(bool allow_pause) {
        bool run=true;
        if(allow_pause && permille) {
            if(pause_left) {pause_left--;run=false;}
            else if(random()%1000<permille) {pause_left=random()%pause_max;run=false;}
        }
        steps++;
        // Multiplayer cable, two ends. SD and SC are shared and pulled up; the
        // parent end has SI tied low; the parent's SO feeds the child's SI.
        bool sd=true, sc=true, so[2];
        for(int i=0;i<2;i++) {
            auto &t=*c[i].top;
            if(t.cable_dir_sd) sd=sd && t.cable_out_sd;
            if(t.cable_dir_sc) sc=sc && t.cable_out_sc;
            so[i]=t.cable_dir_so?bool(t.cable_out_so):true;
        }
        bool want[2]={run, run && !phase_left}, stall[2], en[2];
        for(int i=0;i<2;i++) {
            auto &t=*c[i].top;
            t.cable_in_sd=sd;t.cable_in_sc=sc;t.cable_in_so=so[i];
            t.cable_in_si=(unsigned(i)==parent)?0:so[parent];
            t.io_enable=want[i];t.clock=0;t.eval();
            stall[i]=t.io_emuCartStall;
        }
        const bool any=(want[0] && stall[0]) || (want[1] && stall[1]);
        for(int i=0;i<2;i++) {
            en[i]=want[i] && !(separate_stall?stall[i]:any);
            c[i].top->io_enable=en[i];c[i].top->eval();
            c[i].memory(steps);
            if(en[i]) c[i].sample(frame);
        }
        if(c[0].top->probe_ewramEnable && c[1].top->probe_ewramEnable) ew_same++;
        for(int i=0;i<2;i++)
            if(c[i].ew_started && c[1-i].last_ew_start && steps-c[1-i].last_ew_start<3) ew_near++;
        if(trace_pins && frame>=g_trace_from && frame<g_trace_to) {
            const unsigned p=(sd<<3)|(sc<<2)|(so[0]<<1)|unsigned(so[1]);
            if(p!=p_prev) {
                fprintf(stderr,"P frame=%u cycle=%llu sd=%u sc=%u so1=%u so2=%u\n",frame,(unsigned long long)c[0].enabled,sd,sc,so[0],so[1]);
                p_prev=p;
            }
        }
        if(en[0] && phase_left) phase_left--; // in core clocks of core 1, as DualCoreSync counts it
        if(!run) paused++;
        for(auto &x:c) {x.top->clock=1;x.top->eval();x.context.timeInc(1);}
        return en[0];
    }
};

int main(int argc,char **argv) {
    try {
        Options o;
        for(int i=1;i<argc;i++) {
            std::string a=argv[i];
            if(a=="--separate-stall") {o.separate_stall=true;continue;}
            if(a=="--trace-pins") {o.trace_pins=true;continue;}
            if(i+1>=argc) throw std::runtime_error("Missing value for "+a);
            std::string v=argv[++i];
            if(a=="--bios")o.bios=v;else if(a=="--rom")o.rom=v;else if(a=="--out")o.out=v;
            else if(a=="--inputs")o.inputs=v;else if(a=="--frames")o.frames=std::stoul(v);
            else if(a=="--checkpoint")o.checkpoint=std::stoul(v);else if(a=="--delay")o.delay=std::stoul(v);
            else if(a=="--seed")o.seed=std::stoul(v);else if(a=="--gate-permille")o.gate_permille=std::stoul(v);
            else if(a=="--gate-max")o.gate_max=std::stoul(v);else if(a=="--phase")o.phase=std::stoul(v);
            else if(a=="--parent")o.parent=std::stoul(v);else if(a=="--consoles")o.consoles=std::stoul(v);
            else if(a=="--save")o.save=v;else if(a=="--load")o.load=v;
            else if(a=="--trace-from")g_trace_from=std::stoul(v);else if(a=="--trace-to")g_trace_to=std::stoul(v);
            else throw std::runtime_error("Unknown option "+a);
        }
        if(o.bios.empty() || o.rom.empty() || o.out.empty() || !o.frames || !o.checkpoint || !o.gate_max ||
           o.parent>1 || o.consoles<1 || o.consoles>2)
            throw std::runtime_error("Required: --bios PATH --rom PATH --out DIR [options]");
        fs::create_directories(o.out);
        auto bios=read_bytes(o.bios);
        if(bios.size()!=16384) throw std::runtime_error("A 16384-byte GBA BIOS is required");
        Cart cart; cart.load(o.rom);
        std::cerr<<"rom_bytes="<<cart.size<<" backup="<<cart.name<<'\n';
        std::vector<Input> input;
        if(!o.inputs.empty()) {
            std::ifstream f(o.inputs);
            if(!f) throw std::runtime_error("Cannot read input script");
            std::string line;
            while(std::getline(f,line)) {
                if(line.empty() || line[0]=='#') continue;
                std::istringstream row(line); Input v;
                if(!(row>>v.frame>>v.side>>v.mask) || v.side>1 || v.mask>1023)
                    throw std::runtime_error("Bad script row: "+line);
                v.frame+=o.delay;
                input.push_back(v);
            }
            std::stable_sort(input.begin(),input.end(),[](auto a,auto b){return a.frame<b.frame;});
        }
        // Console index 0 = X (never paused), 1 = Y (random pauses).
        const unsigned N=o.consoles;
        auto consoles=std::make_unique<Console[]>(N);
        for(unsigned k=0;k<N;k++) {
            auto &K=consoles[k];
            for(auto &x:K.c) x.init(o,cart,bios);
            K.parent=o.parent;K.separate_stall=o.separate_stall;
            K.reset(true);
            for(unsigned i=0;i<16;i++) K.step(false);
            K.reset(false);
            for(auto &x:K.c) x.enabled=0;
            K.steps=0;K.phase_left=o.phase;
        }
        if(N>1) {consoles[1].rng=o.seed;consoles[1].permille=o.gate_permille;consoles[1].pause_max=o.gate_max;}
        consoles[0].c[0].trace_id=0;consoles[0].c[1].trace_id=1;consoles[0].trace_pins=o.trace_pins;
        std::ofstream csv(o.out/"frames.csv");
        csv<<"frame,x_p1,y_p1,x_p2,y_p2,equal,irq_p1,irq_p2,ew_p1,ew_p2,ew_same_cycle,ew_within3,rom_p1,rom_p2,rom_miss_p1,rom_miss_p2,multiwait,y_paused\n";
        size_t cursor=0;
        unsigned start=0;
        if(!o.load.empty()) {
            std::ifstream meta(o.load+".meta");
            if(!(meta>>start)) throw std::runtime_error("Cannot read "+o.load+".meta");
            for(unsigned k=0;k<N;k++) {
                auto &K=consoles[k];
                meta>>K.steps>>K.phase_left>>K.rng>>K.pause_left>>K.paused;
                for(int i=0;i<2;i++) {
                    meta>>K.c[i].enabled;
                    K.c[i].checkpoint(o.load+".k"+std::to_string(k)+"c"+std::to_string(i),true);
                }
            }
            if(!meta) throw std::runtime_error("Checkpoint does not match --consoles");
            // Buttons held at the checkpoint.
            while(cursor<input.size() && input[cursor].frame<start) {
                auto v=input[cursor++];
                for(unsigned k=0;k<N;k++) consoles[k].c[v.side].buttons(v.mask);
            }
            std::cerr<<"loaded frame="<<start<<'\n';
        }
        long first_diff=-1, first_ewram_diff=-1; unsigned diff_frames=0, frames_done=0;
        uint64_t prev[8]={};
        const auto t0=std::chrono::steady_clock::now();
        for(unsigned frame=start;frame<o.frames;frame++) {
            while(cursor<input.size() && input[cursor].frame==frame) {
                auto v=input[cursor++];
                for(unsigned k=0;k<N;k++) consoles[k].c[v.side].buttons(v.mask);
            }
            for(unsigned k=0;k<N;k++) consoles[k].frame=frame;
            const uint64_t target=uint64_t(frame+1)*FrameCycles;
            // The consoles are independent: one thread each.
            std::thread ty([&]{ if(N>1) while(consoles[1].c[0].enabled<target) consoles[1].step(true); });
            while(consoles[0].c[0].enabled<target) consoles[0].step(false);
            ty.join();
            frames_done=frame+1;
            auto &X=consoles[0]; auto &Y=consoles[N-1];
            bool equal=X.c[0].trace==Y.c[0].trace && X.c[1].trace==Y.c[1].trace;
            if(!equal) {diff_frames++; if(first_diff<0) first_diff=frame+1;}
            const uint64_t now[8]={X.c[0].ew_starts,X.c[1].ew_starts,X.ew_same,X.ew_near,
                X.c[0].rom_reads,X.c[1].rom_reads,X.c[0].rom_miss,X.c[1].rom_miss};
            csv<<frame+1<<std::hex<<','<<X.c[0].trace<<','<<Y.c[0].trace<<','<<X.c[1].trace<<','<<Y.c[1].trace<<std::dec
               <<','<<equal<<','<<X.c[0].irqs<<','<<X.c[1].irqs;
            for(int i=0;i<8;i++) {csv<<','<<now[i]-prev[i];prev[i]=now[i];}
            csv<<','<<X.c[0].multiwait+X.c[1].multiwait<<','<<(N>1?Y.paused:0)<<'\n';
            bool shot=(frame+1)%o.checkpoint==0 || frame+1==o.frames || (!equal && first_diff==long(frame+1));
            if(shot) {
                const char *names[2]={"x","y"};
                for(unsigned k=0;k<N;k++) for(int i=0;i<2;i++)
                    consoles[k].c[i].ppm(o.out/(std::string(names[k])+"-p"+std::to_string(i+1)+"-"+std::to_string(frame+1)+".ppm"));
                // Work RAM of the two consoles, compared whole (independent of timing).
                bool ewram_equal=true;
                if(N>1) for(int i=0;i<2;i++)
                    ewram_equal=ewram_equal && !memcmp(X.c[i].ewram(),Y.c[i].ewram(),Core::ewram_bytes());
                if(!ewram_equal && first_ewram_diff<0) first_ewram_diff=frame+1;
                csv.flush();
                std::cerr<<"frame="<<frame+1<<" irq="<<X.c[0].irqs<<'/'<<X.c[1].irqs<<" equal="<<equal
                    <<" ewram_equal="<<ewram_equal<<" p2_cycles="<<X.c[1].enabled<<'\n';
            }
        }
        if(!o.save.empty()) {
            std::ofstream meta(o.save+".meta");
            meta<<frames_done<<'\n';
            for(unsigned k=0;k<N;k++) {
                auto &K=consoles[k];
                meta<<K.steps<<' '<<K.phase_left<<' '<<K.rng<<' '<<K.pause_left<<' '<<K.paused<<'\n';
                for(int i=0;i<2;i++) {
                    meta<<K.c[i].enabled<<'\n';
                    K.c[i].checkpoint(o.save+".k"+std::to_string(k)+"c"+std::to_string(i),false);
                }
            }
        }
        const double secs=std::chrono::duration<double>(std::chrono::steady_clock::now()-t0).count();
        auto &X=consoles[0]; auto &Y=consoles[N-1];
        std::ostringstream s;
        s<<"frames="<<frames_done<<" consoles="<<N<<" parent=p"<<o.parent+1<<" phase="<<o.phase
         <<" stall="<<(o.separate_stall?"separate":"common")
         <<" first_diff="<<first_diff<<" diff_frames="<<diff_frames<<" first_ewram_diff="<<first_ewram_diff
         <<" irq_x="<<X.c[0].irqs<<'/'<<X.c[1].irqs<<" irq_y="<<Y.c[0].irqs<<'/'<<Y.c[1].irqs
         <<" video_x="<<X.c[0].video_frames<<'/'<<X.c[1].video_frames
         <<" cycles_x="<<X.c[0].enabled<<'/'<<X.c[1].enabled<<" steps_x="<<X.steps
         <<" multiwait_x="<<X.c[0].multiwait<<'/'<<X.c[1].multiwait
         <<" ewram_x="<<X.c[0].ew_starts<<'/'<<X.c[1].ew_starts<<" ewram_writes_x="<<X.c[0].ew_writes<<'/'<<X.c[1].ew_writes
         <<" ewram_same_cycle="<<X.ew_same<<" ewram_within3="<<X.ew_near
         <<" rom_x="<<X.c[0].rom_reads<<'/'<<X.c[1].rom_reads<<" rom_miss_x="<<X.c[0].rom_miss<<'/'<<X.c[1].rom_miss
         <<" y_steps="<<(N>1?Y.steps:0)<<" y_paused="<<(N>1?Y.paused:0)
         <<" seconds="<<secs<<" s_per_frame="<<secs/std::max(1u,frames_done-start)<<" loaded_from="<<start;
        std::ofstream(o.out/"result.txt")<<s.str()<<'\n';
        std::cout<<"RESULT "<<s.str()<<'\n';
        return first_diff>=0 || first_ewram_diff>=0 ? 1 : 0;
    } catch(const std::exception &e) {std::cerr<<"ERROR: "<<e.what()<<'\n';return 4;}
}
