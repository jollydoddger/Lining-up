package com.jollydoddger.liningup

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/** What the screen draws. Rebuilt from prefs whenever anything changes. */
data class Snapshot(
    val paired: Boolean,
    val code: String,
    val avatar: String,
    val day: String,
    val mine: Status?,
    val theirs: Status?,
    val lastHeardAt: Long,
    val lastError: String?,
    val reminderOn: Boolean,
    val reminderHour: Int,
    val reminderMinute: Int,
    val pending: Boolean,
) {
    val match: Boolean get() = Statuses.isMatch(mine, theirs, day)
}

/**
 * All state, in SharedPreferences. Two people, one answer each per day — there
 * is nothing here a database would help with.
 */
object Store {
    private const val FILE = "lining_up"
    private lateinit var prefs: SharedPreferences
    private val _state = MutableStateFlow<Snapshot?>(null)
    val state: StateFlow<Snapshot?> get() = _state

    /** Set by MainActivity, so a notification is not raised over the open app. */
    @Volatile var foreground = false

    val AVATARS = listOf("🦊", "🐻", "🐱", "🐰", "🦁", "🐼", "🐸", "🦄", "🐯", "🐨", "🐙", "🌸", "🔥", "🌙")

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (prefs.getString("me", null) == null) {
            prefs.edit().putString("me", UUID.randomUUID().toString()).commit()
        }
        refresh()
    }

    val me: String get() = prefs.getString("me", "")!!
    val code: String get() = prefs.getString("code", "") ?: ""
    val couple: Couple? get() = code.takeIf { Pairing.isValid(it) }?.let { Couple(it) }
    val avatar: String get() = prefs.getString("avatar", AVATARS[0])!!

    fun today(now: Long = System.currentTimeMillis()) = Days.key(now)

    fun mine(): Status? = Status.parse(prefs.getString("mine", null))?.takeIf { it.day == today() }
    /** My last answer whatever its day — what a pending send has to carry. */
    fun latestMine(): Status? = Status.parse(prefs.getString("mine", null))
    fun theirs(): Status? = Status.parse(prefs.getString("theirs", null))?.takeIf { it.day == today() }

    /** Whether my latest answer still has to reach the relay. */
    val dirty: Boolean get() = prefs.getBoolean("dirty", false)
    val lastPublishedAt: Long get() = prefs.getLong("publishedAt", 0)

    val reminderOn: Boolean get() = prefs.getBoolean("reminderOn", true)
    val reminderHour: Int get() = prefs.getInt("reminderHour", 20)
    val reminderMinute: Int get() = prefs.getInt("reminderMinute", 0)

    fun setCode(code: String) {
        // A new pairing starts clean: the old partner's answer is not this one's.
        prefs.edit().putString("code", Pairing.normalise(code)).remove("theirs").remove("seen")
            .remove("matchDay").putBoolean("dirty", mine() != null).apply()
        refresh()
    }

    fun unpair() {
        prefs.edit().remove("code").remove("theirs").remove("seen").remove("matchDay").apply()
        refresh()
    }

    fun setAvatar(a: String) {
        prefs.edit().putString("avatar", a).apply()
        // Resend so the partner's screen shows the new face too.
        mine()?.let { prefs.edit().putString("mine", it.copy(avatar = a).wire()).putBoolean("dirty", true).apply() }
        refresh()
    }

    fun setReminder(on: Boolean, hour: Int = reminderHour, minute: Int = reminderMinute) {
        prefs.edit().putBoolean("reminderOn", on).putInt("reminderHour", hour)
            .putInt("reminderMinute", minute).apply()
        refresh()
    }

    /** The one way an answer is made, from the screen or from the popup. */
    fun answer(ok: Boolean?, what: Choice?) {
        val s = Status(me, avatar, today(), ok, if (ok == true) what else null, System.currentTimeMillis())
        prefs.edit().putString("mine", s.wire()).putBoolean("dirty", true).commit()
        refresh()
    }

    fun markPublished(at: Long) {
        prefs.edit().putBoolean("dirty", false).putLong("publishedAt", at).commit()
        refresh()
    }

    fun heard(theirs: Status?, at: Long) {
        val e = prefs.edit().putLong("heardAt", at).remove("error")
        if (theirs != null) e.putString("theirs", theirs.wire())
        e.commit()
        refresh()
    }

    fun failed(why: String) {
        prefs.edit().putString("error", why).commit()
        refresh()
    }

    /** True the first time a given answer of theirs is seen; false after. */
    fun firstSight(s: Status): Boolean {
        if (prefs.getString("seen", null) == s.fingerprint) return false
        prefs.edit().putString("seen", s.fingerprint).commit()
        return true
    }

    /** True once per day, the first time both have said ✅. */
    fun firstMatchToday(): Boolean {
        val day = today()
        if (prefs.getString("matchDay", null) == day) return false
        prefs.edit().putString("matchDay", day).commit()
        return true
    }

    fun refresh() {
        _state.value = Snapshot(
            paired = couple != null,
            code = code,
            avatar = avatar,
            day = today(),
            mine = mine(),
            theirs = theirs(),
            lastHeardAt = prefs.getLong("heardAt", 0),
            lastError = prefs.getString("error", null),
            reminderOn = reminderOn,
            reminderHour = reminderHour,
            reminderMinute = reminderMinute,
            pending = dirty,
        )
    }
}
