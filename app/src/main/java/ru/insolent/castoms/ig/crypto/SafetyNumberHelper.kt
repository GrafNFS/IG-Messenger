package ru.insolent.castoms.ig.crypto

import android.util.Base64
import java.security.MessageDigest

/** Safety number (как у Signal): SHA-256 от отсортированной конкатенации двух
 *  публичных identity-ключей; первые 30 байт дайджеста → 60 цифр (2 на байт).
 *  Оба участника видят ОДИНАКОВОЕ число, только если сервер ключей не подменил ключи. */
object SafetyNumberHelper {

    fun compute(myIdentityPublicB64: String, theirIdentityPublicB64: String): String {
        val mine = Base64.decode(myIdentityPublicB64, Base64.NO_WRAP)
        val theirs = Base64.decode(theirIdentityPublicB64, Base64.NO_WRAP)

        val (first, second) = if (bytesCompare(mine, theirs) <= 0) mine to theirs else theirs to mine
        val digest = MessageDigest.getInstance("SHA-256").digest(first + second)

        val sb = StringBuilder()
        for (i in 0 until 30) {
            sb.append(((digest[i].toInt() and 0xFF) % 100).toString().padStart(2, '0'))
        }
        return sb.toString() // 60 цифр
    }

    fun format(number: String): String =
        number.chunked(5).chunked(4).joinToString("\n") { it.joinToString("  ") }

    private fun bytesCompare(a: ByteArray, b: ByteArray): Int {
        val len = minOf(a.size, b.size)
        for (i in 0 until len) { val c = a[i].compareTo(b[i]); if (c != 0) return c }
        return a.size.compareTo(b.size)
    }
}