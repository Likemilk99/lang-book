#!/usr/bin/env python3
"""Библиотека адаптированных книг уровня, которая покрывает учебную программу.

Цель: прочитав ВСЕ книги уровня L (и сделав упражнения глав), ученик встречает ≥ 90 % слов
словаря уровня (assets/curriculum/vocab_L.json) и каждую грамматическую тему уровня
(grammar_L.json) минимум в двух главах.

Как работает (планировщик покрытия):
  1. Берёт множество целевых лемм T (весь словарь уровня) и темы грамматики G (в порядке курса).
  2. Книги берутся из списка «семян» SEEDS: адаптации произведений в общественном достоянии
     и оригинальные истории о бытовых ситуациях. Для каждой книги Claude составляет план:
     персонажи, синопсисы глав и для каждой главы — ещё не покрытые слова из пула T,
     которые естественно лягут в сюжет. Грамматические темы для глав назначает Python
     (меньше всего покрытые темы, примерно в порядке курса: ранние книги — ранние темы).
  3. Каждая глава пишется отдельным запросом с обязательными словами и грамматикой,
     затем размечается (prepare_book: перевод, лемма, грамматика каждого слова).
  4. По разметке считается ФАКТИЧЕСКОЕ покрытие (леммы `l` и формы `t`); не попавшие слова
     возвращаются в пул. Грамматика главы проверяется отдельным запросом, в Chapter.grammar
     остаются только реально отработанные темы. Дальше — enrich и теги из prepare_book.
  5. Новые книги планируются, пока покрытие < 90 % или какая-то тема встречается < 2 раз
     (и пока не набрано минимальное число книг уровня).

Состояние и кэш всех запросов: pipeline/.cache/library/<L>/ — прерванный запуск
просто повторяется.

  python3 pipeline/library.py --level A1 --jobs 3
  python3 pipeline/library.py --level A1 --report      # только пересчитать coverage_A1.md
"""

from __future__ import annotations

import argparse
import concurrent.futures as cf
import hashlib
import json
import math
import re
import shutil
import sys
import threading
import time
import traceback
import unicodedata
from collections import Counter
from pathlib import Path

PIPE = Path(__file__).resolve().parent
sys.path.insert(0, str(PIPE))
import prepare_book as pb  # noqa: E402

ROOT = PIPE.parent
CUR_DIR = ROOT / "android/app/src/main/assets/curriculum"
ASSET_BOOKS = ROOT / "android/app/src/main/assets/books"
BOOKS_DIR = ROOT / "books"
CACHE = PIPE / ".cache/library"
LEVELS = ["A1", "A2", "B1", "B2"]

# Параметры уровней: глав в книге, слов в главе, сколько целевых слов на главу.
LEVEL_CFG = {
    "A1": {"words": (200, 300), "per_ch": (14, 35), "min_books": 10, "max_books": 14, "avg_ch": 6},
    "A2": {"words": (300, 450), "per_ch": (16, 45), "min_books": 10, "max_books": 16, "avg_ch": 7},
}

# ---------------------------------------------------------------- семена книг

PD = "pd"
ORIG = "original"

