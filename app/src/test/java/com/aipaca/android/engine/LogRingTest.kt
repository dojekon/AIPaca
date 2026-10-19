package com.aipaca.android.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LogRingTest {

    @Test
    fun addKeepsOnlyLastLinesInOrder() {
        val ring = LogRing(capacity = 500)

        repeat(600) { index -> ring.add("line-$index") }

        val snapshot = ring.snapshot()
        assertEquals(500, snapshot.size)
        assertEquals((100 until 600).map { "line-$it" }, snapshot)
    }

    @Test
    fun snapshotIsDetachedFromRing() {
        val ring = LogRing(capacity = 3)
        ring.add("a")
        ring.add("b")

        val copy = ring.snapshot().toMutableList()
        copy.add("c")
        copy[0] = "changed"

        assertEquals(listOf("a", "b"), ring.snapshot())
    }

    @Test
    fun snapshotCannotBeMutated() {
        val ring = LogRing(capacity = 2)
        ring.add("a")

        val snapshot = ring.snapshot()

        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot as MutableList<String>).add("b")
        }
    }

    @Test
    fun addOverwritesOldestLineWhenFull() {
        val ring = LogRing(capacity = 2)
        ring.add("a")
        ring.add("b")
        ring.add("c")

        assertEquals(listOf("b", "c"), ring.snapshot())
    }
}
