// Copyright (c) 2026- GameArena. Part of Arena Console, a fork of PPSSPP (https://github.com/hrydgard/ppsspp).
// This program is free software: you can redistribute it and/or modify it under the terms of the
// GNU General Public License as published by the Free Software Foundation, version 2.0 or later versions.
// GA20_ARENA (Fase 20.2.3): ficheiro novo.

package org.ppsspp.ppsspp;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import io.socket.client.IO;
import io.socket.engineio.client.transports.WebSocket;

/**
 * Ponte de rede do Arena Console (opção C: túnel por WebSocket até ao Render).
 *
 * O PPSSPP corre o ad hoc em modo relay com o servidor 127.0.0.1 (arena.ini). Esta classe:
 *  - abre 127.0.0.1:27312 (adhocctl) e 127.0.0.1:27313 (aemu_postoffice) só no próprio telemóvel;
 *  - liga-se a server_url + "/console" (Socket.IO, path /ws), faz emu_join com o start_token da partida;
 *  - passa cada ligação TCP do PPSSPP como um stream: tun_open {s,p} / tun_data {s,b} / tun_close {s}.
 * O servidor (backend/services/console-adhoc.js) faz de servidor adhocctl + postoffice só para os 2 jogadores.
 */
public final class ArenaNet {
	private static final String TAG = "ArenaNet";
	public static final int ADHOCCTL_PORT = 27312;
	public static final int POSTOFFICE_PORT = 27313;
	private static final int READ_BUF = 16 * 1024;
	private static final int MAX_PENDING_BYTES = 256 * 1024;
	private static final long PENDING_TIMEOUT_MS = 20000;

	public interface Listener {
		/** O servidor fechou a partida (winnerRole: host/guest/draw; reason: forfeit/agreed/dnf). */
		void onOver(String winnerRole, String reason);
		/** Estado da ligação (um dos ST_*), para mostrar ao jogador. */
		void onStatus(String status);
	}

	public static final String ST_JOINING = "joining";
	public static final String ST_WAITING_PEER = "waiting_peer";
	public static final String ST_PEER_ONLINE = "peer_online";
	public static final String ST_PEER_LOST = "peer_lost";
	public static final String ST_REFUSED = "refused";
	public static final String ST_CONN_LOST = "conn_lost";
	public static final String ST_CONN_ERROR = "conn_error";
	public static final String ST_LOCAL_ERROR = "local_error";

	private static ArenaNet instance;
	private static Listener listener;
	private static final Handler main = new Handler(Looper.getMainLooper());

	private final ArenaSession session;
	private final Map<Integer, Stream> streams = new ConcurrentHashMap<>();
	private final AtomicInteger nextId = new AtomicInteger(1);
	private final List<ServerSocket> servers = Collections.synchronizedList(new ArrayList<>());
	private volatile io.socket.client.Socket sock;
	private volatile boolean ready = false;
	private volatile boolean stopped = false;
	private volatile String overWinner = null;
	private volatile String overReason = null;
	private volatile String pendingResult = null;

	private ArenaNet(ArenaSession s) {
		session = s;
	}

	// ───────────────────────── API ─────────────────────────

	public static synchronized void start(ArenaSession s) {
		if (s == null || !s.netplayReady) {
			Log.w(TAG, "Sem dados de rede válidos: ponte não iniciada");
			return;
		}
		if (instance != null && !instance.stopped && ArenaConfig.sameMatch(instance.session, s)) {
			return;  // a mesma partida (ex.: Activity recriada)
		}
		stop();
		final ArenaNet n = new ArenaNet(s);
		instance = n;
		// Portas e ligação fora do thread principal (o onCreate não espera pela rede).
		Thread t = new Thread(n::begin, "ArenaNet-start");
		t.setDaemon(true);
		t.start();
	}

	public static synchronized void stop() {
		if (instance != null) {
			instance.shutdown();
			instance = null;
		}
	}

	public static synchronized boolean isActive() {
		return instance != null && !instance.stopped;
	}

	public static void setListener(Listener l) {
		listener = l;
		ArenaNet n = instance;
		if (l != null && n != null && n.overWinner != null) {
			final String w = n.overWinner, r = n.overReason;
			main.post(() -> l.onOver(w, r));
		}
	}

	/** O meu papel na partida ("host"/"guest"), ou "" sem partida. */
	public static String myRole() {
		ArenaNet n = instance;
		return n == null ? "" : n.session.role;
	}

	public static String otherRole() {
		String r = myRole();
		return r.equals("host") ? "guest" : r.equals("guest") ? "host" : "";
	}

	/** Envia o resultado: o adversário como vencedor = desistência (conta logo); eu = vitória (o adversário confirma). */
	public static void report(String winnerRole) {
		ArenaNet n = instance;
		if (n == null) return;
		n.pendingResult = winnerRole;
		n.sendResult();
	}

