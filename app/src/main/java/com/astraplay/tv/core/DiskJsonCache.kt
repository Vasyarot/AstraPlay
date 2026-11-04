package com.astraplay.tv.core

import java.io.File
import java.security.MessageDigest

/**
 * Простейший файловый кэш JSON-строк: переживает убийство процесса
 * (на 1 ГБ приставках система регулярно выгружает фон). Запись атомарная
 * (tmp + rename), ключ — хэш строки. Все операции блокирующие — вызывать
 * только с Dispatchers.IO.
 */
class DiskJsonCache(private val dir: File) {

    fun read(key: String): String? = runCatching {
        val f = file(key)
        if (!f.exists()) null else f.readText()
    }.getOrNull()

    fun write(key: String, value: String) {
        runCatching {
            dir.mkdirs()
            val target = file(key)
            val tmp = File(dir, target.name + ".tmp")
            tmp.writeText(value)
            if (!tmp.renameTo(target)) tmp.delete()
        }
    }

    /** Очистить весь кэш (каталог используется только этим кэшем). */
    fun clear() {
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }

    private fun file(key: String): File {
        val digest = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        return File(dir, digest.joinToString("") { "%02x".format(it) })
    }
}
