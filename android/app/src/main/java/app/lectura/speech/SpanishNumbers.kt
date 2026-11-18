package app.lectura.speech

/**
 * Числа цифрами → испанские слова. Нужно, потому что голосовой ввод (клавиатура и распознаватель)
 * пишет «tengo 3 hermanos», а в ответе «tengo tres hermanos».
 */
object SpanishNumbers {
    /** Слова для числа 0..999 999 (с ударениями: dieciséis, veintidós…). */
    fun words(n: Int): String = when {
        n < 30 -> SMALL[n]
        n < 100 -> TENS[n / 10] + if (n % 10 != 0) " y " + SMALL[n % 10] else ""
        n == 100 -> "cien"
        n < 1000 -> HUNDREDS[n / 100] + if (n % 100 != 0) " " + words(n % 100) else ""
        else -> (if (n / 1000 == 1) "mil" else words(n / 1000) + " mil") +
            if (n % 1000 != 0) " " + words(n % 1000) else ""
    }

    /**
     * Варианты прочтения числа-токена (только цифры). Для чисел на 1 — ещё мужская/женская
     * усечённые формы: 1 → uno/un/una, 21 → veintiuno/veintiún/veintiuna, 31 → treinta y uno/un/una.
     * null — не число или слишком большое.
     */
    fun forms(token: String): List<String>? {
        if (token.isEmpty() || !token.all { it in '0'..'9' } || token.length > 6) return null
        val n = token.toInt()
        val base = words(n)
        if (n % 10 != 1 || n % 100 == 11) return listOf(base)
        return when {
            base.endsWith("veintiuno") -> listOf(base, base.dropLast(3) + "ún", base.dropLast(1) + "a")
            base.endsWith("uno") -> listOf(base, base.dropLast(1), base.dropLast(1) + "a")
            else -> listOf(base)
        }
    }

    /** «1.000» / «1 000» → «1000» (разделители тысяч). */
    fun joinThousands(s: String): String = THOUSANDS.replace(s, "")

    private val THOUSANDS = Regex("(?<=\\d)[.\\u00A0\\u202F ](?=\\d{3}(?!\\d))")

    private val SMALL = listOf(
        "cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve",
        "diez", "once", "doce", "trece", "catorce", "quince", "dieciséis", "diecisiete", "dieciocho", "diecinueve",
        "veinte", "veintiuno", "veintidós", "veintitrés", "veinticuatro", "veinticinco", "veintiséis",
        "veintisiete", "veintiocho", "veintinueve",
    )
    private val TENS = listOf("", "", "", "treinta", "cuarenta", "cincuenta", "sesenta", "setenta", "ochenta", "noventa")
    private val HUNDREDS = listOf(
        "", "ciento", "doscientos", "trescientos", "cuatrocientos", "quinientos",
        "seiscientos", "setecientos", "ochocientos", "novecientos",
    )
}
