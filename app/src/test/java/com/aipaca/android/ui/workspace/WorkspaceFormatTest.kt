package com.aipaca.android.ui.workspace

import com.aipaca.android.engine.agent.tools.WorkspaceEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceFormatTest {

    @Test
    fun directoriesFirstThenCaseInsensitiveNames() {
        val entries = listOf(
            WorkspaceEntry("b.txt", "b.txt", false, 1),
            WorkspaceEntry("Zdir", "Zdir", true, 0),
            WorkspaceEntry("A.txt", "A.txt", false, 1),
            WorkspaceEntry("adir", "adir", true, 0),
        )

        assertEquals(listOf("adir", "Zdir", "A.txt", "b.txt"), sortWorkspaceEntries(entries).map { it.name })
    }

    @Test
    fun pathHelpers() {
        assertEquals("", parentPath("a.txt"))
        assertEquals("docs", parentPath("docs/a.txt"))
        assertEquals("docs/a.txt", childPath("docs", "a.txt"))
        assertEquals("a.txt", childPath("", "a.txt"))
    }

    @Test
    fun importNamesAreSanitizedAndMadeUnique() {
        assertEquals("report.pdf", sanitizeImportName("/storage/emulated/0/report.pdf"))
        assertEquals("imported.txt", sanitizeImportName(".."))
        assertEquals("imported.txt", sanitizeImportName(null))
        assertEquals("a-2.txt", uniqueName(setOf("a.txt", "a-1.txt"), "a.txt"))
        assertEquals("notes-1", uniqueName(setOf("notes"), "notes"))
        assertEquals("new.md", uniqueName(setOf("a.txt"), "new.md"))
    }

    @Test
    fun binaryDetection() {
        assertTrue(looksBinary(byteArrayOf(0x50, 0x00, 0x4b)))
        assertFalse(looksBinary("plain text ✓".toByteArray()))
    }
}
