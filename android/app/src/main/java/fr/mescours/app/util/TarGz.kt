package fr.mescours.app.util

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** Écrit une petite archive .tar.gz (format ustar) en mémoire. */
object TarGz {
    fun create(files: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { gz ->
            val now = System.currentTimeMillis() / 1000
            files.forEach { (name, data) ->
                gz.write(header(name, data.size.toLong(), now))
                gz.write(data)
                val pad = (512 - data.size % 512) % 512
                if (pad > 0) gz.write(ByteArray(pad))
            }
            gz.write(ByteArray(1024)) // deux blocs vides = fin d'archive
        }
        return bos.toByteArray()
    }

    private fun header(name: String, size: Long, mtime: Long): ByteArray {
        require(name.toByteArray().size <= 100) { "Nom trop long pour tar : $name" }
        val h = ByteArray(512)
        fun put(offset: Int, len: Int, value: String) {
            val b = value.toByteArray(Charsets.US_ASCII)
            System.arraycopy(b, 0, h, offset, minOf(b.size, len))
        }
        fun octal(offset: Int, len: Int, value: Long) =
            put(offset, len, value.toString(8).padStart(len - 1, '0'))
        put(0, 100, name)
        octal(100, 8, "644".toLong(8))   // mode
        octal(108, 8, 0)                  // uid
        octal(116, 8, 0)                  // gid
        octal(124, 12, size)
        octal(136, 12, mtime)
        for (i in 148 until 156) h[i] = ' '.code.toByte() // checksum provisoire
        h[156] = '0'.code.toByte()        // fichier normal
        put(257, 6, "ustar")
        put(263, 2, "00")
        val sum = h.sumOf { it.toInt() and 0xff }
        put(148, 8, sum.toString(8).padStart(6, '0') + "\u0000 ")
        return h
    }
}
