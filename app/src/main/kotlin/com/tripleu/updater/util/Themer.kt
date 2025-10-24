package com.tripleu.updater.util

import com.tripleu.updater.prefs.Prefs
import com.tripleu.updater.ui.theme.isDarkTheme
import kotlinx.coroutines.flow.MutableStateFlow

class Themer(prefs: Prefs) {

    private val theme = MutableStateFlow(isDarkTheme(prefs.theme.get()))

    fun flow() = theme

    fun setTheme(v: Boolean) { theme.value = v }

}
