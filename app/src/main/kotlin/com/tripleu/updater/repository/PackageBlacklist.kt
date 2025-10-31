package com.tripleu.updater.repository

import com.tripleu.updater.data.ui.AppUpdate

private val ALWAYS_BLACKLISTED_PACKAGES = setOf(
    "com.google.android.googlequicksearchbox"
)

internal fun AppUpdate.isBlacklisted(): Boolean = packageName in ALWAYS_BLACKLISTED_PACKAGES
