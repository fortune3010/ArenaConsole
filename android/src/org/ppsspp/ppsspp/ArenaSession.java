package org.ppsspp.ppsspp;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * GA20_ARENA (Fase 20.2.1): dados da partida enviados pelo Launcher GameArena.
 *
 * O Launcher abre o Intent "com.gamearena.console.START_GAME" (contrato: CONTRATO-ARENA-CONSOLE.md, protocolo 1).
 * A ISO chega por content:// no ClipData (com FLAG_GRANT_READ_URI_PERMISSION) e também no extra "iso_uri".
 * Os extras vêm soltos e repetidos no Bundle "arena_bundle".
 *
 * Esta classe só lê e valida os dados. O PpssppActivity.parseIntent() usa bootTarget para arrancar o jogo
 * direto (o mesmo caminho dos atalhos do PPSSPP). Os dados de rede ficam em ArenaSession.current para a
 * Fase 20.2.2 (netplay WebRTC); nada aqui abre ligações.
 */
public final class ArenaSession {
	private static final String TAG = "ArenaSession";

	public static final String ACTION_START_GAME = "com.gamearena.console.START_GAME";
	public static final int SUPPORTED_PROTOCOL = 1;
	public static final String EXTRA_BUNDLE = "arena_bundle";

	// Só aceitamos sinalização do nosso servidor (o PpssppActivity é exported: qualquer app pode mandar este Intent).
	private static final String[] ALLOWED_SERVER_HOSTS = { "gamer-fortune.onrender.com" };

	private static final Pattern CODE_RE = Pattern.compile("^[A-Z0-9]{4,8}$");
	private static final Pattern PID_RE = Pattern.compile("^[a-f0-9]{8,64}$");
	private static final Pattern TOKEN_RE = Pattern.compile("^[A-Za-z0-9_-]{8,128}$");
	private static final Pattern FP_RE = Pattern.compile("^[a-f0-9]{16,64}$");

	/** A sessão da última partida aberta pelo Launcher (null se o PPSSPP foi aberto de outra forma). */
	public static volatile ArenaSession current = null;

	// ── ISO ──
	/** O que o PPSSPP vai arrancar: content://… (preferido) ou um caminho legível. null = mostrar o menu. */
	public final String bootTarget;
	public final String isoName;
	public final String isoFingerprint;
	public final String gameId;

	// ── rede (Fase 20.2.2) ──
	public final String role;           // "host" | "guest"
	public final String peerId;
	public final String remotePeerId;
	public final String roomCode;
	public final String startToken;     // segredo: nunca vai para o log
	public final String serverUrl;
	public final String signalNamespace;
	public final int protocol;
	/** true se todos os dados de rede passaram a validação (role, ids, código, token, servidor, protocolo). */
	public final boolean netplayReady;
	/** Motivo pelo qual netplayReady é false ("" se estiver tudo bem). */
	public final String netplayError;

	private ArenaSession(String bootTarget, String isoName, String isoFingerprint, String gameId,
	                     String role, String peerId, String remotePeerId, String roomCode, String startToken,
	                     String serverUrl, String signalNamespace, int protocol, String netplayError) {
		this.bootTarget = bootTarget;
		this.isoName = isoName;
		this.isoFingerprint = isoFingerprint;
		this.gameId = gameId;
		this.role = role;
		this.peerId = peerId;
		this.remotePeerId = remotePeerId;
		this.roomCode = roomCode;
		this.startToken = startToken;
		this.serverUrl = serverUrl;
		this.signalNamespace = signalNamespace;
		this.protocol = protocol;
		this.netplayError = netplayError;
		this.netplayReady = netplayError.isEmpty();
	}

	public boolean isHost() {
		return "host".equals(role);
	}

	/** Devolve a sessão se o Intent vier do Launcher GameArena, senão null (o PPSSPP segue o caminho normal). */
	public static ArenaSession fromIntent(Intent intent) {
		if (intent == null || !ACTION_START_GAME.equals(intent.getAction())) {
			return null;
		}
		Bundle loose = intent.getExtras();
		Bundle grouped = null;
		try {
			grouped = intent.getBundleExtra(EXTRA_BUNDLE);
		} catch (Exception e) {
			Log.w(TAG, "arena_bundle ilegível: " + e.getMessage());
		}

		String isoUri = str(loose, grouped, "iso_uri");
		String isoPath = str(loose, grouped, "iso_path");
		String isoName = clean(str(loose, grouped, "iso_name"), 80);
		String fp = str(loose, grouped, "iso_fingerprint").toLowerCase(Locale.ROOT);
		String gameId = clean(str(loose, grouped, "game_id"), 32);
		String role = str(loose, grouped, "role");
		String peerId = str(loose, grouped, "peer_id");
		String remotePeerId = str(loose, grouped, "remote_peer_id");
		String roomCode = str(loose, grouped, "room_code").toUpperCase(Locale.ROOT);
		String startToken = str(loose, grouped, "start_token");
		String serverUrl = str(loose, grouped, "server_url");
		String ns = str(loose, grouped, "signal_namespace");
		int protocol = integer(loose, grouped, "protocol_version");

		String boot = pickBootTarget(intent, isoUri, isoPath);
		String err = validateNet(role, peerId, remotePeerId, roomCode, startToken, serverUrl, ns, protocol);
		if (!fp.isEmpty() && !FP_RE.matcher(fp).matches()) {
			fp = "";
		}

		ArenaSession s = new ArenaSession(boot, isoName, fp, gameId, role, peerId, remotePeerId, roomCode,
			err.isEmpty() ? startToken : "", serverUrl, ns, protocol, err);
		Log.i(TAG, s.toString());
		return s;
	}

