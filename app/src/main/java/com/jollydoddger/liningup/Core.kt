package com.jollydoddger.liningup

// Everything in this file is plain Kotlin/Java with no Android import, so
// tools/verify.sh can compile and test it off the phone. Keep it that way:
// one `import android.…` here puts all of it out of reach of the only check
// that runs before a push.

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The second question, once the answer to the first is ✅. Left to right. */
enum class Choice(val emoji: String) {
    TONGUE("👅"),
    EGGPLANT("🍆");

    companion object {
        fun of(name: String?): Choice? = entries.firstOrNull { it.name == name }

        /** "TONGUE,EGGPLANT", "EGGPLANT" (what one-choice builds sent), or "-". */
        fun setOf(raw: String?): Set<Choice> =
            raw.orEmpty().split(",").mapNotNull { of(it.trim()) }.toSet()

        fun wire(set: Set<Choice>): String =
            if (set.isEmpty()) "-" else entries.filter { it in set }.joinToString(",") { it.name }
    }
}

/**
 * One person's answer for one day.
 *
 * [ok] is null when the answer was taken back, which has to be sent as well:
 * otherwise the partner's phone keeps showing the ✅ that was withdrawn.
 * [what] is any of 👅 and 🍆, both, or neither, and only ever set under ✅.
 * [at] is when the answer was tapped, which is the time the partner sees; a
 * resend keeps it. [edited] is the last change of any kind, a note included,
 * and is what decides which of two messages is newer.
 */
data class Status(
    val who: String,
    val avatar: String,
    val day: String,
    val ok: Boolean?,
    val what: Set<Choice>,
    val at: Long,
    val edited: Long = at,
    val note: String = "",
) {
    /** What the screen shows: ✅👅🍆, ✅🍆, ✅, ❌, or nothing. */
    val emojis: String
        get() = when (ok) {
            true -> "✅" + Choice.entries.filter { it in what }.joinToString("") { it.emoji }
            false -> "❌"
            null -> ""
        }

    /** Something to show: an answer, or a note on its own. */
    val shown: Boolean get() = ok != null || note.isNotBlank()

    /**
     * Changes when the answer or the note does — not on a resend, and not on a
     * new face, which is worth showing but not worth a notification.
     */
    val fingerprint: String get() = "$who|$day|$ok|${Choice.wire(what)}|$at|${note.hashCode()}"

    /** Tab-separated, versioned. The note travels base64 so it may hold anything. */
    fun wire(): String = listOf(
        WIRE_VERSION, who, avatar.replace(Regex("[\t\n]"), ""), day,
        when (ok) { true -> "1"; false -> "0"; null -> "-" },
        Choice.wire(what), at.toString(), edited.toString(),
        Base64.getEncoder().encodeToString(note.toByteArray(Charsets.UTF_8)),
    ).joinToString("\t")

    companion object {
        const val WIRE_VERSION = "v2"

        /** Long enough for a sentence or three; the relay caps a message at 4 KB. */
        const val NOTE_MAX = 500

        fun parse(text: String?): Status? {
            val p = text?.split("\t") ?: return null
            // v1 is what the first build sent: no edit time, no note.
            val v1 = p.size == 7 && p[0] == "v1"
            val v2 = p.size == 9 && p[0] == WIRE_VERSION
            if (!v1 && !v2) return null
            val ok = when (p[4]) { "1" -> true; "0" -> false; "-" -> null; else -> return null }
            val at = p[6].toLongOrNull() ?: return null
            if (p[1].isBlank() || p[3].isBlank()) return null
            val edited = if (v2) p[7].toLongOrNull() ?: return null else at
            val note = if (v2) runCatching {
                String(Base64.getDecoder().decode(p[8]), Charsets.UTF_8)
            }.getOrNull() ?: return null else ""
            return Status(
                p[1], p[2], p[3], ok,
                if (ok == true) Choice.setOf(p[5]) else emptySet(),
                at, edited, note.take(NOTE_MAX),
            )
        }
    }
}

