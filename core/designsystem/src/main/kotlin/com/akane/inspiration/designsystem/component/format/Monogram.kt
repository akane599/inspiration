package com.akane.inspiration.designsystem.component.format

import java.util.Locale

/**
 * An app tile's letter: the first letter or digit of [label], upper-cased by [locale]'s rules (Turkish `i` becomes
 * `İ`). Empty when [label] has neither.
 */
fun monogram(
    label: String,
    locale: Locale,
): String = label.firstOrNull { it.isLetterOrDigit() }?.uppercase(locale).orEmpty()
