package com.elmdash.ui

import com.elmdash.ui.engine.LayoutLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LayoutLoaderTest {

    @Test
    fun `loads all 3 Corsa-e dashboard pages from dashboard_layout json`() {
        val candidates = listOf(
            File("../app/src/main/assets/dashboard_layout.json"),
            File("app/src/main/assets/dashboard_layout.json")
        )
        val file = candidates.first { it.exists() }
        val config = LayoutLoader.parseConfig(file.readText(Charsets.UTF_8))
        val pages = config.pages

        assertEquals(3, pages.size)
        assertEquals(listOf("drive", "charge", "battery"), pages.map { it.id })
        assertTrue(pages.all { it.widgets.isNotEmpty() })

        // Verify all widget slots fit inside 12x6 grid bounds
        for (page in pages) {
            for (s in page.widgets) {
                assertTrue("col out of bounds in ${s.id}", s.col in 0..11)
                assertTrue("row out of bounds in ${s.id}", s.row in 0..5)
                assertTrue("colSpan overflows 12 in ${s.id}", s.col + s.colSpan <= 12)
                assertTrue("rowSpan overflows 6 in ${s.id}", s.row + s.rowSpan <= 6)
            }
        }
    }
}
