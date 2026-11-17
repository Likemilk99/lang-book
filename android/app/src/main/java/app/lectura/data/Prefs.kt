package app.lectura.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    private val _fontSize = MutableStateFlow(sp.getFloat("fontSize", 21f))
    val fontSize: StateFlow<Float> = _fontSize.asStateFlow()
    fun setFontSize(v: Float) {
        val c = v.coerceIn(14f, 34f)
        _fontSize.value = c
        sp.edit().putFloat("fontSize", c).apply()
    }

    private val _showRu = MutableStateFlow(sp.getBoolean("showRu", false))
    val showRu: StateFlow<Boolean> = _showRu.asStateFlow()
    fun setShowRu(v: Boolean) {
        _showRu.value = v
        sp.edit().putBoolean("showRu", v).apply()
    }

    var rate: Float
        get() = sp.getFloat("rate", 0.8f)
        set(v) = sp.edit().putFloat("rate", v).apply()

    fun deletedBundled(): Set<String> = sp.getStringSet("deletedBundled", emptySet()).orEmpty()
    fun markBundledDeleted(name: String) {
        sp.edit().putStringSet("deletedBundled", deletedBundled() + name).apply()
    }
}
