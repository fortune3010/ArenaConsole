// Copyright (c) 2026- GameArena. Part of Arena Console, a fork of PPSSPP (https://github.com/hrydgard/ppsspp).
// This program is free software: you can redistribute it and/or modify it under the terms of the
// GNU General Public License as published by the Free Software Foundation, version 2.0 or later versions.
// GA20_ARENA (Fase 20.2.2/20.2.3): ficheiro novo.

package org.ppsspp.ppsspp;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Configuração automática de cada partida, sem menus:
 *  - arena.ini (passado com --appendconfig): desliga cheats, rewind, desfazer save, depurador, chat e simulação
 *    de rede; liga o ad hoc com o servidor 127.0.0.1 (a ponte ArenaNet) em modo relay por TCP;
 *  - --memstick=<pasta privada da app>: sem o ecrã de escolha de pasta no 1.º arranque;
 *  - --pause-menu-exit: se o jogo terminar, a app fecha em vez de abrir a lista de jogos.
 * O C++ (mensagem "arena_lock") volta a forçar as opções de integridade depois de carregar a configuração.
 */
public final class ArenaConfig {
	private static final String TAG = "ArenaConfig";
	private static volatile String pendingArgs = "";

	private ArenaConfig() {}

	/** As mesmas partidas (sala + jogador + token): usado para não reiniciar o jogo quando a Activity é recriada. */
	public static boolean sameMatch(ArenaSession a, ArenaSession b) {
		return a != null && b != null && a.roomCode.equals(b.roomCode) && a.peerId.equals(b.peerId) && a.startToken.equals(b.startToken);
	}

	/** Escreve o arena.ini desta partida e prepara os argumentos de arranque. Devolve false se não conseguiu. */
	public static boolean prepare(Context ctx, ArenaSession s) {
		pendingArgs = "";
		try {
			File base = ctx.getExternalFilesDir(null);
			if (base == null) {
				base = ctx.getFilesDir();
			}
			File memstick = new File(base, "memstick");
			if (!memstick.isDirectory() && !memstick.mkdirs()) {
				Log.e(TAG, "Não foi possível criar " + memstick);
				return false;
			}
			File dir = new File(ctx.getFilesDir(), "arena");
			if (!dir.isDirectory() && !dir.mkdirs()) {
				Log.e(TAG, "Não foi possível criar " + dir);
				return false;
			}
			File ini = new File(dir, "arena.ini");
			try (FileOutputStream os = new FileOutputStream(ini)) {
				os.write(iniFor(s).getBytes(StandardCharsets.UTF_8));
			}
			// Os caminhos das pastas privadas não têm espaços; mesmo assim, recusamos se tiverem (a linha de comandos parte nos espaços).
			String mp = memstick.getAbsolutePath(), ip = ini.getAbsolutePath();
			if (mp.contains(" ") || ip.contains(" ") || mp.contains("\"") || ip.contains("\"")) {
				Log.e(TAG, "Caminho com espaços: " + mp + " / " + ip);
				return false;
			}
			pendingArgs = "--pause-menu-exit --memstick=" + mp + " --appendconfig=" + ip;
			return true;
		} catch (Exception e) {
			Log.e(TAG, "prepare: " + e.getMessage());
			return false;
		}
	}

	public static String pendingArgs() {
		return pendingArgs;
	}

	/** MAC fixo por jogador e partida, derivado do peer_id (endereço "local" unicast: 02:xx:...). */
	public static String macFor(ArenaSession s) {
		String h = (s.peerId + "000000000000").substring(0, 12);
		int b0 = (Integer.parseInt(h.substring(0, 2), 16) & 0xFC) | 0x02;
		StringBuilder b = new StringBuilder(17);
		b.append(String.format(Locale.ROOT, "%02x", b0));
		for (int i = 2; i < 12; i += 2) {
			b.append(':').append(h, i, i + 2);
		}
		return b.toString();
	}

	static String iniFor(ArenaSession s) {
		String nick = s.isHost() ? "ArenaP1" : "ArenaP2";
		return "; GA20_ARENA: gerado pelo Arena Console para esta partida. Não editar.\n"
			+ "[General]\n"
			+ "FirstRun = False\n"
			+ "CheckForNewVersion = False\n"
			+ "EnableCheats = False\n"
			+ "RewindSnapshotInterval = 0\n"
			+ "EnableStateUndo = False\n"
			+ "AutoLoadSaveState = 0\n"
			+ "RemoteDebuggerOnStartup = False\n"
			+ "RemoteShareOnStartup = False\n"
			+ "[Network]\n"
			+ "EnableWlan = True\n"
			+ "EnableAdhocServer = False\n"
			+ "proAdhocServer = 127.0.0.1\n"
			+ "AdhocServerRelayMode = 1\n"
			+ "AdhocUseUdp = False\n"
			+ "EnableUPnP = False\n"
			+ "AdhocSimDelayMs = 0\n"
			+ "AdhocSimJitterMs = 0\n"
			+ "AdhocSimLossPercent = 0\n"
			+ "AllowSavestateWhileConnected = False\n"
			+ "AllowSpeedControlWhileConnected = False\n"
			+ "EnableNetworkChat = False\n"
			+ "[SystemParam]\n"
			+ "NickName = " + nick + "\n"
			+ "MacAddress = " + macFor(s) + "\n"
			+ "[Debugger]\n"
			+ "ShowDeveloperMenu = False\n"
			+ "[Achievements]\n"
			+ "AchievementsEnable = False\n";
	}
}
