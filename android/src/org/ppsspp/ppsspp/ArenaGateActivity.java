// Copyright (c) 2026- GameArena. Part of Arena Console, a fork of PPSSPP (https://github.com/hrydgard/ppsspp).
// This program is free software: you can redistribute it and/or modify it under the terms of the
// GNU General Public License as published by the Free Software Foundation, version 2.0 or later versions.
// GA20_ARENA (Fase 20.2.2): ficheiro novo.

package org.ppsspp.ppsspp;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * O ícone do Arena Console abre este ecrã (não o emulador): "Abre pela Game Arena".
 * Também aparece quando o PpssppActivity recusa um Intent (com o motivo e, se for o caso, o certificado encontrado).
 * Não carrega o C++.
 */
public class ArenaGateActivity extends Activity {
	public static final String EXTRA_REASON = "arena_gate_reason";
	public static final String EXTRA_CERTS = "arena_gate_certs";

	/** Chamado pelo PpssppActivity quando recusa o Intent. */
	public static void open(Context ctx, ArenaGuard.Result r) {
		Intent i = new Intent(ctx, ArenaGateActivity.class);
		i.putExtra(EXTRA_REASON, r.reason);
		i.putStringArrayListExtra(EXTRA_CERTS, new ArrayList<>(r.launcherCerts));
		i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
		ctx.startActivity(i);
	}

	private int dp(float d) {
		return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, d, getResources().getDisplayMetrics());
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		build();
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		setIntent(intent);
		build();
	}

	private void build() {
		final String launcher = ArenaGuard.launcherPackage(this);
		final Intent launch = getPackageManager().getLaunchIntentForPackage(launcher);
		String reason = getIntent().getStringExtra(EXTRA_REASON);
		List<String> certs = getIntent().getStringArrayListExtra(EXTRA_CERTS);
		if (certs == null || certs.isEmpty()) {
			certs = ArenaGuard.certsOf(getPackageManager(), launcher);
		}

		ScrollView scroll = new ScrollView(this);
		scroll.setBackgroundColor(ArenaUi.BG);
		scroll.setFillViewport(true);
		LinearLayout col = new LinearLayout(this);
		col.setOrientation(LinearLayout.VERTICAL);
		col.setGravity(Gravity.CENTER_HORIZONTAL);
		col.setPadding(dp(28), dp(48), dp(28), dp(28));
		scroll.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		TextView icon = text("🎮", 46, Color.WHITE, false);
		col.addView(icon);
		TextView title = text(getString(R.string.arena_gate_title), 26, Color.WHITE, true);
		title.setLetterSpacing(0.08f);
		title.setPadding(0, dp(8), 0, dp(4));
		col.addView(title);
		TextView head = text(getString(R.string.arena_gate_open_ga), 18, ArenaUi.NEON, true);
		head.setPadding(0, dp(10), 0, dp(10));
		col.addView(head);
		col.addView(text(getString(R.string.arena_gate_body), 14, ArenaUi.TEXT2, false));

		int why = reasonText(reason);
		if (launch == null) why = R.string.arena_gate_no_launcher;
		if (why != 0) {
			TextView w = text(getString(why), 13, 0xFFFF8FAE, false);
			w.setPadding(dp(12), dp(10), dp(12), dp(10));
			w.setBackground(ArenaUi.pill(0x26FF2A6D, 0, dp(12)));
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			lp.topMargin = dp(16);
			col.addView(w, lp);
		}

		if (launch != null) {
			col.addView(button(getString(R.string.arena_gate_btn_open), true, v -> {
				try {
					startActivity(launch);
					finish();
				} catch (Exception e) {
					Toast.makeText(this, R.string.arena_gate_no_launcher, Toast.LENGTH_LONG).show();
				}
			}));
		}
		if (ArenaNet.isActive() && ArenaSession.current != null) {
			col.addView(button(getString(R.string.arena_gate_btn_back), false, v -> {
				// Só traz o jogo para a frente: o PpssppActivity ignora este Intent (não é um START_GAME).
				Intent i = new Intent(this, PpssppActivity.class);
				i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
				startActivity(i);
				finish();
			}));
		}
		col.addView(button(getString(R.string.arena_gate_btn_close), false, v -> finish()));

		// Diagnóstico: o certificado da Game Arena instalada (para configurar arena_config.xml).
		if (ArenaGuard.R_BAD_CERT.equals(reason) && !certs.isEmpty()) {
			final String all = TextUtils.join("\n", certs);
			TextView l = text(getString(R.string.arena_gate_cert_label), 11, ArenaUi.TEXT2, false);
			l.setPadding(0, dp(22), 0, dp(4));
			col.addView(l);
			TextView c = text(all, 10, Color.WHITE, false);
			c.setTypeface(Typeface.MONOSPACE);
			c.setTextIsSelectable(true);
			col.addView(c);
			col.addView(button(getString(R.string.arena_gate_copy), false, v -> {
				ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
				if (cm != null) {
					cm.setPrimaryClip(ClipData.newPlainText("cert", all));
					Toast.makeText(this, R.string.arena_gate_copied, Toast.LENGTH_SHORT).show();
				}
			}));
		}

		View spacer = new View(this);
		col.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
		TextView lic = text(getString(R.string.arena_license), 10, 0xFF6C7EA8, false);
		lic.setPadding(0, dp(28), 0, dp(4));
		col.addView(lic);
		final String src = getString(R.string.arena_source_url);
		TextView link = text(getString(R.string.arena_source) + ": " + src, 10, ArenaUi.NEON, false);
		link.setOnClickListener(v -> {
			try {
				startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(src)));
			} catch (Exception e) {
				// sem browser
			}
		});
		col.addView(link);
		setContentView(scroll);
	}

	private static int reasonText(String reason) {
		if (reason == null) return 0;
		switch (reason) {
			case ArenaGuard.R_NO_AUTH: return R.string.arena_gate_reason_no_auth;
			case ArenaGuard.R_WRONG_SENDER: return R.string.arena_gate_reason_wrong_sender;
			case ArenaGuard.R_NO_LAUNCHER: return R.string.arena_gate_no_launcher;
			case ArenaGuard.R_BAD_CERT: return R.string.arena_gate_reason_bad_cert;
			case ArenaGuard.R_NO_ISO: return R.string.arena_gate_reason_no_iso;
			case ArenaGuard.R_BAD_NET: return R.string.arena_gate_reason_bad_net;
			case ArenaGuard.R_STORAGE: return R.string.arena_net_local_error;
			default: return 0;
		}
	}

	private TextView text(String s, float sp, int color, boolean bold) {
		TextView t = new TextView(this);
		t.setText(s);
		t.setTextColor(color);
		t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
		t.setGravity(Gravity.CENTER);
		if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
		return t;
	}

	private Button button(String label, boolean primary, View.OnClickListener l) {
		Button b = new Button(this);
		b.setText(label);
		b.setAllCaps(true);
		b.setTypeface(Typeface.DEFAULT_BOLD);
		b.setTextColor(primary ? 0xFF04121F : Color.WHITE);
		b.setBackground(ArenaUi.pill(primary ? ArenaUi.NEON : 0x00000000, primary ? 0 : 0x55FFFFFF, dp(26)));
		b.setOnClickListener(l);
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
		lp.topMargin = dp(14);
		b.setLayoutParams(lp);
		return b;
	}
}
