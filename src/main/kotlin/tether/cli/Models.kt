package tether.cli

import kotlinx.serialization.json.JsonObject

/**
 * A model the CLI offers, from its initialize reply. [value] is what `--model` and `set_model` take (an alias such as
 * "sonnet", or a full id); [resolved] is the model it runs; [efforts] are the effort levels it takes, empty for none.
 */
data class ModelOption(val value: String, val resolved: String?, val name: String, val description: String?, val efforts: List<String>)

/** The model and effort a session runs with now, from get_settings' `applied` block; [effort] is null for a model without one. */
data class RunningModel(val model: String, val effort: String?)

/** What the header's pickers show: [model] is one of [options]' values, [effort] the level in use (null for none). */
data class ModelChoice(val options: List<ModelOption>, val model: String, val effort: String?) {
    /** The effort levels the shown model takes; empty hides the effort picker. */
    val efforts: List<String> get() = shown?.efforts.orEmpty()

    /** The option whose value is [model]; null only for a choice built without it. */
    private val shown: ModelOption? get() = options.firstOrNull { it.value == model }

    /**
     * The name of the model the shown option runs, for the composer's chip: the option's own display name ("Fable 5.2"),
     * except for the [DEFAULT_ALIAS], whose name ("Default (recommended)") gives way to that of another option running
     * the same model ("Opus 5.5").
     */
    val modelName: String get() {
        val shown = shown ?: return model
        if (shown.value != DEFAULT_ALIAS || shown.resolved == null) return shown.name
        return options.firstOrNull { it.value != DEFAULT_ALIAS && it.resolved == shown.resolved }?.name ?: shown.name
    }

    companion object {
        /** The CLI's alias for its recommended model, whose display name names the role rather than the model. */
        const val DEFAULT_ALIAS = "default"

        /**
         * The option running [running]'s model: [chosen] (the value the user picked) while it is the one running,
         * otherwise the first that names it. A model no option names (a custom id) is added as its own option.
         */
        fun of(options: List<ModelOption>, chosen: String?, running: RunningModel): ModelChoice {
            fun runs(o: ModelOption) = o.value == running.model || o.resolved == running.model
            val shown = options.firstOrNull { it.value == chosen && runs(it) } ?: options.firstOrNull(::runs)
            if (shown != null) return ModelChoice(options, shown.value, running.effort)
            val levels = if (running.effort == null) emptyList() else options.flatMap { it.efforts }.distinct()
            val own = ModelOption(running.model, running.model, running.model, null, levels)
            return ModelChoice(options + own, own.value, running.effort)
        }
    }
}

object Models {
    /** The models in an initialize reply, in the CLI's order; empty when it lists none (an older CLI). */
    fun options(initResponse: JsonObject): List<ModelOption> =
        initResponse.arr("models").orEmpty().filterIsInstance<JsonObject>().mapNotNull { m ->
            val value = m.str("value")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val efforts = if (m.bool("supportsEffort") == true) m.strings("supportedEffortLevels") else emptyList()
            ModelOption(value, m.str("resolvedModel"), m.str("displayName") ?: value, m.str("description"), efforts)
        }

    /** The model and effort in use from a get_settings reply; null when it has no `applied` model (an older CLI). */
    fun running(settingsResponse: JsonObject): RunningModel? {
        val applied = settingsResponse.obj("applied") ?: return null
        val model = applied.str("model")?.takeIf { it.isNotBlank() } ?: return null
        return RunningModel(model, applied.str("effort")?.takeIf { it.isNotBlank() })
    }
}
