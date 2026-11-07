// Copyright (c) 2026- GameArena. Part of Arena Console, a fork of PPSSPP (https://github.com/hrydgard/ppsspp).
// GPL 2.0 or later. GA20_LAB: nunca escreve na memória do jogo.

#include <cctype>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

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

std::string Command(const std::string &line) {
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
