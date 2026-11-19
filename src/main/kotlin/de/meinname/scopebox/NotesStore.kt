package de.meinname.scopebox

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.security.MessageDigest

/** Notes of one project, stored in their own text file (never shared with other projects). */
class NotesStore(project: Project) {
    val file: File = fileFor(project)

    fun load(): String = try {
        if (file.isFile) file.readText(Charsets.UTF_8) else ""
    } catch (t: Throwable) {
        LOG.warn("Notes: could not read " + file, t)
        ""
    }

    fun save(text: String) {
        try {
            file.parentFile.mkdirs()
            file.writeText(text, Charsets.UTF_8)
        } catch (t: Throwable) {
            LOG.warn("Notes: could not write " + file, t)
        }
    }

    companion object {
        private val LOG = Logger.getInstance(NotesStore::class.java)

        private fun fileFor(project: Project): File {
            val name = project.name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "project" }
            val key = project.basePath ?: project.name
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(key.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .substring(0, 8)
            return File(System.getProperty("user.home"), ".louis-coding-essentials/projects/$name-$hash.txt")
        }
    }
}
