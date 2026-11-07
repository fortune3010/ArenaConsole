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
	private static final List<String> addrs = new ArrayList<>();
	private static final List<TextView> rows = new ArrayList<>();

	private static final Runnable poll = new Runnable() {
		@Override
		public void run() {
			if (panel == null || panel.getVisibility() != View.VISIBLE || sel == null) return;
			send("get " + sel + " " + width);
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
		if (ArenaSession.current != null) return;
		if (root != null && root.getContext() == a && root.getParent() != null) return;
		actRef = new WeakReference<>(a);
		sel = null;
		addrs.clear();
		rows.clear();
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
		panel.addView(list);
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

	private static void addRow(final PpssppActivity a, final String addr, long v) {
		TextView t = tv(a, addr + " = " + v);
		t.setOnClickListener(x -> select(addr));
		addrs.add(addr);
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
				status.setText(c + ": " + o.optInt("count") + " candidatos");
				list.removeAllViews();
				addrs.clear();
				rows.clear();
				JSONArray it = o.optJSONArray("items");
				if (it != null) {
					for (int i = 0; i < it.length(); i++) {
						JSONObject e = it.getJSONObject(i);
						addRow(a, e.getString("a"), e.optLong("v"));
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
