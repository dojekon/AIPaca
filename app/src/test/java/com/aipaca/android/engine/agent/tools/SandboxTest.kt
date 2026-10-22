package com.aipaca.android.engine.agent.tools

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxTest {

    @Test
    fun emptyPathResolvesToRoot() {
        withTempDir { dir ->
            val root = File(dir, "agent").apply { mkdirs() }
            val result = resolveInSandbox(root, "")
            assertTrue(result.isSuccess)
            assertEquals(root.canonicalFile, result.getOrThrow())
        }
    }

    @Test
    fun blankPathResolvesToRoot() {
        withTempDir { dir ->
            val root = File(dir, "agent").apply { mkdirs() }
            val result = resolveInSandbox(root, "   ")
            assertTrue(result.isSuccess)
            assertEquals(root.canonicalFile, result.getOrThrow())
        }
    }

    @Test
    fun simpleNestedPathStaysInside() {
        withTempDir { dir ->
            val root = File(dir, "agent").apply { mkdirs() }
            val result = resolveInSandbox(root, "notes/today.txt")
            assertTrue(result.isSuccess)
            val target = result.getOrThrow()
            assertTrue(target.path.startsWith(root.canonicalPath + File.separator))
            assertEquals(File(root.canonicalFile, "notes/today.txt").canonicalFile, target)
        }
    }

    @Test
    fun parentTraversalRejected() {
        withTempDir { dir ->
            val root = File(dir, "agent").apply { mkdirs() }
            val result = resolveInSandbox(root, "../outside")
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun nestedParentTraversalRejected() {
        withTempDir { dir ->
            val root = File(dir, "agent").apply { mkdirs() }
            val result = resolveInSandbox(root, "a/../../outside")
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun absolutePathRejected() {
        withTempDir { dir ->
            val root = File(dir, "agent").apply { mkdirs() }
            val result = resolveInSandbox(root, "/etc/passwd")
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun absolutePathInsideSandboxIsAccepted() {
        withTempDir { dir ->
            val root = File(dir, "agent").apply { mkdirs() }
            val result = resolveInSandbox(root, File(root, "notes/a.txt").absolutePath)
            assertEquals(File(root, "notes/a.txt").canonicalFile, result.getOrThrow())
        }
    }

    @Test
    fun absoluteSandboxRootResolvesToRoot() {
        withTempDir { dir ->
            val root = File(dir, "agent").apply { mkdirs() }
            assertEquals(root.canonicalFile, resolveInSandbox(root, root.absolutePath + "/").getOrThrow())
            assertEquals(root.canonicalFile, resolveInSandbox(root, ".").getOrThrow())
        }
    }

    @Test
    fun absolutePathInsideSandboxStillRejectsTraversal() {
        withTempDir { dir ->
            val root = File(dir, "agent").apply { mkdirs() }
            val result = resolveInSandbox(root, root.absolutePath + "/../secret")
            assertTrue(result.isFailure)
        }
    }

    @Test
    fun siblingDirectoryWithSamePrefixIsRejected() {
        withTempDir { dir ->
            val root = File(dir, "agent").apply { mkdirs() }
            val result = resolveInSandbox(root, File(dir, "agent2/x").absolutePath)
            assertTrue(result.isFailure)
        }
    }

    @Test
    fun windowsAbsolutePathRejected() {
        withTempDir { dir ->
            val root = File(dir, "agent").apply { mkdirs() }
            val result = resolveInSandbox(root, """C:\Windows\system32""")
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun siblingPrefixAttackRejected() {
        withTempDir { dir ->
            val root = File(dir, "agent").apply { mkdirs() }
            File(dir, "agent-evil").apply { mkdirs() }
            val result = resolveInSandbox(root, "../agent-evil/x")
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun existingFileInsideRootResolves() {
        withTempDir { dir ->
            val root = File(dir, "agent").apply { mkdirs() }
            val file = File(root, "existing.txt").apply { writeText("data") }
            val result = resolveInSandbox(root, "existing.txt")
            assertTrue(result.isSuccess)
            assertEquals(file.canonicalFile, result.getOrThrow())
        }
    }

    @Test
    fun symlinkEscapeRejected() {
        withTempDir { dir ->
            val root = File(dir, "agent").apply { mkdirs() }
            val outside = File(dir, "outside").apply { mkdirs() }
            File(outside, "secret.txt").writeText("secret")
            Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
            val result = resolveInSandbox(root, "link/secret.txt")
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun nonexistentNestedPathAllowed() {
        withTempDir { dir ->
            val root = File(dir, "agent").apply { mkdirs() }
            val result = resolveInSandbox(root, "new/dir/file.txt")
            assertTrue(result.isSuccess)
            val target = result.getOrThrow()
            assertTrue(target.path.startsWith(root.canonicalPath + File.separator))
            assertTrue(!target.exists())
        }
    }

    private fun withTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("sandbox-test").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}