SEEDS: dict[str, list[dict]] = {
    "A1": [
        dict(key="fabulas", kind=PD, chapters=6, year=1781, author="Esopo / Félix María de Samaniego",
             workId="fabulas-esopo-samaniego",
             source="По мотивам басен Эзопа и Ф. М. Саманиего («Fábulas morales», 1781)",
             license="Адаптация произведений в общественном достоянии (Эзоп, VI в. до н. э.; Саманиего, ум. 1801)",
             idea="A book of classic fables, one fable per chapter, animals talk in lively short dialogues, "
                  "each chapter ends with a short moraleja. Fables: La zorra y el cuervo; La cigarra y la hormiga; "
                  "El ratón de campo y el ratón de ciudad; La liebre y la tortuga; La lechera (Samaniego); "
                  "El león y el ratón.",
             themes=["food", "nature", "description", "feelings", "home", "city"]),
        dict(key="nuevo-en-madrid", kind=ORIG, chapters=6,
             source="Оригинальная история: русский студент Иван переезжает в Мадрид учить испанский",
             idea="Original modern story. Iván, a young Russian from Novosibirsk, arrives in Madrid to study Spanish "
                  "for a year. He introduces himself (name, age, nationality, address, phone, email), meets his "
                  "flatmates in a shared piso, explores the barrio, orders at a bar, gets a Spanish SIM card for his "
                  "móvil, and has his first class at a language school with classmates from other countries.",
             themes=["identity", "home", "city", "education", "media", "food", "numbers"]),
        dict(key="caperucita", kind=PD, chapters=5, title="Caperucita Roja", year=1697, author="Charles Perrault / Hermanos Grimm",
             workId="caperucita-roja",
             source="По мотивам сказки «Красная Шапочка» (Ш. Перро, 1697; братья Гримм, 1812)",
             license="Адаптация произведения в общественном достоянии (Перро, ум. 1703; братья Гримм, ум. 1859/1863)",
             idea="Little Red Riding Hood retold with lots of dialogue: Caperucita, her mother, the sick grandmother "
                  "who lives in the forest, the cunning wolf, and the woodcutter. The famous '¡Qué ojos tan grandes "
                  "tienes!' scene with body parts. Food in the basket, the path through the forest, a happy ending.",
             themes=["family", "body-health", "food", "nature", "description", "feelings"]),
        dict(key="lucia-busca-trabajo", kind=ORIG, chapters=6,
             source="Оригинальная история: Лусия ищет работу в Валенсии",
             idea="Original modern story. Lucía, 28, lives in Valencia and is unemployed (está en paro). She looks for "
                  "a job online, calls companies, writes emails, goes to an interview at an office (times, days, "
                  "the metro), gets the job and has her first week: colleagues, the boss, lunch break, a funny "
                  "misunderstanding on the phone. Daily routine with reflexive verbs, the time, dates.",
             themes=["work", "time", "media", "travel", "numbers", "city"]),
        dict(key="traje-emperador", kind=PD, chapters=5, year=1837, author="Hans Christian Andersen",
             workId="traje-nuevo-emperador",
             source="По мотивам сказки Х. К. Андерсена «Новое платье короля» (1837)",
             license="Адаптация произведения в общественном достоянии (Андерсен, ум. 1875)",
             idea="The Emperor's New Clothes: a vain emperor who loves clothes, two swindlers who pretend to weave "
                  "magic cloth (prices, gold, money), ministers who lie, the parade in the city square and the child "
                  "who says the truth. Lots of clothes, colours, prices and opinions.",
             themes=["shopping", "description", "numbers", "feelings", "city"]),
        dict(key="ali-baba", kind=PD, chapters=6, year=1717, author="Las mil y una noches",
             workId="ali-baba",
             source="По мотивам сказки «Али-Баба и сорок разбойников» («Тысяча и одна ночь», фр. пер. А. Галлана, 1717)",
             license="Адаптация произведения в общественном достоянии («Тысяча и одна ночь»; А. Галлан, ум. 1715)",
             idea="Ali Baba and the Forty Thieves: poor Ali Baba, his rich greedy brother Kasim, the cave and "
                  "'¡Ábrete, Sésamo!', counting gold coins, the clever servant Morgiana, the market, the house, "
                  "jars of oil, the thieves' plan and a happy ending.",
             themes=["numbers", "family", "home", "food", "shopping", "services"]),
        dict(key="que-semana-marta", kind=ORIG, chapters=6,
             source="Оригинальная история: неделя болезни Марты — врач, аптека, скорая",
             idea="Original modern story. Marta, a nurse's sister and a busy teacher in Zaragoza, has a terrible week: "
                  "she catches a cold, has a headache and fever, goes to the doctor (body parts, symptoms), the "
                  "pharmacy, then her little brother breaks his arm playing football and they go to urgencias by "
                  "ambulance. Feelings, health advice (tener que, hay que), a happy weekend at the end.",
             themes=["body-health", "feelings", "services", "family", "time"]),
        dict(key="cenicienta", kind=PD, chapters=6, year=1697, author="Charles Perrault",
             workId="cenicienta",
             source="По мотивам сказки Ш. Перро «Золушка» (1697)",
             license="Адаптация произведения в общественном достоянии (Перро, ум. 1703)",
             idea="Cinderella: the stepmother and two stepsisters, housework (cleaning the kitchen, the rooms), "
                  "the invitation to the prince's party, the fairy godmother, the dress and shoes, the pumpkin coach, "
                  "midnight (a las doce en punto), the lost shoe and the search through the kingdom.",
             themes=["family", "home", "shopping", "time", "leisure", "description"]),
        dict(key="fin-de-semana-en-sevilla", kind=ORIG, chapters=6,
             source="Оригинальная история: два друга едут на выходные в Севилью",
             idea="Original modern story. Two friends, Pablo and Sofía, go from Madrid to Sevilla for a long weekend. "
                  "They buy train tickets at the station (ida y vuelta, times, platform), check into a small hotel, "
                  "ask for directions (todo recto, a la derecha, cerca de), visit the cathedral area, a park, a "
                  "flamenco show, it is very hot, then a storm; they lose the train back and take a bus.",
             themes=["travel", "city", "nature", "time", "leisure", "numbers"]),
        dict(key="historias-del-barrio", kind=ORIG, chapters=6,
             source="Оригинальная история: короткие сценки из жизни одного мадридского района",
             idea="Original modern collection: each chapter is a short independent scene in the same neighbourhood "
                  "of Madrid (the bank and the cash machine, the post office, the clothes shop, the bakery, a "
                  "neighbour who needs help, the bus stop). Recurring characters: the kiosk owner Paco and his "
                  "neighbour Elena.",
             themes=["services", "shopping", "city", "society", "media", "description"]),
        dict(key="musicos-de-bremen", kind=PD, chapters=5, year=1819, author="Hermanos Grimm",
             workId="musicos-de-bremen",
             source="По мотивам сказки братьев Гримм «Бременские музыканты» (1819)",
             license="Адаптация произведения в общественном достоянии (братья Гримм, ум. 1859/1863)",
             idea="The Town Musicians of Bremen: an old donkey, a dog, a cat and a rooster, each one unwanted by "
                  "their owners, decide to go to the city to be musicians. They walk, sing, get hungry and tired, "
                  "find a house in the forest with robbers eating dinner, scare them away and stay to live there.",
             themes=["leisure", "nature", "home", "food", "feelings", "time"]),
        dict(key="don-quijote", kind=PD, chapters=6, year=1605, author="Miguel de Cervantes",
             workId="don-quijote",
             source="По мотивам романа М. де Сервантеса «Дон Кихот» (1605)",
             license="Адаптация произведения в общественном достоянии (Сервантес, ум. 1616)",
             idea="Very simple retelling of famous episodes of Don Quijote: Alonso Quijano reads too many books, "
                  "becomes a knight, his horse Rocinante, the inn that he thinks is a castle, Sancho Panza joins him "
                  "(the promise of an island), the windmills that are giants, the sheep that are an army, and the "
                  "return home. Funny dialogues between Don Quijote and Sancho.",
             themes=["description", "feelings", "nature", "food", "society", "travel"]),
        dict(key="la-familia-garcia", kind=ORIG, chapters=6,
             source="Оригинальная история: обычная неделя семьи Гарсия",
             idea="Original modern story. The García family in Bilbao: parents, three children, the grandmother and "
                  "a dog. School, homework, sports and hobbies, a birthday party, a Sunday excursion to the mountains, "
                  "plans for the summer holidays, small family conflicts solved with humour.",
             themes=["family", "leisure", "education", "home", "food", "nature", "time"]),
        dict(key="pequenas-historias", kind=ORIG, chapters=6,
             source="Оригинальная история: маленькие истории о людях одного города",
             idea="Original modern collection of short independent stories (one per chapter) about different people "
                  "in Granada: a student, a cook, an old man and his cat, a taxi driver, a girl who learns to dance, "
                  "a couple who moves house. Warm, a little funny.",
             themes=[]),
    ],
    "A2": [
        dict(key="lazarillo", kind=PD, chapters=7, year=1554, author="Anónimo",
             workId="lazarillo-de-tormes",
             source="По мотивам повести «Жизнь Ласарильо с Тормеса» (аноним, 1554)",
             license="Адаптация произведения в общественном достоянии (аноним, 1554)",
             idea="Lazarillo de Tormes told by Lázaro himself in the past: his childhood by the river Tormes, the "
                  "blind man (the grapes, the sausage, the stone bull), the stingy priest and the bread chest, the "
                  "poor proud squire in Toledo, and finally a job and a wife. Hunger, tricks, humour.",
             themes=["food", "family", "feelings", "city", "work"]),
        dict(key="piso-en-valencia", kind=ORIG, chapters=7,
             source="Оригинальная история: Ольга снимает квартиру в Валенсии",
             idea="Original modern story. Olga, a Russian graphic designer, moves to Valencia. She looks for a flat "
                  "online, visits flats with an estate agent, signs the contract, opens a bank account, moves in "
                  "(furniture, appliances that break, the heating, the neighbours, household chores), gets an "
                  "appointment (cita previa) for her documents and finally throws a small housewarming party.",
             themes=["home", "services", "identity", "shopping", "feelings"]),
        dict(key="vuelta-al-mundo", kind=PD, chapters=8, year=1872, author="Jules Verne",
             workId="vuelta-al-mundo-80-dias",
             source="По мотивам романа Ж. Верна «Вокруг света за 80 дней» (1872)",
             license="Адаптация произведения в общественном достоянии (Верн, ум. 1905)",
             idea="Around the World in Eighty Days: Phileas Fogg and his bet at the Reform Club, his servant "
                  "Passepartout, trains, steamships, an elephant in India, saving Aouda, detective Fix who thinks "
                  "Fogg is a thief, delays, timetables and the surprising last day.",
             themes=["travel", "time", "numbers", "nature", "feelings"]),
        dict(key="la-entrevista", kind=ORIG, chapters=7,
             source="Оригинальная история: Хавьер ищет первую работу после университета",
             idea="Original modern story. Javier finishes university in Salamanca and looks for his first job: CV, "
                  "online applications, a video interview with technical problems, a real interview in Madrid, the "
                  "contract, colleagues, the laptop and passwords, a difficult client, and a computer course.",
             themes=["work", "media", "education", "feelings", "time"]),
        dict(key="liga-pelirrojos", kind=PD, chapters=7, year=1891, author="Arthur Conan Doyle",
             workId="liga-de-los-pelirrojos",
             source="По мотивам рассказа А. Конан Дойла «Союз рыжих» (1891)",
             license="Адаптация произведения в общественном достоянии (Конан Дойл, ум. 1930)",
             idea="The Red-Headed League: Mr Wilson, a pawnbroker with red hair, tells Holmes and Watson about a "
                  "strange job copying the encyclopaedia for good money, the advertisement, his assistant who "
                  "loves photography, the sudden end of the League, Holmes's investigation and the bank robbery "
                  "stopped at night in the cellar.",
             themes=["work", "services", "description", "city", "society"]),
        dict(key="dolor-de-muelas", kind=ORIG, chapters=7,
             source="Оригинальная история: зубная боль, врачи и спортзал",
             idea="Original modern story. Carmen, a radio journalist in Málaga, has toothache before an important "
                  "programme, calls for a dentist appointment, goes to the doctor for a back problem, buys medicine "
                  "at the pharmacy, decides to change her life: joins a gym, eats healthily, starts running and "
                  "watches a football match with friends.",
             themes=["body-health", "leisure", "food", "feelings", "media"]),
        dict(key="isla-del-tesoro", kind=PD, chapters=8, year=1883, author="Robert Louis Stevenson",
             workId="isla-del-tesoro",
             source="По мотивам романа Р. Л. Стивенсона «Остров сокровищ» (1883)",
             license="Адаптация произведения в общественном достоянии (Стивенсон, ум. 1894)",
             idea="Treasure Island told by Jim Hawkins in the past: the old pirate at the inn, the map, the doctor "
                  "and the squire, the ship Hispaniola, Long John Silver, the apple barrel, the island, Ben Gunn, "
                  "the fight for the stockade and the treasure.",
             themes=["nature", "travel", "feelings", "description", "numbers"]),
        dict(key="rinconete-y-cortadillo", kind=PD, chapters=6, year=1613, author="Miguel de Cervantes",
             workId="rinconete-y-cortadillo",
             source="По мотивам новеллы М. де Сервантеса «Ринконете и Кортадильо» («Назидательные новеллы», 1613)",
             license="Адаптация произведения в общественном достоянии (Сервантес, ум. 1616)",
             idea="Rinconete y Cortadillo: two runaway boys meet at an inn, play cards, travel to Sevilla, work as "
                  "porters at the market, steal a purse, and are taken to the house of Monipodio, the boss of the "
                  "city's thieves with his strange rules, 'laws' and justice.",
             themes=["city", "shopping", "society", "food", "work"]),
        dict(key="pasaporte-perdido", kind=ORIG, chapters=7,
             source="Оригинальная история: потерянный паспорт в аэропорту Барахас",
             idea="Original modern story. Dmitri and his Spanish girlfriend Laura fly from Madrid to Tenerife. At "
                  "the airport: check-in, security control, boarding pass, a delay; then Dmitri loses his passport "
                  "and residence permit. Police station report, lost-property office, consulate appointment, "
                  "forms with personal data, and a surprise ending on the island.",
             themes=["travel", "identity", "services", "society", "nature"]),
        dict(key="ojos-verdes", kind=PD, chapters=6, year=1861, author="Gustavo Adolfo Bécquer",
             workId="los-ojos-verdes",
             source="По мотивам легенды Г. А. Беккера «Зелёные глаза» (1861)",
             license="Адаптация произведения в общественном достоянии (Беккер, ум. 1870)",
             idea="Los ojos verdes: young noble Fernando hunts a deer near the Fountain of the Poplars, his old "
                  "hunter Íñigo warns him about the spirit of the water; Fernando becomes sad and obsessed, returns "
                  "to the fountain, meets the woman with green eyes and follows her into the lake. Romantic, mysterious.",
             themes=["nature", "feelings", "description", "family"]),
        dict(key="simbad", kind=PD, chapters=7, year=1717, author="Las mil y una noches",
             workId="simbad-el-marino",
             source="По мотивам сказок о Синдбаде-мореходе («Тысяча и одна ночь», фр. пер. А. Галлана, 1717)",
             license="Адаптация произведения в общественном достоянии («Тысяча и одна ночь»; А. Галлан, ум. 1715)",
             idea="Sinbad the Sailor tells Sinbad the Porter about his voyages: the island that was a whale, the "
                  "valley of diamonds and the giant roc bird, the giant cyclops, shipwrecks, trading in markets, "
                  "and returning home rich.",
             themes=["travel", "nature", "shopping", "numbers", "feelings"]),
        dict(key="elecciones-en-el-pueblo", kind=ORIG, chapters=7,
             source="Оригинальная история: выборы мэра в маленькой деревне",
             idea="Original modern story. In a small village in Asturias, the old mayor retires and two candidates "
                  "compete in the elections: a young woman who wants to save the river and the environment, and a "
                  "businessman who promises a shopping centre. Debates in the bar, the local newspaper, a problem "
                  "with the law, the festivities, the vote and the result.",
             themes=["society", "nature", "media", "work", "feelings"]),
        dict(key="quijote-y-sancho", kind=PD, chapters=7, year=1615, author="Miguel de Cervantes",
             workId="don-quijote",
             source="По мотивам романа М. де Сервантеса «Дон Кихот» (1605–1615)",
             license="Адаптация произведения в общественном достоянии (Сервантес, ум. 1616)",
             idea="Later episodes of Don Quijote: the barber's basin as the helmet of Mambrino, the galley slaves, "
                  "Sierra Morena, the duke and duchess, Clavileño the wooden horse, Sancho as governor of the island "
                  "Barataria judging cases wisely, and the Knight of the White Moon.",
             themes=["society", "feelings", "description", "food", "travel"]),
        dict(key="escandalo-en-bohemia", kind=PD, chapters=6, year=1891, author="Arthur Conan Doyle",
             workId="escandalo-en-bohemia",
             source="По мотивам рассказа А. Конан Дойла «Скандал в Богемии» (1891)",
             license="Адаптация произведения в общественном достоянии (Конан Дойл, ум. 1930)",
             idea="A Scandal in Bohemia: the King of Bohemia asks Holmes to recover a photograph from Irene Adler, "
                  "Holmes in disguise, the wedding, the fake fire, the hidden photo and Irene's clever escape.",
             themes=["description", "home", "feelings", "city", "media"]),
        dict(key="un-ano-en-salamanca", kind=ORIG, chapters=7,
             source="Оригинальная история: учебный год в Саламанке — праздники и времена года",
             idea="Original modern story. An Erasmus year in Salamanca told month by month: enrolment at university, "
                  "exams and grades, autumn rain, Christmas and New Year's Eve, los Reyes Magos, Semana Santa, a "
                  "spring trip, the summer and saying goodbye.",
             themes=["education", "time", "nature", "leisure", "family"]),
        dict(key="historias-de-la-ciudad", kind=ORIG, chapters=7,
             source="Оригинальная история: короткие истории жителей Барселоны",
             idea="Original modern collection of independent short stories (one per chapter) about people in "
                  "Barcelona: a shop assistant, a retired policeman, a cook, a neighbour who lost his dog, two old "
                  "friends who meet again, a family that changes house, a bad day at the bank.",
             themes=[]),
    ],
}

