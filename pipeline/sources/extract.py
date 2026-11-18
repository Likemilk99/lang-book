#!/usr/bin/env python3
"""Вырезает отдельные произведения из скачанных файлов Project Gutenberg (raw/pg<ID>.txt)
в исходники для prepare_book.py --original / --source.

    python3 -I pipeline/sources/extract.py

Каждое произведение начинается со своего заголовка (строка целиком) и заканчивается перед
следующим маркером `end` (строка, с которой начинается следующее произведение/иллюстрация).
В начало кладётся «# Заголовок» — prepare_book.py --original берёт из него название главы;
римские цифры I, II… внутри текста остаются разделами.
Сам текст автора не меняется (чистка сносок, _курсива_ и т.п. — в prepare_book.clean_original).
"""

from __future__ import annotations

import re
from pathlib import Path

HERE = Path(__file__).resolve().parent
RAW = HERE / "raw"

# out-файл → [(pg-файл, заголовок в тексте, заголовок главы, regex конца)]
WORKS: dict[str, list[tuple[str, str, str, str]]] = {
    "becquer-monte-animas.txt": [
        ("pg53552.txt", "EL MONTE DE LAS ÁNIMAS", "El monte de las ánimas", r"^\[Ilustración\]"),
    ],
    "becquer-leyendas.txt": [
        ("pg53552.txt", "LOS OJOS VERDES", "Los ojos verdes", r"^\[Ilustración\]"),
        ("pg53552.txt", "EL RAYO DE LUNA", "El rayo de luna", r"^\[Ilustración\]"),
        ("pg53552.txt", "MAESE PÉREZ EL ORGANISTA", "Maese Pérez el organista", r"^\[Ilustración\]"),
    ],
    "clarin-adios-cordera.txt": [
        ("pg62361.txt", "¡ADIÓS, CORDERA!", "¡Adiós, Cordera!", r"^[A-ZÁÉÍÓÚÑ¡!¿?,. ]{6,}$"),
    ],
    # Без заголовка: начинаем с первой фразы сказки (после посвящения), кончаем перед сносками.
    "coloma-raton-perez.txt": [
        ("pg36558.txt", "+Entre la muerte del rey que rabió", "", r"^\[A\] "),
    ],
}


def extract(pg: str, heading: str, title: str, end: str) -> str:
    lines = (RAW / pg).read_text(encoding="utf-8").replace("\r\n", "\n").split("\n")
    # «+текст» — начало по первой строке, которая начинается с текста; сама строка входит в текст.
    keep = heading.startswith("+")
    if keep:
        heading = heading[1:]
        starts = [i for i, ln in enumerate(lines) if ln.strip().startswith(heading)][:1]
    else:
        starts = [i for i, ln in enumerate(lines) if ln.strip() == heading]
    if not starts:
        raise SystemExit(f"{pg}: не найден заголовок {heading!r}")
    # Если заголовок встречается и в оглавлении, берём последнее вхождение отдельной строкой.
    s = starts[-1]
    end_re = re.compile(end)
    e = next((i for i in range(s + 1, len(lines)) if end_re.match(lines[i].strip())
              and lines[i].strip() != heading), len(lines))
    body = "\n".join(lines[s if keep else s + 1:e]).strip()
    words = len(body.split())
    print(f"  {title or heading}: строки {s + 1}–{e}, ~{words} слов")
    return (f"# {title}\n\n" if title else "") + body + "\n"


def main() -> None:
    for out, parts in WORKS.items():
        missing = [p[0] for p in parts if not (RAW / p[0]).exists()]
        if missing:
            print(f"- {out}: нет {', '.join(missing)} (скачайте, см. README.md)")
            continue
        print(f"- {out}")
        text = "\n\n".join(extract(*p) for p in parts)
        (HERE / out).write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
