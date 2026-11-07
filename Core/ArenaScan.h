// GA20_LAB: laboratório de mapeamento da RAM do Arena Console (SÓ LEITURA). GPL 2.0 or later.
#pragma once
#include <string>

namespace ArenaScan {
// Comandos: new W | dec | inc | same | diff | eq V | show N | get ADDR W | tag KIND ADDR W | export | reset
// Devolve sempre uma linha de JSON.
std::string Command(const std::string &line);
}
