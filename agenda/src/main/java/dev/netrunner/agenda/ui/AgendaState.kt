package dev.netrunner.agenda.ui

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.netrunner.agenda.data.AgendaPrefs
import dev.netrunner.agenda.data.AskaReminders
import dev.netrunner.agenda.data.CalendarRepo
import dev.netrunner.agenda.data.Contract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shared by the tabs: calendars, hidden ones, and a version bumped whenever the calendar changes. */
class AgendaState(context: Context) {
    private val app = context.applicationContext

    var calendars by mutableStateOf(emptyList<CalendarRepo.Calendar>())
        private set
    var hidden by mutableStateOf(AgendaPrefs.hidden(app))
        private set
    var showAska by mutableStateOf(AgendaPrefs.showAska(app))
        private set
    var version by mutableIntStateOf(0)
        private set

    /** The editor: a draft to create, or an existing contract to edit. */
    var editing by mutableStateOf<Contract?>(null)

    // Reloads when Google sync or another app changes the calendar, or As//ka's reminders change.
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { version++ }
    }

    fun start() {
        runCatching { app.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer) }
        // As//ka's reminders, notified by the launcher's provider.
        runCatching { app.contentResolver.registerContentObserver(android.net.Uri.parse("content://dev.netrunner.launcher.reminders"), true, observer) }
        version++
    }

    fun stop() {
        app.contentResolver.unregisterContentObserver(observer)
    }

    fun refresh() { version++ }

    fun toggleCalendar(id: Long) {
        hidden = if (id in hidden) hidden - id else hidden + id
        AgendaPrefs.setHidden(app, hidden)
    }

    fun toggleAska() {
        showAska = !showAska
        AgendaPrefs.setShowAska(app, showAska)
    }

    /** Calendar instances plus As//ka's reminders between [from] and [to]. */
    suspend fun load(from: Long, to: Long): List<Contract> = withContext(Dispatchers.IO) {
        if (!CalendarRepo.granted(app)) return@withContext emptyList()
        val cals = runCatching { CalendarRepo.calendars(app) }.getOrDefault(emptyList())
        withContext(Dispatchers.Main) { calendars = cals }
        val events = runCatching { CalendarRepo.instances(app, from, to, hidden) }.getOrDefault(emptyList())
        val reminders = if (showAska) AskaReminders.list(app, from, to) else emptyList()
        (events + reminders).sortedBy { it.begin }
    }
}
