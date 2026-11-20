package dev.nova.tv.data.store

import java.io.File

/**
 * A JSON file that is only ever replaced, never rewritten in place.
 *
 * Both pools save through this. The old code wrote the real file whenever the
 * rename failed, which is the one branch that runs when the filesystem is
 * already unhappy -- and a truncated write there loses the whole pool instead of
 * one save. A pool that is one save out of date is a bad evening; a pool that is
 * half a JSON document is a crash on next launch, because nothing can decode it.
 *
 * Write a temporary file and rename it over the destination. If either step
 * fails, leave the previous file in place and report the error. Never delete the
 * destination as a fallback: a second failed rename would lose the old pool.
 */
class AtomicFileStore(
    private val file: File,
    private val onError: (String) -> Unit = {}
) {

    fun read(): String? = if (file.isFile) runCatching { file.readText() }.getOrNull() else null

    fun write(text: String) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        try {
            temporary.writeText(text)
        } catch (error: Exception) {
            val reason = "could not write ${temporary.name}"
            onError(reason)
            throw java.io.IOException(reason, error)
        }
        if (temporary.renameTo(file)) return
        val reason = "could not replace ${file.name}; the previous version is still in place"
        onError(reason)
        temporary.delete()
        throw java.io.IOException(reason)
    }
}
