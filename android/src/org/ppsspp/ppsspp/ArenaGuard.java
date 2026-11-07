// Copyright (c) 2026- GameArena. Part of Arena Console, a fork of PPSSPP (https://github.com/hrydgard/ppsspp).
// This program is free software: you can redistribute it and/or modify it under the terms of the
// GNU General Public License as published by the Free Software Foundation, version 2.0 or later versions.
// GA20_ARENA (Fase 20.2.2): ficheiro novo.

package org.ppsspp.ppsspp;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.util.Log;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Decide se o PpssppActivity pode arrancar: só com o Intent START_GAME da Game Arena verdadeira.
 *
 * Como sabemos quem mandou o Intent (funciona em todas as versões do Android):
 *  1. o Launcher junta um PendingIntent seu no extra "arena_auth" (nunca é enviado, serve de "assinatura");
 *  2. PendingIntent.getCreatorPackage() é dado pelo sistema e não pode ser falsificado por outra app;
 *  3. o pacote criador tem de ser o Launcher (arena_launcher_package) e estar assinado com um dos
 *     certificados SHA-256 de arena_launcher_certs_sha256 (res/values/arena_config.xml).
 */
public final class ArenaGuard {
	private static final String TAG = "ArenaGuard";
	public static final String EXTRA_AUTH = "arena_auth";

	public static final String R_OK = "ok";
	public static final String R_NOT_ARENA = "not_arena";       // aberto pelo ícone ou por outra app
	public static final String R_NO_AUTH = "no_auth";           // Intent sem a prova do Launcher
	public static final String R_WRONG_SENDER = "wrong_sender"; // PendingIntent criado por outra app
	public static final String R_NO_LAUNCHER = "no_launcher";   // Game Arena não instalada
	public static final String R_BAD_CERT = "bad_cert";         // Game Arena com outra assinatura
	public static final String R_NO_ISO = "no_iso";
	public static final String R_BAD_NET = "bad_net";           // dados da partida inválidos
	public static final String R_STORAGE = "storage";           // não foi possível preparar a configuração da partida

	public static final class Result {
		public final boolean ok;
		public final String reason;
		public final ArenaSession session;
		/** Certificado(s) da Game Arena instalada, para mostrar no portão quando não batem certo. */
		public final List<String> launcherCerts;

		Result(boolean ok, String reason, ArenaSession session, List<String> launcherCerts) {
			this.ok = ok;
			this.reason = reason;
			this.session = session;
			this.launcherCerts = launcherCerts;
		}
	}

	private ArenaGuard() {}

	public static String launcherPackage(Context ctx) {
		return ctx.getString(R.string.arena_launcher_package);
	}

	public static Result check(Context ctx, Intent intent) {
		ArenaSession s = ArenaSession.fromIntent(intent);
		if (s == null) {
			return new Result(false, R_NOT_ARENA, null, new ArrayList<>());
		}
		String launcher = launcherPackage(ctx);
		PackageManager pm = ctx.getPackageManager();
		List<String> installed = certsOf(pm, launcher);

		PendingIntent auth = null;
		try {
			if (Build.VERSION.SDK_INT >= 33) {
				auth = intent.getParcelableExtra(EXTRA_AUTH, PendingIntent.class);
			} else {
				auth = legacyAuth(intent);
			}
		} catch (Exception e) {
			Log.w(TAG, "arena_auth ilegível: " + e.getMessage());
		}
		if (auth == null) {
			return new Result(false, R_NO_AUTH, s, installed);
		}
		String creator = auth.getCreatorPackage();
		if (creator == null || !creator.equals(launcher)) {
			Log.w(TAG, "Intent criado por " + creator + ", esperado " + launcher);
			return new Result(false, R_WRONG_SENDER, s, installed);
		}
		if (installed.isEmpty()) {
			return new Result(false, R_NO_LAUNCHER, s, installed);
		}
		if (!signedWithAllowed(ctx, pm, launcher)) {
			Log.w(TAG, "Certificado da Game Arena não autorizado: " + installed);
			return new Result(false, R_BAD_CERT, s, installed);
		}
		if (s.bootTarget == null) {
			return new Result(false, R_NO_ISO, s, installed);
		}
		if (!s.solo && !s.netplayReady) { // GA20_SOLO: permitir modo treino
			Log.w(TAG, "Dados de rede inválidos: " + s.netplayError);
			return new Result(false, R_BAD_NET, s, installed);
		}
		return new Result(true, R_OK, s, installed);
	}

