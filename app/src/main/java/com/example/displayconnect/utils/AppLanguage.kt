package com.example.displayconnect.utils

import android.content.Context
import android.content.res.Configuration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

object AppLanguage {
    private val selected = MutableStateFlow("pt-BR")
    val language = selected.asStateFlow()
    private fun normalize(tag: String?): String = when(tag) { "en" -> "en"; "es" -> "es"; else -> "pt-BR" }
    fun load(context: Context) {
        selected.value = context.getSharedPreferences("display_language", Context.MODE_PRIVATE)
            .getString("language", "pt-BR").let { normalize(it) }
    }
    fun select(context: Context, tag: String) {
        selected.value = normalize(tag)
        context.getSharedPreferences("display_language", Context.MODE_PRIVATE).edit()
            .putString("language", selected.value).apply()
    }
    fun context(base: Context, tag: String = selected.value): Context {
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        return base.createConfigurationContext(config)
    }
}
