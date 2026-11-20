package dev.nova.tv.data.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AtomicFileStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `successful replacement writes the new pool`() {
        val destination = temp.newFile("pool.json")
        destination.writeText("old")
        AtomicFileStore(destination).write("new")
        assertEquals("new", destination.readText())
    }

    @Test
    fun `a failed replacement never deletes the previous destination`() {
        // A non-empty directory at the destination forces renameTo to fail.
        val destination = temp.newFolder("pool.json")
        val original = File(destination, "old").apply { writeText("keep") }
        var reported = ""
        val store = AtomicFileStore(destination) { reported = it }
        assertTrue(runCatching { store.write("new") }.isFailure)
        assertEquals("keep", original.readText())
        assertTrue(reported.contains("could not replace"))
    }
}