# ---------------------------------------------------------------- лимит параллельных claude

_SEM = threading.Semaphore(3)
_orig_claude_json = pb.claude_json


def _limited_claude_json(*a, **kw):
    with _SEM:
        return _orig_claude_json(*a, **kw)


pb.claude_json = _limited_claude_json  # enrich_book / tag_book тоже идут через лимит


def log(*a) -> None:
    print(time.strftime("%H:%M:%S"), *a, flush=True)


# ---------------------------------------------------------------- сравнение слов

_PUNCT = re.compile(r"[¿?¡!.,;:«»\"“”()\[\]—–]")
REFLEXIVE = {"me", "te", "se", "nos", "os"}


def low(s: str) -> str:
    return unicodedata.normalize("NFC", s or "").lower().strip()


def entry_parts(lemma: str) -> list[list[str]]:
    """'ni… ni' → [['ni'], ['ni']]; 'al lado de' → [['al', 'lado', 'de']]."""
    parts = [p for p in re.split(r"…|\.\.\.", low(lemma))]
    return [w for w in (_PUNCT.sub(" ", p).split() for p in parts) if w]


def token_keys(tok: dict, prev: list[str]) -> set[str]:
    t, l = low(tok.get("t", "")), low(tok.get("l", "") or "")
    keys = {t, l} - {""}
    for k in list(keys):
        if " " in k:  # иногда лемма — словосочетание
            keys.update(k.split())
    if t.endswith("es") and len(t) > 4:
        keys.add(t[:-2])
    if t.endswith("s") and len(t) > 3:
        keys.add(t[:-1])
    if t == "del":
        keys |= {"de", "el"}
    if t == "al":
        keys |= {"a", "el"}
    if l and not l.endswith("se") and l.endswith(("ar", "er", "ir")) and prev and prev[-1] in REFLEXIVE:
        keys.add(l + "se")
    if l.endswith("se") and len(l) > 4:
        keys.add(l[:-2])
    return keys


