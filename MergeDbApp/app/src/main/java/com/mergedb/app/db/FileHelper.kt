package com.mergedb.app.db

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileHelper {

    private val ts get() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /**
     * Save [data] to the app's external files directory (no permissions required).
     * If a file with [suggestedName] already exists, appends a timestamp before
     * the extension to avoid overwriting.
     *
     * Returns the absolute path of the written file.
     */
    fun save(data: ByteArray, suggestedName: String, context: Context): String {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        dir.mkdirs()

        val file = resolveFile(dir, suggestedName)
        file.writeBytes(data)
        return file.absolutePath
    }

    private fun resolveFile(dir: File, name: String): File {
        val candidate = File(dir, name)
        if (!candidate.exists()) return candidate

        val dot = name.lastIndexOf('.')
        val (base, ext) = if (dot >= 0) name.substring(0, dot) to name.substring(dot)
                          else name to ""
        return File(dir, "${base}_${ts}${ext}")
    }
}