	/** ClipData (onde vai a permissão de leitura) → extra iso_uri → iso_path só se o ficheiro for legível. */
	private static String pickBootTarget(Intent intent, String isoUri, String isoPath) {
		ClipData clip = intent.getClipData();
		if (clip != null && clip.getItemCount() > 0) {
			Uri u = clip.getItemAt(0).getUri();
			if (u != null && "content".equals(u.getScheme())) {
				return u.toString();
			}
		}
		if (isoUri.startsWith("content://")) {
			return isoUri;
		}
		if (!isoPath.isEmpty()) {
			// No Android 11+ o PPSSPP não consegue ler /Android/data/<launcher>/…: só serve em versões antigas.
			File f = new File(isoPath);
			if (f.isFile() && f.canRead()) {
				return f.getAbsolutePath();
			}
			Log.w(TAG, "iso_path não legível por esta app: " + isoPath);
		}
		return null;
	}

	private static String validateNet(String role, String peerId, String remotePeerId, String roomCode, String startToken,
	                                  String serverUrl, String ns, int protocol) {
		if (protocol != SUPPORTED_PROTOCOL) return "protocol_version " + protocol + " (suportado: " + SUPPORTED_PROTOCOL + ")";
		if (!"host".equals(role) && !"guest".equals(role)) return "role inválido";
		if (!PID_RE.matcher(peerId).matches()) return "peer_id inválido";
		if (!PID_RE.matcher(remotePeerId).matches() || remotePeerId.equals(peerId)) return "remote_peer_id inválido";
		if (!CODE_RE.matcher(roomCode).matches()) return "room_code inválido";
		if (!TOKEN_RE.matcher(startToken).matches()) return "start_token inválido";
		if (!"/console".equals(ns)) return "signal_namespace inválido";
		Uri u;
		try {
			u = Uri.parse(serverUrl);
		} catch (Exception e) {
			return "server_url inválido";
		}
		if (u == null || !"https".equals(u.getScheme()) || u.getHost() == null) return "server_url tem de ser https";
		String host = u.getHost().toLowerCase(Locale.ROOT);
		for (String h : ALLOWED_SERVER_HOSTS) {
			if (h.equals(host)) return "";
		}
		return "servidor não autorizado: " + host;
	}

	private static String str(Bundle loose, Bundle grouped, String key) {
		String v = null;
		try {
			if (loose != null) v = loose.getString(key);
			if ((v == null || v.isEmpty()) && grouped != null) v = grouped.getString(key);
		} catch (Exception e) {
			v = null;
		}
		return v == null ? "" : v.trim();
	}

	private static int integer(Bundle loose, Bundle grouped, String key) {
		try {
			if (loose != null && loose.containsKey(key)) return loose.getInt(key, -1);
			if (grouped != null && grouped.containsKey(key)) return grouped.getInt(key, -1);
		} catch (Exception e) {
			// valor com tipo errado
		}
		return -1;
	}

	private static String clean(String s, int max) {
		String t = s.replaceAll("[\\u0000-\\u001f<>]", "").trim();
		return t.length() > max ? t.substring(0, max) : t;
	}

	/** Para o log: nunca inclui o start_token. */
	@Override
	public String toString() {
		return "ArenaSession{boot=" + (bootTarget != null ? bootTarget : "(nenhum: menu)")
			+ ", iso=" + isoName + (gameId.isEmpty() ? "" : " [" + gameId + "]")
			+ ", role=" + role + ", room=" + roomCode + ", peer=" + peerId + ", remote=" + remotePeerId
			+ ", server=" + serverUrl + signalNamespace + ", protocol=" + protocol
			+ ", netplay=" + (netplayReady ? "ok" : "NÃO (" + netplayError + ")") + "}";
	}
}