def is_word(tok: dict) -> bool:
    return any(c.isalpha() for c in tok.get("t", ""))


def sentence_keys(s: dict) -> list[set[str]]:
    out, prev = [], []
    for tok in s["tokens"]:
        if not is_word(tok):
            continue
        tw = low(tok["t"]).split()
        if len(tw) > 1:  # разметчик иногда склеивает выражение в один токен: «por favor»
            lw = low(tok.get("l") or "").split()
            for i, x in enumerate(tw):
                out.append({x} | ({lw[i]} if len(lw) == len(tw) else set()))
            prev.extend(tw)
            continue
        out.append(token_keys(tok, prev))
        prev.append(low(tok["t"]))
    return out


def chapter_hits(ch: dict, targets: dict[str, list[list[str]]]) -> Counter:
    """Сколько раз каждая целевая лемма встречается в главе."""
    hits: Counter = Counter()
    singles = {k: p[0][0] for k, p in targets.items() if len(p) == 1 and len(p[0]) == 1}
    by_word: dict[str, list[str]] = {}
    for k, w in singles.items():
        by_word.setdefault(w, []).append(k)
    multis = {k: p for k, p in targets.items() if k not in singles}
    for p in ch["paragraphs"]:
        for s in p["sentences"]:
            seq = sentence_keys(s)
            for keys in seq:
                for kk in keys:
                    for k in by_word.get(kk, ()):
                        hits[k] += 1
            for k, parts in multis.items():
                # «tener (mucha) hambre», «hace (mucho) calor»: у глагольных выражений
                # между словами допускаем до двух вставных слов.
                first = parts[0][0]
                gap = 2 if len(first) > 2 and first.endswith(("ar", "er", "ir")) else 0
                flat = [w for part in parts for w in part]
                if len(parts) == 1 and gap:
                    parts_ok = _match_gapped(seq, flat, gap)
                else:
                    parts_ok = _match_parts(seq, parts)
                if parts_ok:
                    hits[k] += 1
    return hits


def _match_parts(seq: list[set[str]], parts: list[list[str]]) -> bool:
    pos = 0
    for part in parts:
        found = -1
        for i in range(pos, len(seq) - len(part) + 1):
            if all(part[j] in seq[i + j] for j in range(len(part))):
                found = i
                break
        if found < 0:
            return False
        pos = found + len(part)
    return True


def _match_gapped(seq: list[set[str]], words: list[str], gap: int) -> bool:
    for i in range(len(seq)):
        if words[0] not in seq[i]:
            continue
        pos, ok = i, True
        for w in words[1:]:
            nxt = next((j for j in range(pos + 1, min(len(seq), pos + 2 + gap)) if w in seq[j]), -1)
            if nxt < 0:
                ok = False
                break
            pos = nxt
        if ok:
            return True
    return False


def chapter_text(ch: dict) -> str:
    return "\n".join(" ".join(pb.sentence_text(s["tokens"]) for s in p["sentences"]) for p in ch["paragraphs"])


def word_count(ch: dict) -> int:
    return sum(1 for p in ch["paragraphs"] for s in p["sentences"] for t in s["tokens"] if is_word(t))


# ---------------------------------------------------------------- схемы и промпты

PLAN_SCHEMA = {
    "type": "object",
    "properties": {
        "title": {"type": "string", "description": "Book title in Spanish"},
        "premise": {"type": "string", "description": "2-4 sentences, English"},
        "characters": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {"name": {"type": "string"}, "gender": {"type": "string", "enum": ["m", "f"]},
                               "role": {"type": "string"}},
                "required": ["name", "gender", "role"],
                "additionalProperties": False,
            },
        },
        "chapters": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "title": {"type": "string", "description": "Chapter title in Spanish"},
                    "synopsis": {"type": "string", "description": "English, 3-6 sentences: scenes, who talks, what happens"},
                    "words": {"type": "array", "items": {"type": "string"},
                              "description": "Target words for this chapter, copied exactly from the POOL"},
                },
                "required": ["title", "synopsis", "words"],
                "additionalProperties": False,
            },
        },
    },
    "required": ["title", "premise", "characters", "chapters"],
    "additionalProperties": False,
}

PLAN_SYSTEM = """You are the editor of a library of graded Spanish readers (Peninsular Spanish) for a native
Russian speaker. The whole library of level {level} must teach the complete {level} vocabulary list: every
book gets a share of words that are not covered yet, and each chapter must use its words naturally.

You plan ONE book. Return:
- title: an attractive Spanish title (level {level}).
- premise: 2-4 sentences in English.
- characters: the main characters (Spanish names for modern Spanish stories; keep the original names of a
  classic work, in their usual Spanish form).
- chapters: exactly {nch} chapters, each with a Spanish title, a synopsis (English, 3-6 sentences: concrete
  scenes and the conversations in them; a coherent plot across chapters with tension or humour and a
  satisfying ending) and `words`: about {per_ch} words chosen from the POOL for this chapter.

Choosing words:
- Copy the pool entries EXACTLY as written (the part before the parenthesis), e.g. "cuánto cuesta", "lavarse".
- Pick words that fit the chapter's scenes so they can be used naturally in narration or dialogue; plan the
  scenes so that many pool words fit (that is the point of the library). Prefer the themes listed as a good
  fit for this book, but take any pool word that fits.
- Each word in at most one chapter. Do not pick words that would make the story absurd.
- NO ANACHRONISMS: if the book is set in the past (a classic work, a fable, a fairy tale, a historical
  setting), NEVER pick words for things that do not exist in that world (modern technology, phones, TV,
  radio, internet, cars, buses, planes, airports, high-speed trains, supermarkets, euros, banks' cash
  machines, modern police, gas cookers…). Leave them in the pool for the modern stories. Never modernise a
  classic — it is fine to pick fewer words than asked.
- Each chapter also has assigned GRAMMAR POINTS: plan scenes where they occur naturally.
{level_guide}"""

GEN_SYSTEM = """You write chapters of graded Spanish readers for a native Russian speaker.
Target level: {level}. {level_guide}
{spain}
{conversational}

This reader belongs to a library designed to teach the whole {level} syllabus, so each chapter must use a
given list of TARGET WORDS and showcase given GRAMMAR POINTS — but it must read as a real, engaging story,
never as a vocabulary exercise.

Rules:
- Use EVERY target word at least once (any inflected form is fine: plural, feminine, any verb tense/person;
  multi-word expressions as a whole). Use the most useful ones twice. Weave them into the plot and the
  dialogue naturally; never list objects just to name them. Only if a word really cannot fit, skip it.
- Grammar points: include at least 3 clear, natural examples of each, shown ONLY through natural usage
  (e.g. noun gender/plural: many nouns of both genders with articles and agreeing adjectives). Characters
  must not discuss or explain grammar or the language itself (unless the scene is a language class).
- Apart from the target words use only very common words of level {level} and below; avoid rare or
  advanced words (character names and transparent cognates are fine). Short, clear sentences.
- Write numbers, prices, times and dates in words (veinte euros, a las ocho y media), never digits.
- Length: {wmin}-{wmax} words.
- Keep continuity with the book plan and the previous chapters (names, facts, tone).
- For an adaptation of a classic work keep its plot, characters, setting and spirit, retold simply. Never
  introduce anachronisms (phones, TV, cars, airports, supermarkets, euros, gas cookers… in a fairy tale or a
  historical story): if a target word does not belong to that world, skip it.
- Format: paragraphs as lists of sentences. Each line of dialogue is its own paragraph and starts with an
  em dash (—); keep a speech tag in the same sentence as the line ("—Hola —dice Ana.").
- Give the chapter a short Spanish title and its Russian translation."""