	public static boolean isOver() {
		ArenaNet n = instance;
		return n != null && n.overWinner != null;
	}

	// ───────────────────────── ligação ao servidor ─────────────────────────

	private void begin() {
		try {
			listen(ADHOCCTL_PORT);
			listen(POSTOFFICE_PORT);
		} catch (IOException e) {
			Log.e(TAG, "Não foi possível abrir as portas locais: " + e.getMessage());
			status(ST_LOCAL_ERROR);
		}
		try {
			IO.Options o = IO.Options.builder()
				.setPath("/ws")
				.setTransports(new String[] { WebSocket.NAME })
				.setReconnection(true)
				.setReconnectionDelay(1000)
				.setReconnectionDelayMax(5000)
				.setTimeout(20000)
				.build();
			io.socket.client.Socket s = IO.socket(URI.create(session.serverUrl + session.signalNamespace), o);
			sock = s;
			s.on(io.socket.client.Socket.EVENT_CONNECT, a -> {
				Log.i(TAG, "Ligado ao servidor, a entrar na partida " + session.roomCode);
				status(ST_JOINING);
				try {
					s.emit("emu_join", new JSONObject().put("code", session.roomCode).put("pid", session.peerId).put("startToken", session.startToken));
				} catch (Exception e) {
					Log.e(TAG, "emu_join: " + e.getMessage());
				}
			});
			s.on("emu_ready", a -> {
				ready = true;
				JSONObject m = a.length > 0 && a[0] instanceof JSONObject ? (JSONObject) a[0] : new JSONObject();
				status(m.optBoolean("peerOnline") ? ST_PEER_ONLINE : ST_WAITING_PEER);
				for (Stream st : streams.values()) st.openRemote();
				sendResult();
			});
			s.on("emu_error", a -> {
				String r = a.length > 0 && a[0] instanceof JSONObject ? ((JSONObject) a[0]).optString("reason") : "";
				Log.e(TAG, "emu_error: " + r);
				ready = false;
				status(ST_REFUSED);
			});
			s.on("emu_peer", a -> {
				boolean on = a.length > 0 && a[0] instanceof JSONObject && ((JSONObject) a[0]).optBoolean("online");
				status(on ? ST_PEER_ONLINE : ST_PEER_LOST);
			});
			s.on("console_over", a -> {
				JSONObject m = a.length > 0 && a[0] instanceof JSONObject ? (JSONObject) a[0] : new JSONObject();
				overWinner = m.optString("winnerRole");
				overReason = m.optString("reason");
				final Listener l = listener;
				final String w = overWinner, r = overReason;
				if (l != null) main.post(() -> l.onOver(w, r));
			});
			s.on("tun_data", a -> {
				if (a.length == 0 || !(a[0] instanceof JSONObject)) return;
				JSONObject m = (JSONObject) a[0];
				Stream st = streams.get(m.optInt("s", -1));
				Object b = m.opt("b");
				if (st != null && b instanceof byte[]) st.toLocal((byte[]) b);
			});
			s.on("tun_close", a -> {
				if (a.length == 0 || !(a[0] instanceof JSONObject)) return;
				Stream st = streams.get(((JSONObject) a[0]).optInt("s", -1));
				if (st != null) st.close(false);
			});
			s.on(io.socket.client.Socket.EVENT_DISCONNECT, a -> {
				ready = false;
				Log.w(TAG, "Ligação ao servidor perdida");
				status(ST_CONN_LOST);
				// O estado ad hoc do servidor perde-se: fecha as ligações do PPSSPP para ele voltar a ligar-se.
				for (Stream st : new ArrayList<>(streams.values())) st.close(false);
			});
			s.on(io.socket.client.Socket.EVENT_CONNECT_ERROR, a -> status(ST_CONN_ERROR));
			s.connect();
		} catch (Exception e) {
			Log.e(TAG, "Socket.IO: " + e.getMessage());
			status(ST_CONN_ERROR);
		}
	}

	private void sendResult() {
		io.socket.client.Socket s = sock;
		String w = pendingResult;
		if (s == null || w == null || !ready) return;
		try {
			s.emit("emu_result", new JSONObject().put("winnerRole", w));
		} catch (Exception e) {
			Log.e(TAG, "emu_result: " + e.getMessage());
		}
	}

	private void status(String text) {
		final Listener l = listener;
		if (l != null) main.post(() -> l.onStatus(text));
	}

	private void shutdown() {
		stopped = true;
		ready = false;
		synchronized (servers) {
			for (ServerSocket ss : servers) {
				try { ss.close(); } catch (IOException e) { /* já fechado */ }
			}
			servers.clear();
		}
		for (Stream st : new ArrayList<>(streams.values())) st.close(false);
		io.socket.client.Socket s = sock;
		sock = null;
		if (s != null) {
			s.off();
			s.disconnect();
		}
	}

