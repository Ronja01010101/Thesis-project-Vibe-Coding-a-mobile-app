package com.example.thesisproject.util

import java.text.Normalizer

/**
 * BUG-001: diacritic-insensitive normalisation for stop-name search.
 *
 * Decomposes the string to Unicode NFD (so "å" → "a" + combining ring above),
 * strips all combining marks (Unicode category Mn), and lowercases.
 *
 * Maps "Ångermanland" → "angermanland", "Östermalmstorg" → "ostermalmstorg",
 * so a user typing "angerm" / "oster" matches stops whose names start with
 * Swedish characters they don't have on their keyboard.
 */
fun normalizeForSearch(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .lowercase()

private val COMBINING_MARKS = "\\p{Mn}+".toRegex()
