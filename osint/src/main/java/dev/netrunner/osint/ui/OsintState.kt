package dev.netrunner.osint.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.netrunner.osint.data.Case
import dev.netrunner.osint.data.Casebook
import dev.netrunner.osint.data.Module
import dev.netrunner.osint.data.Recon
import dev.netrunner.osint.data.Report
import dev.netrunner.osint.data.Target
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** What the screens share: the current target and its results, and the case notebook. */
class OsintState(context: Context) {
    private val app = context.applicationContext

    var query by mutableStateOf("")
    var target by mutableStateOf<Target?>(null)
        private set
    /** Results of the current target, in module order. */
    val results = mutableStateListOf<Report>()
    val running = mutableStateListOf<Module>()

    var case by mutableStateOf(Casebook.load(app))
        private set

    /** Runs [modules] on what was typed; a new target clears the previous results. */
    suspend fun lookup(modules: List<Module>) {
        val t = Target.parse(query) ?: return
        if (t != target) {
            target = t
            results.clear()
        }
        val todo = modules.filter { it.applies(t) && it !in running }
        running += todo
        coroutineScope {
            todo.map { m ->
                async {
                    val r = Recon.run(m, t)
                    running -= m
                    results.removeAll { it.module == r.module }
                    // Keeps the modules' order whatever finishes first.
                    val at = results.indexOfFirst { other -> Module.entries.indexOfFirst { it.label == other.module } > m.ordinal }
                    if (at < 0) results += r else results.add(at, r)
                }
            }.awaitAll()
        }
    }

    fun clearResults() {
        results.clear()
        target = null
    }

    // ---- Notebook ----

    fun rename(name: String) = update(case.copy(name = name))

    fun add(report: Report) = add(Case.Entry(System.nanoTime(), System.currentTimeMillis(), "", report))

    fun addNote(text: String) {
        if (text.isNotBlank()) add(Case.Entry(System.nanoTime(), System.currentTimeMillis(), text.trim()))
    }

    private fun add(entry: Case.Entry) = update(case.copy(entries = case.entries + entry))

    fun remove(entry: Case.Entry) = update(case.copy(entries = case.entries.filterNot { it.id == entry.id }))

    fun isSaved(report: Report) = case.entries.any { it.report == report }

    fun newCase() = update(Case("", emptyList()))

    private fun update(c: Case) {
        case = c
        Casebook.save(app, c)
    }
}
