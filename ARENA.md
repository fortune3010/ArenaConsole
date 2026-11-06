# Arena Console: alterações ao PPSSPP

Fork do [PPSSPP](https://github.com/hrydgard/ppsspp) (Henrik Rydgård e contribuidores, GPL 2.0 ou posterior),
mantido pela Game Arena. Base: PPSSPP `f82a875` (5/11/2026). Todas as alterações têm a marca `GA20_ARENA`
(ou `GA20_ARENA_V2`).

## O que é diferente do PPSSPP
| Área | PPSSPP | Arena Console |
|---|---|---|
| Ícone | abre a lista de jogos | abre o portão "Abre pela Game Arena" (`ArenaGateActivity`) |
| Abrir jogos | ficheiros, atalhos, outras apps | só o Intent `com.gamearena.console.START_GAME` da Game Arena |
| Verificação | — | `PendingIntent` `arena_auth` criado pela Game Arena + certificado SHA-256 (`res/values/arena_config.xml`) |
| Menus | pausa, definições, save states, cheats | nenhum; Voltar/pausa = "Terminar a partida?" |
| Atalhos | save/load, rewind, velocidade, dev | bloqueados no `EmuScreen` com `bArenaLocked` |
| Configuração | pelo utilizador | `arena.ini` por partida (`--appendconfig`) + `arena_lock` no C++ |
| Rede | ad hoc com servidores PRO públicos | ad hoc em modo relay com `127.0.0.1`; a ponte `ArenaNet` passa tudo por Socket.IO até ao servidor da Game Arena |
| Nome / pacote | PPSSPP / `org.ppsspp.ppsspp` | Arena Console / `com.gamearena.console` (o `namespace` Java continua `org.ppsspp.ppsspp`) |

## Ficheiros
- Novos: `android/src/org/ppsspp/ppsspp/Arena*.java`, `android/res/values/arena_config.xml`,
  `android/res/drawable/arena_ic_launcher_fg.xml`, `android/res/mipmap-anydpi*/arena_ic_launcher.xml`,
  `.github/workflows/arena-android.yml`, este ficheiro.
- Alterados: `android/AndroidManifest.xml`, `android/build.gradle.kts`, `android/res/values/strings.xml`,
  `android/src/org/ppsspp/ppsspp/PpssppActivity.java`, `android/jni/app-android.cpp`, `Core/Config.h`,
  `Common/System/System.h`, `UI/EmuScreen.cpp`, `README.md`.

## Rede (resumo)
O PPSSPP liga-se a `127.0.0.1:27312` (adhocctl) e `127.0.0.1:27313` (aemu_postoffice, TCP). A `ArenaNet` aceita só
ligações locais e passa cada uma como stream (`tun_open` / `tun_data` / `tun_close`) no namespace Socket.IO `/console`,
depois de `emu_join` com o `start_token` da partida. O servidor da Game Arena faz de adhocctl + postoffice só para os
2 jogadores da sala.

## Licenças
- PPSSPP: GPL 2.0 ou posterior ([LICENSE.TXT](LICENSE.TXT)).
- O PPSSPP já inclui o `ext/aemu_postoffice` (GPL 3.0). Por isso o APK, no conjunto, segue também a GPL 3.0:
  quem receber o APK tem direito ao código fonte desta versão.
- Dependências novas: `io.socket:socket.io-client` 2.1.2 e OkHttp (licença MIT / Apache 2.0, compatíveis com a GPL 3.0).
