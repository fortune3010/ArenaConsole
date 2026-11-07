// Copyright (c) 2026- GameArena. Part of Arena Console, a fork of PPSSPP (https://github.com/hrydgard/ppsspp).
// This program is free software: you can redistribute it and/or modify it under the terms of the
// GNU General Public License as published by the Free Software Foundation, version 2.0 or later versions.
// GA20_ARENA (Fase 20.2.2): ficheiro novo.

package org.ppsspp.ppsspp;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;

/**
 * Interface própria do Arena Console por cima do jogo (sem menus do PPSSPP):
 *  - ecrã de carregamento com a marca, até o C++ avisar "arena_boot_done" (ou 30 s);
 *  - Voltar / pausa → "Terminar a partida?" (Ganhei / Perdi-Desistir / Continuar);
 *  - fim da partida vindo do servidor → resultado + "Voltar à Game Arena".
 */
public final class ArenaUi {
	static final int BG = 0xFF010E1F;
	static final int NEON = 0xFF3DFFA8;
	static final int TEXT2 = 0xFFA8B9E8;
	private static final long LOADING_MAX_MS = 30000;

	private static WeakReference<View> loading = new WeakReference<>(null);
	private static WeakReference<TextView> loadingStatus = new WeakReference<>(null);
	private static WeakReference<AlertDialog> dialog = new WeakReference<>(null);
	private static boolean exiting = false;
	/** A partida cujo jogo já arrancou (para não voltar a mostrar o carregamento quando a Activity é recriada). */
	private static ArenaSession bootedMatch = null;
	private static final Handler main = new Handler(Looper.getMainLooper());

	private ArenaUi() {}

