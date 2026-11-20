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
 * So: write the temporary file, try to swap it in, and if the swap fails, delete
 * the destination and swap once more (the same result a POSIX rename gives). If
 * that also fails, the write is abandoned and reported through [onError]. The old
 * file stays intact and valid.
 */
class AtomicFileStore(
    private val file: File,
    private val onError: (String) -> Unit = {}
) {

    fun read(): String? = if (file.isFile) runCatching { file.readText() }.getOrNull() else null

    fun write(text: String) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        val written = runCatching { temporary.writeText(text) }.isSuccess
        if (!written) {
            onError("could not write ${temporary.name}")
            return
        }
        if (temporary.renameTo(file)) return
        // Java's renameTo refuses to replace an existing file on some
        // filesystems, so remove it and try the same swap again.
        if (file.delete() && temporary.renameTo(file)) return
        onError("could not replace ${file.name}; the previous version is still in place")
    }
}
