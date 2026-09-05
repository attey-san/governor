package com.attey.governor.core

/** Quotes one argument for Android's POSIX-compatible system shell. */
internal fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"
