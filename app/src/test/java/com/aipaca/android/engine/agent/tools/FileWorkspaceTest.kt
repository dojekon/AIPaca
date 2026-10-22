package com.aipaca.android.engine.agent.tools

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileWorkspaceTest {

    @Test
    fun validateBlankAndEmptyReturnRoot() = withWorkspace { _, workspace ->
        assertEquals("", workspace.validate("").getOrThrow())
        assertEquals("", workspace.validate("   ").getOrThrow())
        assertEquals("", workspace.validate(".").getOrThrow())
    }

    @Test
    fun validateNestedPathReturnsNormalizedRelativePath() = withWorkspace { _, workspace ->
        assertEquals("notes/today.txt", workspace.validate("notes/today.txt").getOrThrow())
        assertEquals("notes/today.txt", workspace.validate("notes/./today.txt").getOrThrow())
    }

    @Test
    fun validateRejectsTraversalAndAbsolutePaths() = withWorkspace { _, workspace ->
        assertTrue(workspace.validate("../outside").isFailure)
        assertTrue(workspace.validate("a/../../outside").isFailure)
        assertTrue(workspace.validate("/etc/passwd").isFailure)
    }

    @Test
    fun listSortsDirectoriesFirstThenByName() = withWorkspace { root, workspace ->
        File(root, "zdir").mkdirs()
        File(root, "adir").mkdirs()
        File(root, "b.txt").writeText("b")
        File(root, "a.txt").writeText("aa")

        val entries = runBlocking { workspace.list("").getOrThrow() }

        assertEquals(
            listOf("adir/", "zdir/", "a.txt", "b.txt"),
            entries.map { if (it.isDirectory) "${it.name}/" else it.name },
        )
        assertEquals(2L, entries.first { it.name == "a.txt" }.sizeBytes)
        assertEquals("adir", entries.first { it.name == "adir" }.path)
    }

    @Test
    fun walkIncludesDirectoriesAndFiles() = withWorkspace { root, workspace ->
        File(root, "sub").mkdirs()
        File(root, "sub/deep").mkdirs()
        File(root, "a.txt").writeText("x")
        File(root, "sub/b.txt").writeText("y")

        val paths = runBlocking { workspace.walk("").getOrThrow() }.map { it.path }.toSet()

        assertEquals(setOf("sub", "sub/deep", "sub/b.txt", "a.txt"), paths)
    }

    @Test
    fun walkRespectsMaxEntries() = withWorkspace { root, workspace ->
        File(root, "sub").mkdirs()
        repeat(5) { index -> File(root, "f$index.txt").writeText("x") }
        File(root, "sub/nested.txt").writeText("x")

        val entries = runBlocking { workspace.walk("", maxEntries = 3).getOrThrow() }

        assertEquals(3, entries.size)
    }

    @Test
    fun statMissingReturnsNull() = withWorkspace { _, workspace ->
        assertNull(runBlocking { workspace.stat("nope/missing.txt").getOrThrow() })
    }

    @Test
    fun statReturnsEntryForFile() = withWorkspace { root, workspace ->
        File(root, "doc.txt").writeText("hello")

        val entry = runBlocking { workspace.stat("doc.txt").getOrThrow() }

        assertEquals("doc.txt", entry?.path)
        assertEquals("doc.txt", entry?.name)
        assertEquals(false, entry?.isDirectory)
        assertEquals(5L, entry?.sizeBytes)
    }

    @Test
    fun writeCreatesNestedDirectoriesAndReadRoundTrips() = withWorkspace { root, workspace ->
        val writeResult = runBlocking { workspace.writeText("nested/dir/file.txt", "Привет\nworld") }
        assertTrue(writeResult.isSuccess)
        assertEquals("Привет\nworld", File(root, "nested/dir/file.txt").readText())
        assertEquals(
            "Привет\nworld",
            runBlocking { workspace.readText("nested/dir/file.txt").getOrThrow() },
        )
    }

    @Test
    fun existsAndIsDirectoryReportState() = withWorkspace { root, workspace ->
        File(root, "sub").mkdirs()
        File(root, "file.txt").writeText("x")

        assertTrue(runBlocking { workspace.exists("sub").getOrThrow() })
        assertTrue(runBlocking { workspace.isDirectory("sub").getOrThrow() })
        assertTrue(runBlocking { workspace.exists("file.txt").getOrThrow() })
        assertFalse(runBlocking { workspace.isDirectory("file.txt").getOrThrow() })
        assertFalse(runBlocking { workspace.exists("ghost").getOrThrow() })
    }

    @Test
    fun descriptionIsRootAbsolutePath() {
        val dir = Files.createTempDirectory("file-workspace-desc").toFile()
        try {
            val workspace = FileWorkspace(dir)
            assertEquals(dir.canonicalFile.absolutePath, workspace.description)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun withWorkspace(block: (root: File, workspace: FileWorkspace) -> Unit) {
        val root = Files.createTempDirectory("file-workspace-test").toFile()
        try {
            block(root, FileWorkspace(root))
        } finally {
            root.deleteRecursively()
        }
    }
}