ANNOTATE_EXTRA = """
Extra rules for the lemma (l):
- Pronominal/reflexive verb uses (me llamo, se levanta, nos sentamos, ducharse) → l is the infinitive WITH -se
  (llamarse, levantarse, sentarse, ducharse). Non-reflexive uses keep the plain infinitive (llamar).
- Articles: l = el / un for all forms (la, los, las → el; una, unos, unas → un).
- Feminine nouns that are separate words (hermana, abuela, hija, profesora) → l is the feminine singular
  noun itself (hermana), not the masculine.
- Numbers written in words → l is the number word itself (veinte, cien)."""

GRAMMAR_SCHEMA = {
    "type": "object",
    "properties": {
        "chapters": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "chapter": {"type": "integer"},
                    "topics": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "properties": {
                                "id": {"type": "string"},
                                "practised": {"type": "boolean"},
                                "examples": {"type": "array", "items": {"type": "string"}},
                            },
                            "required": ["id", "practised", "examples"],
                            "additionalProperties": False,
                        },
                    },
                },
                "required": ["chapter", "topics"],
                "additionalProperties": False,
            },
        }
    },
    "required": ["chapters"],
    "additionalProperties": False,
}

GRAMMAR_SYSTEM = """You check a graded Spanish reader against a grammar syllabus. For each chapter you get the
grammar points that were supposed to be practised in it. For each point decide `practised`: true only if the
chapter text contains at least 2 clear, correct examples of exactly that point (quote up to 3 of them,
verbatim, in `examples`). Be strict about the specific point (e.g. "estar + gerundio" needs real
estar + gerundio forms; "verbos reflexivos" needs reflexive verbs; "demostrativos" needs este/ese/aquel
forms), but do not require that the chapter explains anything — natural usage is enough."""


# ---------------------------------------------------------------- библиотека уровня

