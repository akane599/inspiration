package com.akane.inspiration.designsystem.component.format

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLocale
import java.util.Locale

/** The locale the UI is shown in (`LocalLocale`), as a [Locale] for formatting and case mapping. */
@Composable
fun currentLocale(): Locale = LocalLocale.current.platformLocale
