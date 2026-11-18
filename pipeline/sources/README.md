# Исходные тексты (общественное достояние)

Критерий: автор умер до 1945 г. **и** произведение опубликовано до 1930 г.
(общественное достояние и в Испании — 80 лет после смерти для авторов, умерших до 1987 г., —
и в России/ЕС — 70 лет, и в США — публикация до 1930 г.).

Все тексты — из Project Gutenberg (plain text UTF-8). Скачанные файлы лежат в `raw/`
(в git не попадают); `extract.py` вырезает из них нужные произведения в `*.txt` рядом
(эти файлы в git есть, это текст автора без изменений, с разметкой `# Заголовок`).

| Файл | Произведение | Автор (годы жизни) | Первая публикация | Gutenberg |
|---|---|---|---|---|
| `becquer-monte-animas.txt` | «El monte de las ánimas» | Gustavo Adolfo Bécquer (1836–1870) | 1861, «El Contemporáneo» | [#53552](https://www.gutenberg.org/ebooks/53552) «Obras escogidas» (изд. ок. 1911) |
| `becquer-leyendas.txt` | «Los ojos verdes» (1861), «El rayo de luna» (1862), «Maese Pérez el organista» (1861) | Bécquer | 1861–1862, «El Contemporáneo» | #53552 |
| `clarin-adios-cordera.txt` | «¡Adiós, Cordera!» | Leopoldo Alas «Clarín» (1852–1901) | 1892; сборник «El Señor y lo demás, son cuentos» 1893 | [#62361](https://www.gutenberg.org/ebooks/62361) (изд. 1919) |
| `coloma-raton-perez.txt` | «Ratón Pérez», cuento infantil | Luis Coloma (1851–1915) | написан ок. 1891–1894 для Альфонсо XIII; издание 1911 | [#36558](https://www.gutenberg.org/ebooks/36558) |

Вступление братьев Альварес Кинтеро к «Obras escogidas» (они умерли в 1938 и 1944 гг.) не используется —
только текст Беккера. Посвящение к «Ratón Pérez» и сноски издателя тоже отброшены.

Детские сказки Сатурнино Кальехи и «Cuentos y poesías populares andaluces» Фернан Кабальеро
в Project Gutenberg не нашлись (проверено по GUTINDEX.ALL), поэтому взят «Ratón Pérez» Колома —
классическая испанская сказка о зубной мышке.

## Как скачать и собрать

Ссылки вида `https://www.gutenberg.org/cache/epub/<ID>/pg<ID>.txt`; если gutenberg.org недоступен,
то же самое есть на зеркале `https://gutenberg.pglaf.org/cache/epub/<ID>/pg<ID>.txt`.

```sh
mkdir -p pipeline/sources/raw
for id in 53552 62361 36558; do
  curl -L -o pipeline/sources/raw/pg$id.txt https://www.gutenberg.org/cache/epub/$id/pg$id.txt
done
python3 -I pipeline/sources/extract.py
```

Затем книги:

```sh
P="python3 pipeline/prepare_book.py --jobs 3"
# Оригиналы
$P --original --source pipeline/sources/becquer-monte-animas.txt --title "El monte de las ánimas" \
   --author "Gustavo Adolfo Bécquer" --level B2 --id becquer-monte-animas --work-id becquer-monte-animas \
   --year 1861 --license "Общественное достояние: автор умер в 1870 г." --batch 10 \
   --source-note "Gustavo Adolfo Bécquer, «El monte de las ánimas» (1861). Текст: Obras escogidas, Project Gutenberg #53552"
$P --original --source pipeline/sources/becquer-leyendas.txt --title "Leyendas (selección)" \
   --author "Gustavo Adolfo Bécquer" --level B2 --id becquer-leyendas --work-id becquer-leyendas \
   --year 1861 --license "Общественное достояние: автор умер в 1870 г." --batch 10 \
   --source-note "Gustavo Adolfo Bécquer, «Los ojos verdes», «El rayo de luna», «Maese Pérez el organista» (1861–1862). Текст: Obras escogidas, Project Gutenberg #53552"
$P --original --source pipeline/sources/clarin-adios-cordera.txt --title "¡Adiós, Cordera!" \
   --author "Leopoldo Alas «Clarín»" --level B2 --id clarin-adios-cordera --work-id clarin-adios-cordera \
   --year 1892 --license "Общественное достояние: автор умер в 1901 г." --batch 10 \
   --source-note "Leopoldo Alas «Clarín», «¡Adiós, Cordera!» (1892), из сборника «El Señor y lo demás, son cuentos». Project Gutenberg #62361"
$P --original --source pipeline/sources/coloma-raton-perez.txt --title "Ratón Pérez" \
   --author "Luis Coloma" --level B1 --id coloma-raton-perez --work-id coloma-raton-perez \
   --year 1911 --license "Общественное достояние: автор умер в 1915 г." --batch 10 \
   --source-note "Luis Coloma, «Ratón Pérez», cuento infantil (написан ок. 1891–1894; издание 1911). Project Gutenberg #36558"
# Лесенка версий «El monte de las ánimas»: A2 и B1 (адаптации) + оригинал выше
$P --source pipeline/sources/becquer-monte-animas.txt --title "El monte de las ánimas" --author "Gustavo Adolfo Bécquer" \
   --level A2 --id becquer-monte-animas-a2 --work-id becquer-monte-animas --year 1861 \
   --license "Оригинал в общественном достоянии: автор умер в 1870 г."
$P --source pipeline/sources/becquer-monte-animas.txt --title "El monte de las ánimas" --author "Gustavo Adolfo Bécquer" \
   --level B1 --id becquer-monte-animas-b1 --work-id becquer-monte-animas --year 1861 \
   --license "Оригинал в общественном достоянии: автор умер в 1870 г."
```