/**
 * The shared secret two phones are paired by.
 *
 * Messages go through ntfy.sh, a public relay, so the code does two jobs: it
 * names a topic nobody could guess, and it is the key every message is sealed
 * with. Somebody who stumbled on the topic would see random bytes, not who is
 * in the mood. The relay never sees the code itself, only a hash of it.
 */
class Couple(code: String) {
    val code: String = Pairing.normalise(code)
    val topic: String = "lu-" + hex(sha256("lining-up/topic/" + this.code)).take(32)
    private val key = SecretKeySpec(sha256("lining-up/key/" + this.code), "AES")

    fun seal(plain: String): String {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(iv + body)
    }

    /** Null for anything that was not sealed with this couple's code. */
    fun open(sealed: String): String? = runCatching {
        val bytes = Base64.getUrlDecoder().decode(sealed.trim())
        if (bytes.size < 12 + 16) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, 12))
        String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        val random = SecureRandom()
        fun sha256(s: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    }
}

object Pairing {
    /** No 0/O, 1/I/L: the code gets read off one screen and typed into another. */
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val LENGTH = 20 // 20 × log2(31) ≈ 99 bits

    fun newCode(random: java.util.Random = SecureRandom()): String =
        (1..LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

    private val GROUPED = Regex("[$ALPHABET]{4}(?:[-\\s]?[$ALPHABET]{4}){4}")

    /**
     * Whatever was pasted — spaces, dashes, lower case, a sentence round it.
     * A grouped code inside a message is found first, because the words round
     * it ("Lining up") are made of letters the code itself uses.
     */
    fun normalise(raw: String): String {
        val upper = raw.uppercase()
        val found = GROUPED.find(upper)?.value ?: upper
        return found.filter { it in ALPHABET }
    }

    fun isValid(raw: String): Boolean = normalise(raw).length == LENGTH

    fun pretty(code: String): String = normalise(code).chunked(4).joinToString("-")
}

object Days {
    /** The day rolls over at this hour rather than at midnight. */
    const val RESET_HOUR = 2

    /** "2026-11-06" until 01:59 on the 7th. */
    fun key(nowMs: Long, zone: ZoneId = ZoneId.systemDefault(), resetHour: Int = RESET_HOUR): String =
        Instant.ofEpochMilli(nowMs).atZone(zone).minusHours(resetHour.toLong()).toLocalDate().toString()

    fun clock(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))

    /** The next time the clock reads [hour]:[minute], strictly after now. */
    fun nextAt(nowMs: Long, hour: Int, minute: Int, zone: ZoneId = ZoneId.systemDefault()): Long {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        var at: ZonedDateTime = now.with(LocalTime.of(hour, minute))
        if (!at.isAfter(now)) at = at.plusDays(1).with(LocalTime.of(hour, minute))
        return at.toInstant().toEpochMilli()
    }
}

object Statuses {
    /**
     * The partner's latest answer for [day]: whoever is not [me], newest
     * change wins. Older days are ignored, which is what makes the 2am reset happen
     * without anybody having to send anything.
     */
    fun partner(all: List<Status>, me: String, day: String): Status? =
        all.filter { it.who != me && it.day == day }.maxByOrNull { it.edited }

    fun mine(all: List<Status>, me: String, day: String): Status? =
        all.filter { it.who == me && it.day == day }.maxByOrNull { it.edited }

    /** Both said ✅ today. */
    fun isMatch(mine: Status?, theirs: Status?, day: String): Boolean =
        mine?.day == day && theirs?.day == day && mine.ok == true && theirs.ok == true

    /** ntfy.sh keeps a message 12 hours; resending well inside that keeps it there. */
    const val REPUBLISH_AFTER_MS = 3 * 60 * 60 * 1000L

    fun shouldRepublish(mine: Status?, day: String, lastPublishedAt: Long, nowMs: Long): Boolean =
        mine != null && mine.day == day && nowMs - lastPublishedAt >= REPUBLISH_AFTER_MS
}
