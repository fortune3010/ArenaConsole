// Copyright (c) 2026- GameArena. Part of Arena Console, a fork of PPSSPP (https://github.com/hrydgard/ppsspp).
// GPL 2.0 or later. GA20_WATCH: nunca escreve na memória do jogo.
//
// Lê a vida do P1 e do P2. Arma quando as duas estão > 0 (início de round). KO = uma delas fica a 0 durante
// HOLD amostras seguidas. Vida do P1 a 0 => ganha o P2 (e vice-versa). KO duplo é só registado.

#include <atomic>
#include <cctype>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <sstream>
#include <string>
#include <thread>
#if defined(__ANDROID__)
#include <sys/resource.h>
#endif

#include "Core/ArenaWatch.h"
#include "Core/MemMap.h"
#include "Core/System.h"
#include "Core/ELF/ParamSFO.h"

extern ParamSFOData g_paramSFO;

namespace ArenaWatch {

static std::atomic<bool> g_running(false);
static std::atomic<bool> g_stop(false);

struct Cfg {
	uint32_t a1 = 0, a2 = 0;
	int w1 = 0, w2 = 0, hold = 0, rounds = 0, ms = 0;
	std::string disc;
};

static std::string DiscId() {
	std::string clean;
	for (char ch : g_paramSFO.GetDiscID()) {
		if (isalnum((unsigned char)ch) || ch == '_' || ch == '-')
			clean += ch;
	}
	return clean;
}

static std::string Err(const char *cmd, const char *msg) {
	return std::string("{\"ok\":false,\"cmd\":\"") + cmd + "\",\"err\":\"" + msg + "\"}";
}

static bool Read(uint32_t addr, int w, uint32_t &v) {
	if (!PSP_IsInited() || !Memory::IsValidRange(addr, w))
		return false;
	v = 0;
	memcpy(&v, Memory::GetPointerUnchecked(addr), w);  // little-endian
	return true;
}

static std::string Ev(const char *cmd, const Cfg &c, const char *slot, uint32_t v1, uint32_t v2, int p1w, int p2w, bool fin) {
	char b[384];
	snprintf(b, sizeof(b), "{\"ok\":true,\"cmd\":\"%s\",\"disc\":\"%s\",\"slotWinner\":\"%s\",\"p1\":%u,\"p2\":%u,\"p1Wins\":%d,\"p2Wins\":%d,\"final\":%s}",
		cmd, c.disc.c_str(), slot, v1, v2, p1w, p2w, fin ? "true" : "false");
	return b;
}

static void Run(Cfg c, Notify notify) {
#if defined(__ANDROID__)
	setpriority(PRIO_PROCESS, 0, 10);  // baixa prioridade: não rouba tempo ao jogo
#endif
	bool armed = false;
	int lastKo = 0, streak = 0, p1Wins = 0, p2Wins = 0;
	while (!g_stop) {
		std::this_thread::sleep_for(std::chrono::milliseconds(c.ms));
		uint32_t v1 = 0, v2 = 0;
		if (!Read(c.a1, c.w1, v1) || !Read(c.a2, c.w2, v2))
			continue;
		if (!armed) {
			if (v1 > 0 && v2 > 0) {
				armed = true;
				lastKo = 0;
				streak = 0;
			}
			continue;
		}
		const int ko = (v1 == 0 ? 1 : 0) | (v2 == 0 ? 2 : 0);
		if (ko == 0) {
			lastKo = 0;
			streak = 0;
			continue;
		}
		if (ko == lastKo)
			streak++;
		else {
			lastKo = ko;
			streak = 1;
		}
		if (streak < c.hold)
			continue;
		armed = false;  // KO confirmado: espera o próximo round (as duas vidas > 0)
		lastKo = 0;
		streak = 0;
		if (ko == 3) {
			if (notify)
				notify(Ev("ko", c, "draw", v1, v2, p1Wins, p2Wins, false));
			continue;
		}
		if (ko == 1)
			p2Wins++;
		else
			p1Wins++;
		const bool fin = p1Wins >= c.rounds || p2Wins >= c.rounds;
		if (notify)
			notify(Ev(fin ? "final" : "ko", c, ko == 1 ? "p2" : "p1", v1, v2, p1Wins, p2Wins, fin));
		if (fin)
			break;
	}
	g_running = false;
}

std::string Command(const std::string &line, const Notify &notify) {
	std::istringstream in(line);
	std::string c;
	in >> c;
	if (c == "disc")
		return "{\"ok\":true,\"cmd\":\"disc\",\"disc\":\"" + DiscId() + "\"}";
	if (c == "stopw") {
		g_stop = true;
		return "{\"ok\":true,\"cmd\":\"stopw\"}";
	}
	if (c == "start") {
		Cfg cfg;
		std::string s1, s2;
		in >> s1 >> cfg.w1 >> s2 >> cfg.w2 >> cfg.hold >> cfg.rounds >> cfg.ms;
		cfg.a1 = (uint32_t)strtoul(s1.c_str(), nullptr, 0);
		cfg.a2 = (uint32_t)strtoul(s2.c_str(), nullptr, 0);
		const bool wOk = (cfg.w1 == 1 || cfg.w1 == 2 || cfg.w1 == 4) && (cfg.w2 == 1 || cfg.w2 == 2 || cfg.w2 == 4);
		if (!cfg.a1 || !cfg.a2 || !wOk || cfg.hold < 1 || cfg.hold > 20 || cfg.rounds < 1 || cfg.rounds > 9 || cfg.ms < 100 || cfg.ms > 2000)
			return Err("start", "parametros invalidos");
		if (!notify)
			return Err("start", "sem canal de resposta");
		cfg.disc = DiscId();
		bool expected = false;
		if (!g_running.compare_exchange_strong(expected, true))
			return "{\"ok\":true,\"cmd\":\"started\",\"already\":true}";
		g_stop = false;
		std::thread(Run, cfg, notify).detach();
		return "{\"ok\":true,\"cmd\":\"started\"}";
	}
	return Err("x", "comando desconhecido");
}

}  // namespace ArenaWatch