	@SuppressWarnings("deprecation")
	private static PendingIntent legacyAuth(Intent intent) {
		Object o = intent.getParcelableExtra(EXTRA_AUTH);
		return (o instanceof PendingIntent) ? (PendingIntent) o : null;
	}

	/** Certificados autorizados (SHA-256 em hexadecimal, com ou sem ":"), normalizados para "AA:BB:...". */
	public static List<String> allowedCerts(Context ctx) {
		List<String> out = new ArrayList<>();
		for (String c : ctx.getResources().getStringArray(R.array.arena_launcher_certs_sha256)) {
			String n = normalize(c);
			if (n.length() == 95) {
				out.add(n);
			}
		}
		return out;
	}

	private static boolean signedWithAllowed(Context ctx, PackageManager pm, String pkg) {
		List<String> allowed = allowedCerts(ctx);
		if (allowed.isEmpty()) {
			Log.e(TAG, "arena_launcher_certs_sha256 está vazio: nenhuma Game Arena é aceite.");
			return false;
		}
		if (Build.VERSION.SDK_INT >= 28) {
			// Respeita a rotação de chaves (APK Signature Scheme v3).
			for (String hex : allowed) {
				try {
					if (pm.hasSigningCertificate(pkg, fromHex(hex), PackageManager.CERT_INPUT_SHA256)) {
						return true;
					}
				} catch (Exception e) {
					Log.w(TAG, "hasSigningCertificate: " + e.getMessage());
				}
			}
			return false;
		}
		List<String> installed = certsOf(pm, pkg);
		if (installed.isEmpty()) {
			return false;
		}
		for (String c : installed) {
			if (!allowed.contains(c)) {
				return false;  // com várias assinaturas, todas têm de ser autorizadas
			}
		}
		return true;
	}

	/** SHA-256 dos certificados atuais do pacote (vazio se não estiver instalado/visível). */
	@SuppressWarnings("deprecation")
	public static List<String> certsOf(PackageManager pm, String pkg) {
		List<String> out = new ArrayList<>();
		try {
			Signature[] sigs;
			if (Build.VERSION.SDK_INT >= 28) {
				PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
				SigningInfo si = pi.signingInfo;
				if (si == null) {
					return out;
				}
				if (si.hasMultipleSigners()) {
					sigs = si.getApkContentsSigners();
				} else {
					Signature[] hist = si.getSigningCertificateHistory();
					sigs = (hist == null || hist.length == 0) ? new Signature[0] : new Signature[] { hist[hist.length - 1] };
				}
			} else {
				PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
				sigs = pi.signatures;
			}
			if (sigs == null) {
				return out;
			}
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			for (Signature sig : sigs) {
				out.add(toHex(md.digest(sig.toByteArray())));
			}
		} catch (PackageManager.NameNotFoundException e) {
			// não instalado (ou invisível sem <queries>)
		} catch (Exception e) {
			Log.w(TAG, "certsOf " + pkg + ": " + e.getMessage());
		}
		return out;
	}

	static String normalize(String s) {
		String h = s == null ? "" : s.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.ROOT);
		if (h.length() != 64) {
			return "";
		}
		StringBuilder b = new StringBuilder(95);
		for (int i = 0; i < 64; i += 2) {
			if (i > 0) b.append(':');
			b.append(h, i, i + 2);
		}
		return b.toString();
	}

	private static String toHex(byte[] d) {
		StringBuilder b = new StringBuilder(d.length * 3);
		for (int i = 0; i < d.length; i++) {
			if (i > 0) b.append(':');
			b.append(String.format(Locale.ROOT, "%02X", d[i] & 0xff));
		}
		return b.toString();
	}

	private static byte[] fromHex(String colonHex) {
		String h = colonHex.replace(":", "");
		byte[] out = new byte[h.length() / 2];
		for (int i = 0; i < out.length; i++) {
			out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
		}
		return out;
	}
}