	static int dp(View v, float d) {
		return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, d, v.getResources().getDisplayMetrics());
	}

	/** Chamado pelo PpssppActivity depois do setContentView: ecrã de carregamento + avisos da ponte de rede. */
	public static void attach(final PpssppActivity act) {
		final ArenaSession s = ArenaSession.current;
		if (s == null) return;
		exiting = false;
		ArenaNet.setListener(new ArenaNet.Listener() {
			@Override
			public void onOver(String winnerRole, String reason) {
				showOver(act, winnerRole, reason);
			}

			@Override
			public void onStatus(String status) {
				int res = statusText(status);
				if (res == 0) return;
				TextView t = loadingStatus.get();
				if (t != null && loading.get() != null) {
					t.setText(res);
				} else if (ArenaNet.ST_PEER_LOST.equals(status) || ArenaNet.ST_PEER_ONLINE.equals(status) || ArenaNet.ST_CONN_LOST.equals(status) || ArenaNet.ST_REFUSED.equals(status)) {
					Toast.makeText(act, res, Toast.LENGTH_SHORT).show();
				}
			}
		});
		if (ArenaNet.isOver()) return;  // o listener acima já mostra o resultado
		if (ArenaConfig.sameMatch(bootedMatch, s)) return;  // o jogo já está a correr

		FrameLayout root = new FrameLayout(act);
		root.setBackgroundColor(BG);
		root.setClickable(true);  // não deixa toques chegarem ao jogo por baixo
		LinearLayout col = new LinearLayout(act);
		col.setOrientation(LinearLayout.VERTICAL);
		col.setGravity(Gravity.CENTER_HORIZONTAL);

		TextView title = new TextView(act);
		title.setText(R.string.arena_gate_title);
		title.setTextColor(Color.WHITE);
		title.setTypeface(Typeface.DEFAULT_BOLD);
		title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
		title.setLetterSpacing(0.08f);
		col.addView(title);

		TextView game = new TextView(act);
		game.setText(s.isoName.isEmpty() ? act.getString(R.string.arena_loading) : s.isoName);
		game.setTextColor(NEON);
		game.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
		game.setGravity(Gravity.CENTER);
		game.setPadding(0, dp(root, 10), 0, dp(root, 18));
		col.addView(game);

		ProgressBar pb = new ProgressBar(act);
		pb.setIndeterminate(true);
		col.addView(pb, new LinearLayout.LayoutParams(dp(root, 44), dp(root, 44)));

		TextView st = new TextView(act);
		st.setText(R.string.arena_loading);
		st.setTextColor(TEXT2);
		st.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
		st.setGravity(Gravity.CENTER);
		st.setPadding(0, dp(root, 14), 0, 0);
		col.addView(st);

		FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
		root.addView(col, cl);

		TextView lic = new TextView(act);
		lic.setText(R.string.arena_license);
		lic.setTextColor(0xFF6C7EA8);
		lic.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
		lic.setGravity(Gravity.CENTER);
		FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
		ll.bottomMargin = dp(root, 18);
		root.addView(lic, ll);

		act.addContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		loading = new WeakReference<>(root);
		loadingStatus = new WeakReference<>(st);
		main.postDelayed(() -> hideLoading(act), LOADING_MAX_MS);
	}

	static int statusText(String st) {
		switch (st) {
			case ArenaNet.ST_JOINING: return R.string.arena_net_joining;
			case ArenaNet.ST_WAITING_PEER: return R.string.arena_net_waiting_peer;
			case ArenaNet.ST_PEER_ONLINE: return R.string.arena_net_peer_online;
			case ArenaNet.ST_PEER_LOST: return R.string.arena_net_peer_lost;
			case ArenaNet.ST_REFUSED: return R.string.arena_net_refused;
			case ArenaNet.ST_CONN_LOST: return R.string.arena_net_conn_lost;
			case ArenaNet.ST_CONN_ERROR: return R.string.arena_net_conn_error;
			case ArenaNet.ST_LOCAL_ERROR: return R.string.arena_net_local_error;
			default: return 0;
		}
	}

	/** O C++ avisou que o jogo arrancou (SystemNotification::BOOT_DONE). */
	public static void hideLoading(PpssppActivity act) {
		if (ArenaSession.current != null) bootedMatch = ArenaSession.current;
		ArenaWatchJava.onBoot();
		final View v = loading.get();
		if (v == null) return;
		loading = new WeakReference<>(null);
		loadingStatus = new WeakReference<>(null);
		v.animate().alpha(0f).setDuration(350).withEndAction(() -> {
			ViewGroup p = (ViewGroup) v.getParent();
			if (p != null) p.removeView(v);
		}).start();
	}

	private static AlertDialog.Builder builder(PpssppActivity act) {
		return new AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert);
	}

	private static void show(AlertDialog d) {
		AlertDialog old = dialog.get();
		if (old != null && old.isShowing()) {
			try { old.dismiss(); } catch (Exception e) { /* já fechado */ }
		}
		dialog = new WeakReference<>(d);
		d.show();
	}

	/** Voltar do Android, botão de pausa do ecrã ou do comando. */
	public static void showExitDialog(final PpssppActivity act) {
		if (exiting || act.isFinishing()) return;
		if (ArenaNet.isOver()) {
			quit(act);
			return;
		}
		AlertDialog d = builder(act)
			.setTitle(R.string.arena_exit_title)
			.setMessage(R.string.arena_exit_msg)
			.setPositiveButton(R.string.arena_exit_won, (x, w) -> {
				ArenaNet.report(ArenaNet.myRole());
				showWaiting(act);
			})
			.setNegativeButton(R.string.arena_exit_lost, (x, w) -> confirmLoss(act))
			.setNeutralButton(R.string.arena_exit_continue, null)
			.create();
		show(d);
	}

	private static void confirmLoss(final PpssppActivity act) {
		AlertDialog d = builder(act)
			.setTitle(R.string.arena_lost_confirm_title)
			.setMessage(R.string.arena_lost_confirm_msg)
			.setPositiveButton(R.string.arena_lost_confirm_yes, (x, w) -> {
				exiting = true;
				ArenaNet.report(ArenaNet.otherRole());
				// Sai quando o servidor confirmar (console_over) ou ao fim de 3 s.
				main.postDelayed(() -> quit(act), 3000);
			})
			.setNegativeButton(R.string.arena_exit_continue, null)
			.create();
		show(d);
	}

	private static void showWaiting(final PpssppActivity act) {
		AlertDialog d = builder(act)
			.setTitle(R.string.arena_wait_title)
			.setMessage(R.string.arena_wait_msg)
			.setPositiveButton(R.string.arena_wait_back, null)
			.setNegativeButton(R.string.arena_wait_leave, (x, w) -> quit(act))
			.create();
		show(d);
	}

	static void showOver(final PpssppActivity act, String winnerRole, String reason) {
		if (act.isFinishing()) return;
		String me = ArenaNet.myRole();
		int title = "draw".equals(winnerRole) ? R.string.arena_over_draw : winnerRole.equals(me) ? R.string.arena_over_won : R.string.arena_over_lost;
		int msg = "dnf".equals(reason) ? R.string.arena_over_reason_dnf : "forfeit".equals(reason) ? R.string.arena_over_reason_forfeit : R.string.arena_over_reason_agreed;
		if (exiting) {
			quit(act);  // quem desistiu já pediu para sair
			return;
		}
		AlertDialog d = builder(act)
			.setTitle(title)
			.setMessage(msg)
			.setCancelable(false)
			.setPositiveButton(R.string.arena_over_back, (x, w) -> quit(act))
			.create();
		show(d);
	}

	/** Fecha o Arena Console (o PPSSPP desliga-se como no comando "finish") e volta à Game Arena. */
	static void quit(PpssppActivity act) {
		if (act.isFinishing() || act.isDestroyed()) return;
		exiting = true;
		AlertDialog d = dialog.get();
		if (d != null && d.isShowing()) {
			try { d.dismiss(); } catch (Exception e) { /* já fechado */ }
		}
		ArenaNet.setListener(null);
		act.processCommand("finish", "");
	}

	/** Um Intent novo com o jogo já a correr: só a mesma partida é aceite (não muda nada). */
	public static void onNewIntentWhilePlaying(PpssppActivity act, Intent intent) {
		ArenaSession s = ArenaSession.fromIntent(intent);
		if (s != null && !ArenaConfig.sameMatch(ArenaSession.current, s)) {
			Toast.makeText(act, R.string.arena_busy, Toast.LENGTH_LONG).show();
		}
	}

	static GradientDrawable pill(int color, int strokeColor, float radius) {
		GradientDrawable g = new GradientDrawable();
		g.setColor(color);
		g.setCornerRadius(radius);
		if (strokeColor != 0) g.setStroke(2, strokeColor);
		return g;
	}
}
