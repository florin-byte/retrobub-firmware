// SPDX-License-Identifier: GPL-3.0-only
// GB-156: the two memory chips of the Game Bub board, as the FPGA pins see them.
// Simulation only. The controllers in front of them are the production ones.
#pragma once
#include <cstdint>
#include <cstdio>
#include <vector>

// 32 MiB SDR SDRAM, 16-bit data, 4 banks x 8192 rows x 512 columns (JEDEC behaviour:
// mode register, CAS latency, bursts, clock suspend through CKE, DQM on writes).
// clock() is called once per rising clock edge with the pin values before that edge;
// `dq` is what the chip drives after it.
struct SdramChip {
    std::vector<uint16_t> mem = std::vector<uint16_t>(16u * 1024 * 1024, 0);
    uint16_t dq = 0;
    uint32_t row[4] = {0, 0, 0, 0};
    uint32_t burst_base = 0, burst_col = 0;
    uint8_t cas = 2, read_wait = 0;
    bool reading = false, writing = false, cke_prev = true;
    uint64_t reads = 0, writes = 0, refreshes = 0, actives = 0;

    void clock(bool cke, unsigned command, unsigned bank, unsigned address, uint16_t data, unsigned dqm) {
        const bool running = cke_prev; // CKE low at one edge masks the next one
        cke_prev = cke;
        if (!running) return;
        switch (command) {
        case 0: cas = (address >> 4) & 7; reading = writing = false; break;         // mode register
        case 1: refreshes++; break;                                                 // auto refresh
        case 2: reading = writing = false; break;                                   // precharge
        case 3: row[bank & 3] = address & 0x1fff; actives++; break;                 // row active
        case 4:                                                                     // write
            writing = true; reading = false; writes++;
            burst_base = ((bank & 3u) << 22) | (row[bank & 3] << 9); burst_col = address & 0x1ff;
            break;
        case 5:                                                                     // read
            reading = true; writing = false; reads++; read_wait = cas - 1;
            burst_base = ((bank & 3u) << 22) | (row[bank & 3] << 9); burst_col = address & 0x1ff;
            return; // first word after `cas - 1` more edges
        case 6: reading = writing = false; break;                                   // burst stop
        default: break;                                                             // nop, deselect
        }
        if (writing) {
            uint16_t &w = mem[burst_base | burst_col];
            if (!(dqm & 1)) w = (w & 0xff00) | (data & 0x00ff);
            if (!(dqm & 2)) w = (w & 0x00ff) | (data & 0xff00);
            burst_col = (burst_col + 1) & 0x1ff;
        } else if (reading) {
            if (read_wait > 1) { read_wait--; return; }
            read_wait = 0;
            dq = mem[burst_base | burst_col];
            burst_col = (burst_col + 1) & 0x1ff;
        }
    }
    // The controller keeps the first 16-bit word of an access in the upper half of the
    // 32-bit value. `address` is the byte address the core uses (a multiple of 4).
    void poke32(uint32_t address, uint32_t value) {
        mem[(address >> 1) & 0xffffff] = value >> 16;
        mem[((address >> 1) + 1) & 0xffffff] = value & 0xffff;
    }
    uint32_t peek32(uint32_t address) const {
        return (uint32_t(mem[(address >> 1) & 0xffffff]) << 16) | mem[((address >> 1) + 1) & 0xffffff];
    }
    void save(FILE *f, uint32_t from_byte) const {
        // The ROM below `from_byte` never changes: only the rest is kept.
        fwrite(&dq, sizeof dq, 1, f); fwrite(row, sizeof row, 1, f);
        fwrite(&burst_base, sizeof burst_base, 1, f); fwrite(&burst_col, sizeof burst_col, 1, f);
        fwrite(&cas, 1, 1, f); fwrite(&read_wait, 1, 1, f);
        uint8_t flags = (reading ? 1 : 0) | (writing ? 2 : 0) | (cke_prev ? 4 : 0); fwrite(&flags, 1, 1, f);
        fwrite(mem.data() + from_byte / 2, 2, mem.size() - from_byte / 2, f);
    }
    bool load(FILE *f, uint32_t from_byte) {
        uint8_t flags = 0;
        bool ok = fread(&dq, sizeof dq, 1, f) == 1 && fread(row, sizeof row, 1, f) == 1 &&
            fread(&burst_base, sizeof burst_base, 1, f) == 1 && fread(&burst_col, sizeof burst_col, 1, f) == 1 &&
            fread(&cas, 1, 1, f) == 1 && fread(&read_wait, 1, 1, f) == 1 && fread(&flags, 1, 1, f) == 1;
        reading = flags & 1; writing = flags & 2; cke_prev = flags & 4;
        const size_t n = mem.size() - from_byte / 2;
        return ok && fread(mem.data() + from_byte / 2, 2, n, f) == n;
    }
};

// 512 KiB asynchronous SRAM, 16-bit data with byte write masks; CE is tied low.
// Called after every evaluation: the chip has no clock.
struct SramChip {
    std::vector<uint16_t> mem = std::vector<uint16_t>(256u * 1024, 0);
    uint64_t writes = 0;
    bool we_prev = true;
    uint16_t pins(bool weN, bool oeN, unsigned maskN, unsigned address, uint16_t data, bool drive) {
        uint16_t &w = mem[address & 0x3ffff];
        if (!weN && drive) {
            if (!(maskN & 1)) w = (w & 0xff00) | (data & 0x00ff);
            if (!(maskN & 2)) w = (w & 0x00ff) | (data & 0xff00);
            if (we_prev) writes++;
        }
        we_prev = weN;
        return w;
    }
    void save(FILE *f) const { fwrite(mem.data(), 2, mem.size(), f); }
    bool load(FILE *f) { return fread(mem.data(), 2, mem.size(), f) == mem.size(); }
};
