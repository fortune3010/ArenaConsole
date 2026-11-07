// Copyright (c) 2026- GameArena. Part of Arena Console, a fork of PPSSPP (https://github.com/hrydgard/ppsspp).
// GPL 2.0 or later. GA20_LAB: nunca escreve na memória do jogo.

#include <cctype>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>
#if defined(__ANDROID__)
#include <sys/resource.h>
#endif

#include "Core/ArenaScan.h"
#include "Core/MemMap.h"
#include "Core/System.h"
#include "Core/ELF/ParamSFO.h"

extern ParamSFOData g_paramSFO;

namespace ArenaScan {

static const uint32_t kBase = 0x08800000;  // início da RAM de utilizador do PSP
static const uint32_t kSize = 0x01800000;  // 24 MB

struct Tag { std::string k; uint32_t a; int w; };

static std::mutex g_mutex;
static int g_w = 2;
static size_t g_count = 0;
static std::vector<uint8_t> g_prev;
static std::vector<uint8_t> g_cand;
static std::vector<Tag> g_tags;

static std::string Hex(uint32_t a) {
	char b[16];
	snprintf(b, sizeof(b), "0x%08x", a);
	return b;
}

static std::string Err(const std::string &cmd, const char *msg) {
	return "{\"ok\":false,\"cmd\":\"" + cmd + "\",\"err\":\"" + msg + "\"}";
}

static bool ReadRam(std::vector<uint8_t> &out) {
	if (!PSP_IsInited() || !Memory::IsValidRange(kBase, kSize))
		return false;
	const uint8_t *p = Memory::GetPointerUnchecked(kBase);
	if (!p)
		return false;
	out.assign(p, p + kSize);
	return true;
}

static inline uint32_t ValAt(const std::vector<uint8_t> &b, size_t off, int w) {
	uint32_t v = 0;
	memcpy(&v, b.data() + off, w);  // little-endian, como o PSP
	return v;
}

static std::string List(const std::string &cmd, int max) {
	std::string s = "{\"ok\":true,\"cmd\":\"" + cmd + "\",\"w\":" + std::to_string(g_w) + ",\"count\":" + std::to_string(g_count) + ",\"items\":[";
	if (g_count <= 2000) {  // só lista quando já há poucos candidatos
		int c = 0;
		for (size_t i = 0; i < g_cand.size() && c < max; i++) {
			if (!g_cand[i])
				continue;
			if (c)
				s += ",";
			s += "{\"a\":\"" + Hex(kBase + (uint32_t)(i * g_w)) + "\",\"v\":" + std::to_string(ValAt(g_prev, i * g_w, g_w)) + "}";
			c++;
		}
	}
	return s + "]}";
}

// ───────── Gravador automático: "auto <segundos> [largura]" ─────────
// Em vez de guardar 24 MB por segundo, mantém só 2 contadores por endereço.
static std::atomic<bool> g_autoRunning(false);
static std::atomic<bool> g_autoStop(false);

struct Rec {
	int w = 2;
	std::vector<uint8_t> dec;   // nº de descidas; 255 = já subiu alguma vez (eliminado)
	std::vector<uint8_t> same;  // nº de amostras iguais
};

struct Hit { uint32_t a; int w; uint32_t v; int d; int s; };

static void Step(Rec &r, const std::vector<uint8_t> &prev, const std::vector<uint8_t> &cur) {
	const size_t n = r.dec.size();
	const int w = r.w;
	for (size_t i = 0; i < n; i++) {
		uint8_t &d = r.dec[i];
		if (d == 255)
			continue;
		const size_t o = i * w;
		const uint32_t a = ValAt(prev, o, w), b = ValAt(cur, o, w);
		if (b > a)
			d = 255;
		else if (b < a) {
			if (d < 254)
				d++;
		} else if (r.same[i] < 255)
			r.same[i]++;
	}
}

static std::string HitJson(const Hit &h, bool timer) {
	char b[160];
	snprintf(b, sizeof(b), "{\"a\":\"%s\",\"w\":%d,\"v\":%u,\"x\":\"%s%d i%d\"}", Hex(h.a).c_str(), h.w, h.v, timer ? "T d" : "d", h.d, h.s);
	return b;
}

static void AutoRun(int seconds, int wsel, Notify notify) {
#if defined(__ANDROID__)
	setpriority(PRIO_PROCESS, 0, 10);  // baixa prioridade: não rouba tempo ao jogo
#endif
	auto emit = [&](const std::string &s) {
		if (notify)
			notify(s);
	};
	std::vector<Rec> recs;
	const int widths[3] = { 2, 4, 1 };
	for (int w : widths) {
		if (wsel == 0 && w == 1)
			continue;  // por defeito: 2 e 4 bytes
		if (wsel != 0 && wsel != w)
			continue;
		recs.emplace_back();
		recs.back().w = w;
		recs.back().dec.assign(kSize / w, 0);
		recs.back().same.assign(kSize / w, 0);
	}
	std::vector<uint8_t> prev, cur;
	if (!ReadRam(prev)) {
		g_autoRunning = false;
		emit(Err("auto", "jogo nao esta a correr"));
		return;
	}
	int samples = 0;
	auto next = std::chrono::steady_clock::now();
	for (int t = 1; t <= seconds && !g_autoStop; t++) {
		next += std::chrono::seconds(1);
		while (!g_autoStop && std::chrono::steady_clock::now() < next)
			std::this_thread::sleep_for(std::chrono::milliseconds(50));
		if (g_autoStop)
			break;
		if (!ReadRam(cur)) {
			g_autoRunning = false;
			emit(Err("auto", "jogo parou"));
			return;
		}
		for (Rec &r : recs)
			Step(r, prev, cur);
		prev.swap(cur);
		samples++;
		if (t % 2 == 0)
			emit("{\"ok\":true,\"cmd\":\"autop\",\"t\":" + std::to_string(t) + ",\"total\":" + std::to_string(seconds) + "}");
	}
	if (samples < 5) {
		g_autoRunning = false;
		emit(Err("auto", "captura curta demais (minimo 5 s)"));
		return;
	}
	// Suspeitos de vida: desceram >= 3 vezes, nunca subiram, iguais em pelo menos metade das amostras.
	// Suspeitos de relógio: desceram em >= 80% das amostras e nunca subiram.
	std::vector<Hit> life, timers;
	size_t lifeTotal = 0;
	for (Rec &r : recs) {
		for (size_t i = 0; i < r.dec.size(); i++) {
			const int d = r.dec[i];
			if (d == 255 || d < 3)
				continue;
			Hit h;
			h.a = kBase + (uint32_t)(i * r.w);
			h.w = r.w;
			h.v = ValAt(prev, i * r.w, r.w);
			h.d = d;
			h.s = r.same[i];
			if (h.s * 2 >= samples) {
				lifeTotal++;
				if (life.size() < 20000)
					life.push_back(h);
			} else if (d * 10 >= samples * 8 && timers.size() < 2000) {
				timers.push_back(h);
			}
		}
	}
	std::vector<Rec>().swap(recs);
	std::sort(life.begin(), life.end(), [](const Hit &x, const Hit &y) { return x.s != y.s ? x.s > y.s : x.d > y.d; });
	std::sort(timers.begin(), timers.end(), [](const Hit &x, const Hit &y) { return x.d > y.d; });
	std::string s = "{\"ok\":true,\"cmd\":\"auto\",\"samples\":" + std::to_string(samples) + ",\"stopped\":" + (g_autoStop ? "true" : "false") + ",\"count\":" + std::to_string(lifeTotal) + ",\"items\":[";
	int n = 0;
	for (size_t i = 0; i < life.size() && i < 25; i++, n++)
		s += (n ? "," : "") + HitJson(life[i], false);
	for (size_t i = 0; i < timers.size() && i < 5; i++, n++)
		s += (n ? "," : "") + HitJson(timers[i], true);
	s += "]}";
	g_autoRunning = false;
	emit(s);
}

std::string Command(const std::string &line, const Notify &notify) {
	std::lock_guard<std::mutex> lock(g_mutex);
	std::istringstream in(line);
	std::string c;
	in >> c;
	for (char ch : c) {
		if (!isalnum((unsigned char)ch))
			return Err("x", "comando invalido");
	}

	if (c == "new") {
		int w = 2;
		in >> w;
		if (w != 1 && w != 2 && w != 4)
			return Err(c, "largura invalida");
		std::vector<uint8_t> cur;
		if (!ReadRam(cur))
			return Err(c, "jogo nao esta a correr");
		g_w = w;
		g_prev.swap(cur);
		g_cand.assign(kSize / g_w, 1);
		g_count = g_cand.size();
		return List(c, 0);
	}

	if (c == "dec" || c == "inc" || c == "same" || c == "diff" || c == "eq") {
		if (g_cand.empty())
			return Err(c, "faz primeiro Novo");
		uint32_t val = 0;
		if (c == "eq") {
			std::string v;
			in >> v;
			if (v.empty())
				return Err(c, "falta o valor");
			val = (uint32_t)strtoul(v.c_str(), nullptr, 0);
		}
		std::vector<uint8_t> cur;
		if (!ReadRam(cur))
			return Err(c, "jogo nao esta a correr");
		const int kind = c == "dec" ? 0 : c == "inc" ? 1 : c == "same" ? 2 : c == "diff" ? 3 : 4;
		size_t k = 0;
		for (size_t i = 0; i < g_cand.size(); i++) {
			if (!g_cand[i])
				continue;
			const uint32_t a = ValAt(g_prev, i * g_w, g_w), b = ValAt(cur, i * g_w, g_w);
			const bool ok = kind == 0 ? b < a : kind == 1 ? b > a : kind == 2 ? b == a : kind == 3 ? b != a : b == val;
			g_cand[i] = ok ? 1 : 0;
			k += ok ? 1 : 0;
		}
		g_prev.swap(cur);
		g_count = k;
		return List(c, 20);
	}

	if (c == "show") {
		int n = 20;
		in >> n;
		if (g_cand.empty())
			return Err(c, "faz primeiro Novo");
		return List(c, n);
	}

	if (c == "get") {
		std::string a;
		int w = g_w;
		in >> a >> w;
		if (w != 1 && w != 2 && w != 4)
			w = 2;
		const uint32_t addr = (uint32_t)strtoul(a.c_str(), nullptr, 0);
		if (!PSP_IsInited() || !Memory::IsValidRange(addr, w))
			return Err(c, "endereco invalido");
		uint32_t v = 0;
		memcpy(&v, Memory::GetPointerUnchecked(addr), w);
		return "{\"ok\":true,\"cmd\":\"get\",\"a\":\"" + Hex(addr) + "\",\"w\":" + std::to_string(w) + ",\"v\":" + std::to_string(v) + "}";
	}

	if (c == "tag") {
		std::string k, a;
		int w = g_w;
		in >> k >> a >> w;
		static const char *kinds[] = { "vida_p1", "vida_p2", "tempo", "fim" };
		bool good = false;
		for (const char *o : kinds) {
			if (k == o)
				good = true;
		}
		if (!good || a.empty() || (w != 1 && w != 2 && w != 4))
			return Err(c, "tag invalida");
		const uint32_t addr = (uint32_t)strtoul(a.c_str(), nullptr, 0);
		if (!PSP_IsInited() || !Memory::IsValidRange(addr, w))
			return Err(c, "endereco invalido");
		bool replaced = false;
		for (Tag &t : g_tags) {
			if (t.k == k) {
				t.a = addr;
				t.w = w;
				replaced = true;
			}
		}
		if (!replaced)
			g_tags.push_back({ k, addr, w });
		return "{\"ok\":true,\"cmd\":\"tag\",\"k\":\"" + k + "\",\"a\":\"" + Hex(addr) + "\"}";
	}

	if (c == "export") {
		std::string clean;
		for (char ch : g_paramSFO.GetDiscID()) {
			if (isalnum((unsigned char)ch) || ch == '_' || ch == '-')
				clean += ch;
		}
		std::string s = "{\"ok\":true,\"cmd\":\"export\",\"disc\":\"" + clean + "\",\"base\":\"" + Hex(kBase) + "\",\"tags\":[";
		for (size_t i = 0; i < g_tags.size(); i++) {
			if (i)
				s += ",";
			s += "{\"k\":\"" + g_tags[i].k + "\",\"a\":\"" + Hex(g_tags[i].a) + "\",\"w\":" + std::to_string(g_tags[i].w) + "}";
		}
		return s + "]}";
	}

	if (c == "auto") {
		int sec = 60, w = 0;
		if (!(in >> sec))
			sec = 60;
		in >> w;  // largura opcional: 0 = 2 e 4 bytes
		if (sec < 10)
			sec = 10;
		if (sec > 300)
			sec = 300;
		if (w != 0 && w != 1 && w != 2 && w != 4)
			w = 0;
		if (!notify)
			return Err(c, "sem canal de resposta");
		bool expected = false;
		if (!g_autoRunning.compare_exchange_strong(expected, true))
			return Err(c, "ja esta a gravar");
		g_autoStop = false;
		std::thread(AutoRun, sec, w, notify).detach();
		return "{\"ok\":true,\"cmd\":\"autop\",\"t\":0,\"total\":" + std::to_string(sec) + "}";
	}

	if (c == "stop") {
		g_autoStop = true;
		return "{\"ok\":true,\"cmd\":\"stop\"}";
	}

	if (c == "reset") {
		g_prev.clear();
		g_cand.clear();
		g_tags.clear();
		g_count = 0;
		return "{\"ok\":true,\"cmd\":\"reset\"}";
	}

	return Err(c, "comando desconhecido");
}

}  // namespace ArenaScan
