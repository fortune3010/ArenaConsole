// GA20_WATCH: leitor automático do resultado da partida (SÓ LEITURA). GPL 2.0 or later.
#pragma once
#include <functional>
#include <string>

namespace ArenaWatch {
using Notify = std::function<void(const std::string &)>;
// disc | start A1 W1 A2 W2 HOLD ROUNDS MS | stopw
std::string Command(const std::string &line, const Notify &notify);
}