	// ───────────────────────── portas locais ─────────────────────────

	private void listen(int port) throws IOException {
		ServerSocket ss = new ServerSocket();
		ss.setReuseAddress(true);
		ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 8);
		servers.add(ss);
		Thread t = new Thread(() -> {
			while (!stopped) {
				try {
					Socket c = ss.accept();
					if (stopped) { c.close(); break; }
					c.setTcpNoDelay(true);
					new Stream(nextId.getAndIncrement(), port, c).begin();
				} catch (IOException e) {
					if (!stopped) Log.w(TAG, "accept " + port + ": " + e.getMessage());
					break;
				}
			}
		}, "ArenaNet-accept-" + port);
		t.setDaemon(true);
		t.start();
	}

	/** Uma ligação TCP do PPSSPP = um stream no túnel. */
	private final class Stream {
		final int id;
		final int port;
		final Socket local;
		final LinkedBlockingQueue<byte[]> outQ = new LinkedBlockingQueue<>();
		final List<byte[]> pending = new ArrayList<>();
		int pendingBytes = 0;
		boolean opened = false;
		volatile boolean closed = false;
		final long created = System.currentTimeMillis();

		Stream(int id, int port, Socket local) {
			this.id = id;
			this.port = port;
			this.local = local;
		}

		void begin() {
			streams.put(id, this);
			if (ready) openRemote();
			Thread reader = new Thread(this::readLoop, "ArenaNet-r" + id);
			reader.setDaemon(true);
			reader.start();
			Thread writer = new Thread(this::writeLoop, "ArenaNet-w" + id);
			writer.setDaemon(true);
			writer.start();
		}

		synchronized void openRemote() {
			if (opened || closed) return;
			io.socket.client.Socket s = sock;
			if (s == null || !ready) return;
			try {
				s.emit("tun_open", new JSONObject().put("s", id).put("p", port));
				opened = true;
				for (byte[] b : pending) emitData(s, b);
				pending.clear();
				pendingBytes = 0;
			} catch (Exception e) {
				Log.e(TAG, "tun_open: " + e.getMessage());
			}
		}

		private void emitData(io.socket.client.Socket s, byte[] b) throws Exception {
			s.emit("tun_data", new JSONObject().put("s", id).put("b", b));
		}

		synchronized void fromLocal(byte[] b) {
			if (closed) return;
			io.socket.client.Socket s = sock;
			if (opened && s != null && ready) {
				try {
					emitData(s, b);
				} catch (Exception e) {
					Log.e(TAG, "tun_data: " + e.getMessage());
				}
				return;
			}
			// Ainda sem ligação ao servidor: guarda um pouco (o PPSSPP liga-se ao arrancar o modo ad hoc).
			pending.add(b);
			pendingBytes += b.length;
			if (pendingBytes > MAX_PENDING_BYTES || System.currentTimeMillis() - created > PENDING_TIMEOUT_MS) {
				close(true);
			}
		}

		void toLocal(byte[] b) {
			if (!closed && b.length > 0) outQ.offer(b);  // o array vazio é o marcador de fim
		}

		void readLoop() {
			byte[] buf = new byte[READ_BUF];
			try {
				InputStream in = local.getInputStream();
				while (!closed) {
					int n = in.read(buf);
					if (n < 0) break;
					if (n > 0) fromLocal(Arrays.copyOf(buf, n));
				}
			} catch (IOException e) {
				// ligação local fechada
			}
			close(true);
		}

		void writeLoop() {
			try {
				OutputStream out = local.getOutputStream();
				while (!closed) {
					byte[] b = outQ.take();
					if (b.length == 0) break;  // marcador de fim
					out.write(b);
					out.flush();
				}
			} catch (Exception e) {
				// fechado
			}
			close(true);
		}

		void close(boolean tellServer) {
			synchronized (this) {
				if (closed) return;
				closed = true;
			}
			streams.remove(id);
			outQ.offer(new byte[0]);
			try { local.close(); } catch (IOException e) { /* já fechado */ }
			io.socket.client.Socket s = sock;
			if (tellServer && opened && s != null) {
				try {
					s.emit("tun_close", new JSONObject().put("s", id));
				} catch (Exception e) {
					Log.w(TAG, "tun_close: " + e.getMessage());
				}
			}
		}
	}

	/** Para testes/diagnóstico. */
	static Map<String, Object> debugState() {
		Map<String, Object> m = new HashMap<>();
		ArenaNet n = instance;
		m.put("active", n != null && !n.stopped);
		m.put("ready", n != null && n.ready);
		m.put("streams", n == null ? 0 : n.streams.size());
		return m;
	}
}
