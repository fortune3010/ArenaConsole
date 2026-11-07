// Copyright (c) 2026- GameArena. Part of Arena Console, a fork of PPSSPP (https://github.com/hrydgard/ppsspp).
// This program is free software: you can redistribute it and/or modify it under the terms of the
// GNU General Public License as published by the Free Software Foundation, version 2.0 or later versions.
// GA20_LAB: ficheiro novo. Laboratório de mapeamento da RAM (só leitura). Só aparece fora das partidas da Arena.
package org.ppsspp.ppsspp;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ArenaLab {
	private static final Handler main = new Handler(Looper.getMainLooper());
	private static final String[] KINDS = { "vida_p1", "vida_p2", "tempo", "fim" };
	private static final String[] LABELS = { "Vida P1", "Vida P2", "Tempo", "Fim de jogo" };

	private static WeakReference<PpssppActivity> actRef = new WeakReference<>(null);
	private static FrameLayout root;
	private static LinearLayout panel, list;
	private static TextView status, watch;
	private static int width = 2;
	private static String sel = null;
	private static int selW = 2;
	private static final List<Integer> rowW = new ArrayList<>();
	private static final List<String> addrs = new ArrayList<>();
	private static final List<TextView> rows = new ArrayList<>();

	private static final Runnable poll = new Runnable() {
		@Override
		public void run() {
			if (panel == null || panel.getVisibility() != View.VISIBLE || sel == null) return;
			send("get " + sel + " " + selW);
			main.postDelayed(this, 800);
		}
	};

	private ArenaLab() {}

	private static void send(String cmd) {
		NativeApp.sendMessageFromJava("arena_scan", cmd);
	}

	private static TextView tv(Context c, String t) {
		TextView v = new TextView(c);
		v.setText(t);
		v.setTextColor(Color.WHITE);
		v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
		v.setPadding(6, 3, 6, 3);
		return v;
	}

	private static Button btn(Context c, String t, View.OnClickListener l) {
		Button b = new Button(c);
		b.setText(t);
		b.setAllCaps(false);
		b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
		b.setTextColor(Color.WHITE);
		b.setBackground(ArenaUi.pill(0xFF1B2B4A, ArenaUi.NEON, 14f));
		b.setPadding(4, 0, 4, 0);
		b.setMinHeight(0);
		b.setMinimumHeight(0);
		b.setMinWidth(0);
		b.setMinimumWidth(0);
		b.setOnClickListener(l);
		return b;
	}

	private static LinearLayout row(Context c, Button... bs) {
		LinearLayout r = new LinearLayout(c);
		r.setOrientation(LinearLayout.HORIZONTAL);
		for (Button b : bs) {
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ArenaUi.dp(r, 34), 1f);
			lp.setMargins(2, 2, 2, 2);
			r.addView(b, lp);
		}
		return r;
	}

	/** Chamado pelo PpssppActivity depois do ArenaUi.attach. Nas partidas da Arena (sessão do Launcher) nunca aparece. */
	public static void attach(final PpssppActivity a) {
		// GA20_LAB: condicao removida para testes de mapeamento
		if (root != null && root.getContext() == a && root.getParent() != null) return;
		actRef = new WeakReference<>(a);
		sel = null;
		addrs.clear();
		rows.clear();
		rowW.clear();
		root = new FrameLayout(a);

		panel = new LinearLayout(a);
		panel.setOrientation(LinearLayout.VERTICAL);
		panel.setBackgroundColor(0xE6010E1F);
		panel.setPadding(8, 8, 8, 8);
		status = tv(a, "LAB: abre um combate e toca em Novo 2B");
		watch = tv(a, "");
		list = new LinearLayout(a);
		list.setOrientation(LinearLayout.VERTICAL);
		panel.addView(status);
		panel.addView(watch);
		android.widget.ScrollView sv = new android.widget.ScrollView(a);
		sv.addView(list);
		panel.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ArenaUi.dp(root, 170)));
		panel.addView(row(a,
			btn(a, "Novo 2B", v -> novo(2)),
			btn(a, "Novo 4B", v -> novo(4)),
			btn(a, "Novo 1B", v -> novo(1))));
		panel.addView(row(a,
			btn(a, "Diminuiu", v -> send("dec")),
			btn(a, "Aumentou", v -> send("inc")),
			btn(a, "Igual", v -> send("same")),
			btn(a, "Mudou", v -> send("diff"))));
		panel.addView(row(a,
			btn(a, "Auto-Scan (Luta)", v -> askAuto(a)),
			btn(a, "Parar", v -> send("stop")),
			btn(a, "Ver vidas", v -> toggleVidas(a))));
		panel.addView(row(a,
			btn(a, "Valor…", v -> askValue(a)),
			btn(a, "Guardar", v -> save(a)),
			btn(a, "Exportar", v -> send("export")),
			btn(a, "Sair", v -> hide())));
		panel.setVisibility(View.GONE);

		FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(ArenaUi.dp(root, 330), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
		pl.topMargin = ArenaUi.dp(root, 48);
		pl.leftMargin = ArenaUi.dp(root, 8);
		root.addView(panel, pl);

		Button fab = btn(a, "LAB", v -> show());
		FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(ArenaUi.dp(root, 52), ArenaUi.dp(root, 32), Gravity.TOP | Gravity.START);
		fl.topMargin = ArenaUi.dp(root, 8);
		fl.leftMargin = ArenaUi.dp(root, 8);
		root.addView(fab, fl);

		a.addContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
	}

	private static void show() {
		if (panel == null) return;
		panel.setVisibility(View.VISIBLE);
		main.removeCallbacks(poll);
		if (sel != null) main.post(poll);
	}

	private static void hide() {
		if (panel != null) panel.setVisibility(View.GONE);
		main.removeCallbacks(poll);
	}

	private static void novo(int w) {
		width = w;
		sel = null;
		watch.setText("");
		send("new " + w);
	}

	private static void select(String addr) {
		sel = addr;
		int ix = addrs.indexOf(addr);
		selW = ix >= 0 && ix < rowW.size() ? rowW.get(ix) : width;
		for (int i = 0; i < rows.size(); i++) {
			rows.get(i).setTextColor(addrs.get(i).equals(addr) ? ArenaUi.NEON : Color.WHITE);
		}
		watch.setText("Selecionado: " + addr);
		main.removeCallbacks(poll);
		main.post(poll);
	}

	private static AlertDialog.Builder dlg(Context c) {
		return new AlertDialog.Builder(c, android.R.style.Theme_Material_Dialog_Alert);
	}

	private static void askValue(final PpssppActivity a) {
		final EditText e = new EditText(a);
		e.setInputType(InputType.TYPE_CLASS_NUMBER);
		e.setTextColor(Color.WHITE);
		dlg(a).setTitle("Valor exato").setView(e)
			.setPositiveButton("OK", (d, w) -> {
				String s = e.getText().toString().trim();
				if (!s.isEmpty()) send("eq " + s);
			})
			.setNegativeButton("Cancelar", null)
			.show();
	}

	private static void askAuto(final PpssppActivity a) {
		final int[] secs = { 30, 60, 120 };
		dlg(a).setTitle("Auto-Scan: quanto tempo?")
			.setItems(new String[] { "30 s", "60 s", "120 s" }, (d, i) -> {
				sel = null;
				list.removeAllViews();
				addrs.clear();
				rowW.clear();
				rows.clear();
				watch.setText("");
				status.setText("A iniciar… luta normalmente");
				send("auto " + secs[i]);
			})
			.show();
	}

	private static boolean vidasMode = false;
	private static String vA1, vA2;
	private static int vW1 = 2, vW2 = 2;
	private static final Runnable vidasPoll = new Runnable() {
		@Override
		public void run() {
			if (!vidasMode || panel == null || panel.getVisibility() != View.VISIBLE) {
				vidasMode = false;
				return;
			}
			send("peek " + vA1 + " " + vW1 + " " + vA2 + " " + vW2);
			main.postDelayed(this, 500);
		}
	};

	private static void toggleVidas(PpssppActivity a) {
		if (vidasMode) {
			vidasMode = false;
			main.removeCallbacks(vidasPoll);
			watch.setText("");
			return;
		}
		try {
			File best = null;
			File[] fs = new File(a.getFilesDir(), "arena").listFiles();
			if (fs != null) {
				for (File f : fs) {
					if (f.getName().startsWith("lab-") && f.getName().endsWith(".json") && (best == null || f.lastModified() > best.lastModified())) best = f;
				}
			}
			if (best == null) {
				Toast.makeText(a, "Faz Exportar primeiro (com vida_p1 e vida_p2 guardados)", Toast.LENGTH_LONG).show();
				return;
			}
			byte[] raw = new byte[(int) best.length()];
			try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.FileInputStream(best))) {
				in.readFully(raw);
			}
			JSONArray tags = new JSONObject(new String(raw, StandardCharsets.UTF_8)).optJSONArray("tags");
			vA1 = null; vA2 = null;
			for (int i = 0; tags != null && i < tags.length(); i++) {
				JSONObject t = tags.getJSONObject(i);
				if ("vida_p1".equals(t.optString("k"))) { vA1 = t.optString("a"); vW1 = t.optInt("w", 2); }
				if ("vida_p2".equals(t.optString("k"))) { vA2 = t.optString("a"); vW2 = t.optInt("w", 2); }
			}
			if (vA1 == null || vA2 == null) {
				Toast.makeText(a, "O export não tem vida_p1 e vida_p2", Toast.LENGTH_LONG).show();
				return;
			}
			vidasMode = true;
			status.setText("Vidas ao vivo (" + best.getName() + "): leva e dá golpes");
			main.removeCallbacks(vidasPoll);
			main.post(vidasPoll);
		} catch (Exception e) {
			status.setText("Ver vidas: " + e.getMessage());
		}
	}

	private static void save(final PpssppActivity a) {
		if (sel == null) {
			Toast.makeText(a, "Toca primeiro num candidato da lista", Toast.LENGTH_SHORT).show();
			return;
		}
		final String addr = sel;
		final int w = width;
		dlg(a).setTitle("Este valor é:")
			.setItems(LABELS, (d, i) -> send("tag " + KINDS[i] + " " + addr + " " + w))
			.show();
	}

	private static void export(PpssppActivity a, JSONObject o) {
		String s = o.toString();
		try {
			ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
			cm.setPrimaryClip(ClipData.newPlainText("arena-lab", s));
		} catch (Exception e) { /* sem área de transferência */ }
		try {
			File dir = new File(a.getFilesDir(), "arena");
			if (dir.isDirectory() || dir.mkdirs()) {
				try (FileOutputStream os = new FileOutputStream(new File(dir, "lab-" + o.optString("disc", "x") + ".json"))) {
					os.write(s.getBytes(StandardCharsets.UTF_8));
				}
			}
		} catch (Exception e) { /* guardar é opcional */ }
		status.setText("Exportado e copiado: cola no chat");
		Toast.makeText(a, "JSON copiado", Toast.LENGTH_SHORT).show();
	}

	private static void addRow(final PpssppActivity a, final String addr, long v, int w, String extra) {
		TextView t = tv(a, addr + " w" + w + " = " + v + (extra.isEmpty() ? "" : "  " + extra));
		t.setOnClickListener(x -> select(addr));
		addrs.add(addr);
		rowW.add(w);
		rows.add(t);
		list.addView(t);
	}

	/** Resposta do C++ (PushCommand "arena_scan_result"), pode chegar noutra thread. */
	public static void onResult(final String json) {
		main.post(() -> {
			PpssppActivity a = actRef.get();
			if (a == null || status == null) return;
			try {
				JSONObject o = new JSONObject(json);
				String c = o.optString("cmd");
				if (!o.optBoolean("ok")) {
					status.setText("Erro: " + o.optString("err"));
					return;
				}
				if ("peek".equals(c)) {
					watch.setText("P1 = " + o.optLong("p1") + "     P2 = " + o.optLong("p2"));
					return;
				}
				if ("autop".equals(c)) {
					status.setText("A gravar " + o.optInt("t") + "/" + o.optInt("total") + " s: luta normalmente (leva golpes em segundos diferentes)");
					return;
				}
				if ("stop".equals(c)) {
					status.setText("A parar…");
					return;
				}
				if ("get".equals(c)) {
					watch.setText(o.optString("a") + " = " + o.optLong("v"));
					return;
				}
				if ("export".equals(c)) {
					export(a, o);
					return;
				}
				if ("tag".equals(c)) {
					status.setText("Guardado: " + o.optString("k") + " = " + o.optString("a"));
					return;
				}
				status.setText(c + ": " + o.optInt("count") + " candidatos" + ("auto".equals(c) ? " (d=descidas, i=iguais, T=relógio). Toca num para ver ao vivo." : ""));
				list.removeAllViews();
				addrs.clear();
				rows.clear();
		rowW.clear();
				JSONArray it = o.optJSONArray("items");
				if (it != null) {
					for (int i = 0; i < it.length(); i++) {
						JSONObject e = it.getJSONObject(i);
						addRow(a, e.getString("a"), e.optLong("v"), e.optInt("w", width), e.optString("x", ""));
					}
				}
				if (addrs.size() == 1) select(addrs.get(0));
				else if (sel != null && !addrs.contains(sel)) sel = null;
			} catch (Exception e) {
				status.setText("Resposta inválida: " + e.getMessage());
			}
		});
	}
}