class Library:
    def __init__(self, level: str, args):
        self.L = level
        self.args = args
        self.cfg = LEVEL_CFG[level]
        self.dir = CACHE / level
        self.dir.mkdir(parents=True, exist_ok=True)
        self.state_path = self.dir / "state.json"

        vocab = json.loads((CUR_DIR / f"vocab_{level}.json").read_text(encoding="utf-8"))
        self.themes = [(t["id"], t["title"]) for t in vocab["themes"]]
        self.vocab: dict[str, dict] = {}
        for t in vocab["themes"]:
            for w in t["words"]:
                self.vocab[low(w["l"])] = {"theme": t["id"], "ru": w["ru"], "pos": w.get("pos", "")}
        self.T = list(self.vocab)
        self.parts = {k: entry_parts(k) for k in self.T}

        # Слова, которые можно использовать свободно: словари этого уровня и ниже.
        self.allowed: set[str] = set()
        for lv in LEVELS[: LEVELS.index(level) + 1]:
            p = CUR_DIR / f"vocab_{lv}.json"
            if p.exists():
                for t in json.loads(p.read_text(encoding="utf-8"))["themes"]:
                    for w in t["words"]:
                        for part in entry_parts(w["l"]):
                            self.allowed.update(part)
        grammar = json.loads((CUR_DIR / f"grammar_{level}.json").read_text(encoding="utf-8"))
        self.topics = [{"id": t["id"], "title": t["title"], "titleEs": t.get("titleEs", ""),
                        "summary": t["summary"]} for t in grammar["topics"]]
        self.topic_idx = {t["id"]: i for i, t in enumerate(self.topics)}

        self.state = json.loads(self.state_path.read_text(encoding="utf-8")) if self.state_path.exists() \
            else {"level": level, "books": []}
        self.lock = threading.RLock()
        self.plan_lock = threading.Lock()
        self.active: set[str] = set()
        self.failed: set[str] = set()

    # ------------------------------------------------ состояние

    def save_state(self) -> None:
        with self.lock:
            tmp = self.state_path.with_suffix(".tmp")
            tmp.write_text(json.dumps(self.state, ensure_ascii=False, indent=1), encoding="utf-8")
            tmp.replace(self.state_path)

    def work(self, book_id: str) -> Path:
        return self.dir / book_id

    def load(self, path: Path):
        return json.loads(path.read_text(encoding="utf-8")) if path.exists() else None

    def seed(self, key: str) -> dict:
        return next(s for s in SEEDS[self.L] if s["key"] == key)

    def snapshot(self) -> dict:
        """Покрытие по файлам на диске: covered (первое появление), частоты, резерв, грамматика."""
        covered: dict[str, str] = {}
        freq: Counter = Counter()
        reserved: dict[str, str] = {}
        g_conf: Counter = Counter()   # подтверждённые главы на тему
        g_exp: Counter = Counter()    # ожидаемые (назначенные, ещё не проверенные)
        chapters_done = 0
        per_book: dict[str, dict] = {}
        targets = {k: self.parts[k] for k in self.T}
        for b in self.state["books"]:
            bid = b["id"]
            w = self.work(bid)
            plan = self.load(w / "plan.json")
            gram = self.load(w / "grammar.json")
            info = {"new": [], "chapters": 0, "words": 0}
            per_book[bid] = info
            if plan is None:
                continue
            for ci, pch in enumerate(plan["chapters"]):
                ch = self.load(w / f"chap_{ci:02d}.json")
                if ch is None:
                    for x in pch["words"]:
                        if x not in covered:
                            reserved.setdefault(x, bid)
                    for t in pch["grammar"]:
                        g_exp[t] += 1
                    continue
                chapters_done += 1
                info["chapters"] += 1
                info["words"] += word_count(ch)
                hits = chapter_hits(ch, targets)
                for x, n in hits.items():
                    freq[x] += n
                    if x not in covered:
                        covered[x] = bid
                        info["new"].append(x)
                if gram is not None:
                    for t in gram.get(str(ci), []):
                        g_conf[t] += 1
                else:
                    for t in pch["grammar"]:
                        g_exp[t] += 1
        reserved = {x: b for x, b in reserved.items() if x not in covered}
        return {"covered": covered, "freq": freq, "reserved": reserved, "g_conf": g_conf,
                "g_exp": g_exp, "chapters_done": chapters_done, "per_book": per_book}

    # ------------------------------------------------ выбор следующей книги

    def next_job(self):
        with self.lock:
            for b in self.state["books"]:
                if b["status"] != "done" and b["id"] not in self.active and b["id"] not in self.failed:
                    self.active.add(b["id"])
                    return b
            # Кто-то ещё планирует книгу — подождать, чтобы видеть её резерв.
            if any(not (self.work(b["id"]) / "plan.json").exists() for b in self.state["books"]
                   if b["id"] in self.active):
                return "wait"
            snap = self.snapshot()
            n = len(self.state["books"])
            nT = len(self.T)
            exp_cov = len(snap["covered"]) + 0.75 * len(snap["reserved"])
            g_total = snap["g_conf"] + snap["g_exp"]
            g_short = [t["id"] for t in self.topics if g_total[t["id"]] < 2]
            need = n < self.args.min_books or exp_cov < 0.9 * nT or g_short
            if not need:
                return "wait" if self.active else None
            if n >= self.args.max_books:
                return "wait" if self.active else None
            used = {b["seed"] for b in self.state["books"]}
            free = [s for s in SEEDS[self.L] if s["key"] not in used]
            if not free:
                return "wait" if self.active else None
            seed = free[0]
            entry = {"id": f"{self.L.lower()}-{n + 1:02d}-{seed['key']}", "seed": seed["key"], "status": "new"}
            self.state["books"].append(entry)
            self.save_state()
            self.active.add(entry["id"])
            log(f"[{self.L}] новая книга {entry['id']}: покрыто {len(snap['covered'])}/{nT}, "
                f"в резерве {len(snap['reserved'])}, темы < 2: {len(g_short)}")
            return entry

    def worker(self) -> None:
        while True:
            job = self.next_job()
            if job is None:
                return
            if job == "wait":
                time.sleep(15)
                continue
            try:
                self.build_book(job)
            except Exception as e:  # noqa: BLE001
                log(f"!!! {job['id']}: {type(e).__name__}: {e}")
                traceback.print_exc()
                with self.lock:
                    self.failed.add(job["id"])
            finally:
                with self.lock:
                    self.active.discard(job["id"])

    def run(self) -> None:
        with cf.ThreadPoolExecutor(self.args.jobs) as ex:
            futs = [ex.submit(self.worker) for _ in range(self.args.jobs)]
            for f in futs:
                f.result()
        self.report()

    # ------------------------------------------------ план книги

    def assign_grammar(self, book_no: int, nch: int, g_total: Counter) -> list[list[str]]:
        frontier = min(len(self.topics), 6 + 3 * book_no)
        counts = Counter(g_total)
        out = []
        for _ in range(nch):
            cand = sorted(self.topics[:frontier], key=lambda t: (counts[t["id"]], self.topic_idx[t["id"]]))
            pick = [t["id"] for t in cand[:2]]
            for t in pick:
                counts[t] += 1
            out.append(sorted(pick, key=self.topic_idx.get))
        return out

    def plan_book(self, entry: dict) -> dict:
        seed = self.seed(entry["seed"])
        book_no = next(i for i, b in enumerate(self.state["books"]) if b["id"] == entry["id"])
        with self.lock:
            snap = self.snapshot()
        nch = seed["chapters"]
        taken = set(snap["covered"]) | set(snap["reserved"])
        pool = [w for w in self.T if w not in taken]
        if snap["chapters_done"] < 6:
            pool = [w for w in pool if self.vocab[w]["theme"] != "basic"]
        books_left = max(1, self.args.min_books - book_no)
        ch_left = books_left * self.cfg["avg_ch"]
        lo, hi = self.cfg["per_ch"]
        per_ch = max(lo, min(hi, math.ceil(len(pool) * 0.9 / ch_left)))
        per_ch = max(1, min(per_ch, math.ceil(len(pool) / nch))) if pool else 0
        # Пул почти исчерпан — добираем до нижней планки словами, которые встретились лишь раз
        # (повторение важнее новых слов в последних книгах уровня).
        review: list[str] = []
        if per_ch < lo:
            review = [x for x in self.T if x in snap["covered"] and snap["freq"][x] <= 1
                      and self.vocab[x]["theme"] != "basic"]
            per_ch = lo
        grammar = self.assign_grammar(book_no, nch, snap["g_conf"] + snap["g_exp"])

        good = set(seed.get("themes") or [])
        lines = []
        for tid, title in self.themes:
            ws = [w for w in pool if self.vocab[w]["theme"] == tid]
            if ws:
                mark = " (good fit for this book)" if tid in good else ""
                lines.append(f"## {tid} — {title}{mark}\n" + ", ".join(f"{w} ({self.vocab[w]['ru']})" for w in ws))
        gtxt = "\n".join(
            f"Chapter {ci + 1}: " + "; ".join(f"{self.topics[self.topic_idx[g]]['titleEs']}" for g in gs)
            for ci, gs in enumerate(grammar))
        kind = ("Adaptation of a public-domain classic: " + seed["source"]) if seed["kind"] == PD else "Original story"
        prompt = (
            f"BOOK IDEA ({kind}):\n{seed['idea']}\n\n"
            f"Chapters: {nch}, about {sum(self.cfg['words']) // 2} words each. Target words per chapter: about {per_ch}.\n\n"
            f"GRAMMAR POINTS per chapter:\n{gtxt}\n\n"
            f"POOL of not yet covered {self.L} words ({len(pool)}), by theme:\n\n" + "\n\n".join(lines)
        )
        if review:
            prompt += (
                "\n\nThe pool is almost exhausted. First use the pool words that fit; then complete each chapter "
                "up to the target with REVIEW words (already met only once in the library, they need repetition):\n"
                + ", ".join(f"{x} ({self.vocab[x]['ru']})" for x in review))
        system = PLAN_SYSTEM.format(level=self.L, nch=nch, per_ch=per_ch,
                                    level_guide="Level guide: " + pb.LEVEL_GUIDE[self.L])
        log(f"{entry['id']}: планирую ({nch} гл., ~{per_ch} слов/гл., пул {len(pool)})")
        res = pb.claude_json(prompt, system, PLAN_SCHEMA, self.args.model, "medium")

        pool_set = set(pool) | set(review)
        seen: set[str] = set()
        chapters = []
        for ci in range(nch):
            pch = res["chapters"][ci] if ci < len(res["chapters"]) else {"title": f"Capítulo {ci + 1}",
                                                                          "synopsis": "", "words": []}
            words = []
            for w in pch["words"]:
                k = low(re.sub(r"\s*\(.*\)$", "", w))
                if k in pool_set and k not in seen:
                    seen.add(k)
                    words.append(k)
            chapters.append({"title": pch["title"], "synopsis": pch["synopsis"], "words": words,
                             "grammar": grammar[ci]})
        plan = {"title": res["title"], "premise": res["premise"], "characters": res["characters"],
                "chapters": chapters, "per_ch": per_ch}
        log(f"{entry['id']}: «{plan['title']}», слова по главам: {[len(c['words']) for c in chapters]}")
        return plan

    # ------------------------------------------------ глава

    def gen_chapter(self, entry: dict, plan: dict, ci: int, prev: list[dict]) -> dict:
        seed = self.seed(entry["seed"])
        pch = plan["chapters"][ci]
        wmin, wmax = self.cfg["words"]
        system = GEN_SYSTEM.format(level=self.L, level_guide=pb.LEVEL_GUIDE[self.L], spain=pb.SPAIN_RULES,
                                   conversational=pb.CONVERSATIONAL, wmin=wmin, wmax=wmax)
        chars = "; ".join(f"{c['name']} ({c['gender']}) — {c['role']}" for c in plan["characters"])
        outline = "\n".join(f"{i + 1}. {c['title']}: {c['synopsis']}" for i, c in enumerate(plan["chapters"]))
        prev_txt = "\n\n".join(f"### Chapter {i + 1}: {p['title']}\n{chapter_text(p)}" for i, p in enumerate(prev))
        words = "\n".join(f"- {w} ({self.vocab[w]['ru']})" for w in pch["words"]) or "(none)"
        gram = "\n".join(f"- {self.topics[self.topic_idx[g]]['titleEs']}: {self.topics[self.topic_idx[g]]['summary']}"
                         for g in pch["grammar"])
        src = f"Adaptation of: {seed['source']} ({seed.get('author', '')})" if seed["kind"] == PD else "Original story"
        prompt = (
            f"BOOK: {plan['title']} — {src}\nPremise: {plan['premise']}\nCharacters: {chars}\n\n"
            f"OUTLINE:\n{outline}\n\n"
            + (f"PREVIOUS CHAPTERS (for continuity):\n{prev_txt}\n\n" if prev else "")
            + f"NOW WRITE CHAPTER {ci + 1} of {len(plan['chapters'])}: {pch['title']}\n{pch['synopsis']}\n\n"
            f"TARGET WORDS (use every one):\n{words}\n\nGRAMMAR POINTS (3+ examples each):\n{gram}\n"
        )
        return pb.claude_json(prompt, system, pb.ADAPT_SCHEMA, self.args.model, "medium")

    def annotate(self, sentences: list[str], work: Path, tag: str) -> list[dict]:
        h = hashlib.sha1("\n".join(sentences).encode()).hexdigest()[:10]

        def call(sents: list[str]) -> list[dict]:
            numbered = "\n".join(f"{i + 1}. {s}" for i, s in enumerate(sents))
            res = pb.claude_json(f"Annotate these {len(sents)} sentences:\n\n{numbered}",
                                 pb.ANNOTATE_SYSTEM + ANNOTATE_EXTRA, pb.ANNOTATE_SCHEMA,
                                 self.args.model, "low")
            out = res["sentences"]
            if len(out) != len(sents):
                if len(sents) == 1:
                    raise RuntimeError("annotation: wrong sentence count")
                mid = len(sents) // 2
                return call(sents[:mid]) + call(sents[mid:])
            return out

        def run() -> list[dict]:
            out = call(sentences)
            bad = [i for i, (s, a) in enumerate(zip(sentences, out)) if not pb.tokens_ok(s, a["tokens"])]
            if bad:
                try:
                    fixed = call([sentences[i] for i in bad])
                    for i, a in zip(bad, fixed):
                        if pb.tokens_ok(sentences[i], a["tokens"]):
                            out[i] = a
                except RuntimeError:
                    pass
            return [{"ru": a["ru"], "tokens": a["tokens"]} for a in out]

        return pb.cached(work / f"ann_{tag}_{h}.json", run)

    def build_chapter(self, entry: dict, plan: dict, ci: int, prev: list[dict]) -> dict:
        w = self.work(entry["id"])
        gen = pb.cached(w / f"gen_{ci:02d}.json", lambda: self.gen_chapter(entry, plan, ci, prev))
        flat = [(pi, s.strip()) for pi, para in enumerate(gen["paragraphs"]) for s in para if s.strip()]
        batches = [flat[b: b + 18] for b in range(0, len(flat), 18)]
        with cf.ThreadPoolExecutor(len(batches) or 1) as ex:
            results = list(ex.map(lambda ib: self.annotate([s for _, s in ib[1]], w, f"{ci:02d}_{ib[0]:02d}"),
                                  enumerate(batches)))
        paragraphs: dict[int, list] = {}
        for items, res in zip(batches, results):
            for (pi, _), s in zip(items, res):
                paragraphs.setdefault(pi, []).append(s)
        ch = {"title": gen["title"], "titleRu": gen.get("title_ru", ""),
              "paragraphs": [{"sentences": paragraphs[k]} for k in sorted(paragraphs)]}
        targets = {x: self.parts[x] for x in plan["chapters"][ci]["words"]}
        hits = chapter_hits(ch, targets)
        miss = [x for x in targets if not hits[x]]
        log(f"{entry['id']} гл.{ci + 1} «{ch['title']}»: {word_count(ch)} слов, "
            f"целевых {len(targets) - len(miss)}/{len(targets)}" + (f"; мимо: {', '.join(miss)}" if miss else ""))
        pb.cached(w / f"chap_{ci:02d}.json", lambda: ch)
        return ch

    # ------------------------------------------------ грамматика, словарик

    def verify_grammar(self, plan: dict, chapters: list[dict]) -> dict:
        blocks = []
        for ci, (pch, ch) in enumerate(zip(plan["chapters"], chapters)):
            pts = "\n".join(f"  - {g}: {self.topics[self.topic_idx[g]]['titleEs']} — "
                            f"{self.topics[self.topic_idx[g]]['summary']}" for g in pch["grammar"])
            blocks.append(f"=== Chapter {ci + 1}\nGrammar points to check:\n{pts}\nText:\n{chapter_text(ch)}")
        res = pb.claude_json("\n\n".join(blocks), GRAMMAR_SYSTEM, GRAMMAR_SCHEMA, self.args.model, "low")
        out: dict[str, list[str]] = {}
        for c in res["chapters"]:
            ci = c["chapter"] - 1
            if 0 <= ci < len(chapters):
                ok = [t["id"] for t in c["topics"] if t["practised"] and t["examples"]
                      and t["id"] in plan["chapters"][ci]["grammar"]]
                out[str(ci)] = sorted(set(ok), key=self.topic_idx.get)
        return out

    def is_name(self, tok: dict, first: bool) -> bool:
        t, l = tok.get("t", ""), tok.get("l", "") or ""
        return (t[:1].isupper() and not first) or (l[:1].isupper())

    def in_level(self, l: str) -> bool:
        """Лемма из словарей ≤ L, с учётом женских форм (profesora, nosotras) и возвратных глаголов."""
        a = self.allowed
        return (l in a or (l.endswith("se") and l[:-2] in a)
                or (l.endswith("a") and (l[:-1] + "o" in a or l[:-1] in a))
                or (l.endswith("as") and (l[:-2] + "os" in a)))

    @staticmethod
    def names(chapters: list[dict]) -> set[str]:
        """Слова, которые в книге пишутся с заглавной не в начале предложения, — имена."""
        out: set[str] = set()
        for ch in chapters:
            for p in ch["paragraphs"]:
                for s in p["sentences"]:
                    words = [t for t in s["tokens"] if is_word(t)]
                    for i, tok in enumerate(words):
                        if i and tok["t"][:1].isupper():
                            out.add(pb.norm_key(tok["t"]))
        return out

    def glossary(self, ch: dict, seen: set[str], names: set[str] = frozenset()) -> list[dict]:
        cnt: Counter = Counter()
        first: dict[str, dict] = {}
        for p in ch["paragraphs"]:
            for s in p["sentences"]:
                words = [t for t in s["tokens"] if is_word(t)]
                for i, tok in enumerate(words):
                    l = low(tok.get("l") or "")
                    if not l or len(l) < 3 or self.is_name(tok, i == 0) or " " in l:
                        continue
                    if (tok.get("g") or "").startswith("числ") or l in ("del", "al"):
                        continue
                    if pb.norm_key(tok["t"]) in names or pb.norm_key(l) in names:
                        continue
                    if l in seen or self.in_level(l):
                        continue
                    cnt[l] += 1
                    first.setdefault(l, tok)
        items = []
        for l, _ in cnt.most_common(6):
            tok = first[l]
            items.append({"l": l, "ru": tok.get("ru", ""), "form": tok["t"]} if low(tok["t"]) != l
                         else {"l": l, "ru": tok.get("ru", "")})
            seen.add(l)
        return [it for it in items if it["ru"]]

    def out_of_level(self, ch: dict) -> tuple[int, int]:
        n = bad = 0
        for p in ch["paragraphs"]:
            for s in p["sentences"]:
                words = [t for t in s["tokens"] if is_word(t)]
                for i, tok in enumerate(words):
                    l = low(tok.get("l") or tok["t"])
                    if self.is_name(tok, i == 0):
                        continue
                    n += 1
                    if not self.in_level(l) and low(tok["t"]) not in self.allowed:
                        bad += 1
        return bad, n

    # ------------------------------------------------ книга целиком

    def build_book(self, entry: dict) -> None:
        bid = entry["id"]
        seed = self.seed(entry["seed"])
        w = self.work(bid)
        with self.plan_lock:
            plan = pb.cached(w / "plan.json", lambda: self.plan_book(entry))
        chapters: list[dict] = []
        for ci in range(len(plan["chapters"])):
            path = w / f"chap_{ci:02d}.json"
            ch = self.load(path) if path.exists() else self.build_chapter(entry, plan, ci, chapters)
            chapters.append(ch)

        gram = pb.cached(w / "grammar.json", lambda: self.verify_grammar(plan, chapters))
        seen: set[str] = set()
        names = self.names(chapters) | {pb.norm_key(c["name"]) for c in plan["characters"]}
        out_chapters = []
        for ci, ch in enumerate(chapters):
            c = {"title": ch["title"], "titleRu": ch["titleRu"], "grammar": gram.get(str(ci), [])}
            gl = self.glossary(ch, seen, names)
            if gl:
                c["glossary"] = gl
            c["paragraphs"] = json.loads(json.dumps(ch["paragraphs"]))
            out_chapters.append(c)

        book = {
            "format": 1, "id": bid, "title": seed.get("title") or plan["title"],
            "author": seed.get("author", "") if seed["kind"] == PD else "",
            "level": self.L, "lang": "es-ES", "source": seed["source"],
            "kind": "adapted",
            "workId": seed.get("workId") or bid,
        }
        if seed["kind"] == PD:
            book["year"] = seed["year"]
            book["license"] = seed["license"]
        book["chapters"] = out_chapters

        eargs = argparse.Namespace(model=self.args.model, enrich_effort="medium", jobs=self.args.jobs)
        pb.enrich_book(book, w, eargs)
        tags_path = w / "tags.json"
        if tags_path.exists():
            book["tags"] = json.loads(tags_path.read_text(encoding="utf-8"))
        else:
            pb.tag_book(book, eargs)
            tags_path.write_text(json.dumps(book["tags"], ensure_ascii=False), encoding="utf-8")
        # Порядок ключей: метаданные, characters, tags, chapters.
        ordered = {k: v for k, v in book.items() if k not in ("characters", "tags", "chapters")}
        ordered["characters"] = book["characters"]
        ordered["tags"] = book["tags"]
        ordered["chapters"] = book["chapters"]

        out = BOOKS_DIR / f"{bid}.json"
        pb.write_book(out, ordered)
        ASSET_BOOKS.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(out, ASSET_BOOKS / out.name)
        with self.lock:
            entry["status"] = "done"
            self.save_state()
        n_words = sum(word_count(c) for c in out_chapters)
        log(f"✅ {bid}: «{book['title']}», {len(out_chapters)} гл., {n_words} слов, "
            f"{out.stat().st_size // 1024} КБ")

    # ------------------------------------------------ отчёт

    def report(self) -> Path:
        with self.lock:
            snap = self.snapshot()
        nT = len(self.T)
        cov = snap["covered"]
        lines = [f"# Покрытие учебной программы {self.L} библиотекой книг", "",
                 f"Сгенерировано `pipeline/library.py`. Словарь: `vocab_{self.L}.json` ({nT} лемм), "
                 f"грамматика: `grammar_{self.L}.json` ({len(self.topics)} тем).", ""]
        g_chapters: Counter = Counter()
        g_books: dict[str, set] = {}
        rows = []
        tot_words = tot_ch = 0
        tot_bad = tot_n = 0
        for b in self.state["books"]:
            w = self.work(b["id"])
            plan = self.load(w / "plan.json")
            gram = self.load(w / "grammar.json") or {}
            book_path = BOOKS_DIR / f"{b['id']}.json"
            if plan is None or not book_path.exists():
                rows.append((b, None, None))
                continue
            book = json.loads(book_path.read_text(encoding="utf-8"))
            bad = n = 0
            for ch in book["chapters"]:
                x, y = self.out_of_level(ch)
                bad, n = bad + x, n + y
            tot_bad, tot_n = tot_bad + bad, tot_n + n
            topics: Counter = Counter()
            for ci in range(len(book["chapters"])):
                for t in gram.get(str(ci), []):
                    topics[t] += 1
                    g_chapters[t] += 1
                    g_books.setdefault(t, set()).add(b["id"])
            info = snap["per_book"][b["id"]]
            tot_words += info["words"]
            tot_ch += info["chapters"]
            rows.append((b, book, {"info": info, "topics": topics, "ool": (bad, n)}))

        lines += ["## Итог", "",
                  f"- Книг: {sum(1 for r in rows if r[1])}, глав: {tot_ch}, слов текста: {tot_words}",
                  f"- **Словарь: {len(cov)}/{nT} = {100 * len(cov) / nT:.1f} %** лемм уровня встречаются в книгах",
                  f"- **Грамматика: {sum(1 for t in self.topics if g_chapters[t['id']] >= 2)}/{len(self.topics)} тем "
                  f"отработаны ≥ 2 раз** (подтверждено проверкой каждой главы)",
                  f"- Слова выше уровня (не из словарей ≤ {self.L}, без имён): "
                  f"{100 * tot_bad / max(1, tot_n):.1f} % словоупотреблений", ""]

        lines += ["## Книги", "", "| # | Книга | Источник | Гл. | Слов | Новых лемм | Грамматика (глав) | Выше уровня |",
                  "|---|---|---|---|---|---|---|---|"]
        for b, book, d in rows:
            if not book:
                lines.append(f"| {b['id']} | (не готова) | | | | | | |")
                continue
            info = d["info"]
            topics = ", ".join(f"{t.removeprefix(self.L.lower() + '-')} ({n})"
                               for t, n in sorted(d["topics"].items(), key=lambda x: self.topic_idx[x[0]]))
            bad, n = d["ool"]
            lines.append(f"| `{b['id']}` | {book['title']} | {book['source']} | {len(book['chapters'])} | "
                         f"{info['words']} | {len(info['new'])} | {topics} | {100 * bad / max(1, n):.1f} % |")
        lines += ["", "## Грамматические темы", "", "| Тема | Глав | Книги |", "|---|---|---|"]
        for t in self.topics:
            mark = "" if g_chapters[t["id"]] >= 2 else " ⚠️"
            lines.append(f"| `{t['id']}` {t['title']}{mark} | {g_chapters[t['id']]} | "
                         f"{', '.join(sorted(g_books.get(t['id'], [])))} |")
        lines += ["", "## Покрытие по темам словаря", "", "| Тема | Покрыто |", "|---|---|"]
        for tid, title in self.themes:
            ws = [x for x in self.T if self.vocab[x]["theme"] == tid]
            c = sum(1 for x in ws if x in cov)
            lines.append(f"| {title} (`{tid}`) | {c}/{len(ws)} ({100 * c / len(ws):.0f} %) |")
        lines += ["", f"## Непокрытые леммы ({nT - len(cov)})", ""]
        for tid, title in self.themes:
            miss = [x for x in self.T if self.vocab[x]["theme"] == tid and x not in cov]
            if miss:
                lines.append(f"- **{title}**: " + ", ".join(f"{x} ({self.vocab[x]['ru']})" for x in miss))
        once = [x for x in self.T if snap["freq"][x] == 1]
        lines += ["", f"Лемм, встретившихся только один раз: {len(once)}.", ""]
        path = PIPE / f"coverage_{self.L}.md"
        path.write_text("\n".join(lines), encoding="utf-8")
        log(f"Отчёт: {path} — словарь {len(cov)}/{nT} ({100 * len(cov) / nT:.1f} %), "
            f"темы ≥2: {sum(1 for t in self.topics if g_chapters[t['id']] >= 2)}/{len(self.topics)}")
        return path


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--level", required=True, choices=list(LEVEL_CFG))
    ap.add_argument("--jobs", type=int, default=3, help="параллельных запросов claude (и книг)")
    ap.add_argument("--model", default="sonnet")
    ap.add_argument("--min-books", type=int, default=0)
    ap.add_argument("--max-books", type=int, default=0)
    ap.add_argument("--report", action="store_true", help="только пересчитать отчёт coverage_<L>.md")
    ap.add_argument("--rebuild", action="store_true",
                    help="пересобрать готовые книги из кэша (без новых запросов) и отчёт")
    args = ap.parse_args()
    cfg = LEVEL_CFG[args.level]
    args.min_books = args.min_books or cfg["min_books"]
    args.max_books = args.max_books or cfg["max_books"]
    global _SEM
    _SEM = threading.Semaphore(args.jobs)
    lib = Library(args.level, args)
    if args.rebuild:
        for b in lib.state["books"]:
            if b["status"] == "done":
                lib.build_book(b)
    if args.report or args.rebuild:
        lib.report()
        return
    lib.run()


if __name__ == "__main__":
    main()
