package com.homemeds.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.sp
import org.junit.Assert.*
import org.junit.Test

class ThemeTest {
    private fun contrast(a: Color, b: Color): Float {
        val first = a.luminance()
        val second = b.luminance()
        return (maxOf(first, second) + .05f) / (minOf(first, second) + .05f)
    }

    @Test fun selectedTabsUseLightGreenWithReadableDarkText() {
        val selected = tabPalette(true)
        assertEquals(Color(0xFFDCEBD4), selected.background)
        assertEquals(Ink, selected.content)
        assertEquals(Green, selected.border)
        assertTrue(contrast(selected.background, selected.content) >= 4.5f)
    }

    @Test fun unselectedTabsStayDistinctAndReadable() {
        val idle = tabPalette(false)
        assertEquals(Color.White, idle.background)
        assertNotEquals(tabPalette(true).background, idle.background)
        assertNotEquals(tabPalette(true).border, idle.border)
        assertTrue(contrast(idle.background, idle.content) >= 4.5f)
    }

    @Test fun typographyDoesNotOverrideComponentTextColorsInEitherMode() {
        for (elderly in listOf(false, true)) {
            val typography = appTypography(elderly)
            listOf(typography.bodyLarge, typography.bodyMedium, typography.bodySmall,
                typography.labelLarge, typography.labelMedium, typography.labelSmall,
                typography.titleLarge, typography.titleMedium).forEach {
                assertEquals(Color.Unspecified, it.color)
            }
            assertEquals(if (elderly) 22.sp else 17.sp, typography.labelLarge.fontSize)
        }
    }

    @Test fun primaryAndDangerButtonsHaveSufficientWhiteTextContrast() {
        assertTrue(contrast(Green, Color.White) >= 4.5f)
        assertTrue(contrast(Red, Color.White) >= 4.5f)
    }
}
