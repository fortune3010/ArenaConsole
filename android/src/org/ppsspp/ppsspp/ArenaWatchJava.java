// Copyright (c) 2026- GameArena. Part of Arena Console, a fork of PPSSPP (https://github.com/hrydgard/ppsspp).
// This program is free software: you can redistribute it and/or modify it under the terms of the
// GNU General Public License as published by the Free Software Foundation, version 2.0 or later versions.
// GA20_WATCH: ficheiro novo. Liga as regras do servidor (emu_ready) ao leitor C++ e reenvia os resultados (emu_auto).
package org.ppsspp.ppsspp;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class ArenaWatchJava {
	private static final String TAG = "ArenaWatch";
	private static final Handler main = new Handler(Looper.getMainLooper());
	private static ArenaSession cur = null;
	private static JSONObject rules = null;
	private static boolean booted = false, started = false;
	private static int tries = 0;
	private static final List<JSONObject> pending = new ArrayList<>();

	private ArenaWatchJava() {}

	private static void send(String cmd) {
		NativeApp.sendMessageFromJava("arena_watch", cmd);
	}

	/** Partida nova => recomeça do zero. */
	private static void check() {
		ArenaSession s = ArenaSession.current;
		if (cur != null && !ArenaConfig.sameMatch(cur, s)) {
			send("stopw");
			rules = null;
			booted = false;
			started = false;
			tries = 0;
			pending.clear();
		}
		cur = s;
	}

	/** Chamado pelo ArenaNet no emu_ready (thread do socket). */
	public static void setRules(final JSONObject r) {
		main.post(() -> {
			check();
			rules = r;
			flush();
			if (booted && !started) queryDisc();
		});
	}

	/** O jogo arrancou (ArenaUi.hideLoading). */
	public static void onBoot() {
		check();
		booted = true;
		if (rules != null && !started) queryDisc();
	}

	private static void queryDisc() {
		if (tries++ > 10) return;
		send("disc");
	}

	private static void flush() {
		for (int i = pending.size() - 1; i >= 0; i--) {
			if (ArenaNet.sendAuto(pending.get(i))) pending.remove(i);
		}
	}

	private static void start(JSONObject rule) {
		try {
			String a1 = null, a2 = null;
			int w1 = 2, w2 = 2;
			JSONArray tags = rule.optJSONArray("tags");
			for (int i = 0; tags != null && i < tags.length(); i++) {
				JSONObject t = tags.getJSONObject(i);
				if ("vida_p1".equals(t.optString("k"))) { a1 = t.optString("a"); w1 = t.optInt("w", 2); }
				if ("vida_p2".equals(t.optString("k"))) { a2 = t.optString("a"); w2 = t.optInt("w", 2); }
			}
			if (a1 == null || a2 == null) return;
			JSONObject w = rule.optJSONObject("watch");
			int hold = w == null ? 4 : w.optInt("koHoldSamples", 4);
			int rounds = w == null ? 1 : w.optInt("roundsToWin", 1);
			int ms = (w == null ? 30 : w.optInt("sampleFrames", 30)) * 1000 / 60;
			send("start " + a1 + " " + w1 + " " + a2 + " " + w2 + " " + hold + " " + rounds + " " + ms);
			started = true;
		} catch (Exception e) {
			Log.e(TAG, "start: " + e.getMessage());
		}
	}

	/** Resposta do C++ (PushCommand "arena_watch_msg"). */
	public static void onMsg(final String json) {
		main.post(() -> {
			try {
				JSONObject o = new JSONObject(json);
				String c = o.optString("cmd");
				if ("disc".equals(c)) {
					String d = o.optString("disc");
					if (d.isEmpty()) {
						main.postDelayed(ArenaWatchJava::queryDisc, 3000);  // o jogo ainda não carregou o PARAM.SFO
						return;
					}
					JSONObject r = rules == null ? null : rules.optJSONObject(d);
					if (r == null) Log.i(TAG, "sem regra para " + d + ": fica o diálogo manual");
					else if (!started) start(r);
					return;
				}
				if ("ko".equals(c) || "final".equals(c)) {
					Log.i(TAG, json);
					if (!ArenaNet.sendAuto(o) && o.optBoolean("final")) pending.add(o);
					return;
				}
				if (!o.optBoolean("ok")) Log.w(TAG, json);
			} catch (Exception e) {
				Log.e(TAG, "onMsg: " + e.getMessage());
			}
		});
	}
}
