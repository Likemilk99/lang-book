package app.lectura.playback

import app.lectura.LecturaApp

/**
 * Произнести испанский текст: голосом диктора, если это фраза разговорника ([ref] = её id) или её вариант,
 * иначе системным TTS. Звук берётся из файла только при точном совпадении текста — чтобы не расходились.
 */
fun speakEs(text: String, ref: String = "", slow: Boolean = false) {
    val app = LecturaApp.instance
    val clip = if (ref.isBlank()) null else app.phrasebook.clipFor(ref, text)
    app.player.speakClip(clip, text, slow)
}
