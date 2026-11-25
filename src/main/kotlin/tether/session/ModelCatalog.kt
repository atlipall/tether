package tether.session

import tether.cli.ModelChoice
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The models Claude offers and the one it runs, at what effort, when a chat picks none: shared by a project's chats so a
 * draft can show its model and effort before its own process starts. Every Claude chat that picked neither keeps it
 * current; until one has reported, [want] asks a probe made by [newProbe] (null: never probe). Thread-safe.
 */
class ModelCatalog(private val newProbe: (() -> ModelProbe)? = null) {
    @Volatile private var defaults: ModelChoice? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val lock = Any()
    private var probe: ModelProbe? = null

    /**
     * What a draft that picked [model] and [effort] (each null for none) would show: the picked model if Claude offers
     * it, else the default; the picked effort, else the default, if that model takes it. Null until anything is known.
     */
    fun preview(model: String?, effort: String?): ModelChoice? {
        val known = defaults ?: return null
        val shown = model?.takeIf { picked -> known.options.any { it.value == picked } } ?: known.model
        val choice = known.copy(model = shown, effort = null)
        return choice.copy(effort = (effort ?: known.effort)?.takeIf { it in choice.efforts })
    }

    /** Keeps [choice] as what runs by default, when it is what a chat that [pickedNothing] reports, and tells the listeners. */
    fun remember(choice: ModelChoice, pickedNothing: Boolean) {
        if (!pickedNothing || choice == defaults) return
        defaults = choice
        listeners.forEach { it() }
    }

    /**
     * Starts a probe when nothing is known and none is running; one that finds nothing lets a later call try again. A
     * probe [clear] stopped reports nothing.
     */
    fun want() {
        val make = newProbe ?: return
        val started = synchronized(lock) {
            if (defaults != null || probe != null) return
            make().also { probe = it }
        }
        started.start { found ->
            val current = synchronized(lock) { (probe === started).also { if (it) probe = null } }
            if (current) found?.let { remember(it, pickedNothing = true) }
        }
    }

    /** Calls [onChange] whenever the defaults change, on the reporting thread; returns what unsubscribes it. */
    fun subscribe(onChange: () -> Unit): () -> Unit {
        listeners += onChange
        return { listeners -= onChange }
    }

    /** Stops a running probe and forgets the defaults, as a fresh project knows none. */
    fun clear() {
        synchronized(lock) { probe.also { probe = null } }?.stop()
        defaults = null
    }
}
