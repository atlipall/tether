package tether.cli

import kotlinx.serialization.json.JsonObject

/**
 * A command the agent runs when a message starts with `/name`, such as a skill or a custom command. [description]
 * says what it does and [hint] what it takes after the name; both are empty when the agent gives none.
 */
data class SlashCommand(val name: String, val description: String, val hint: String)

object SlashCommands {
    /** The commands in claude's initialize reply, in its order; empty when it lists none. */
    fun fromInitialize(initResponse: JsonObject): List<SlashCommand> =
        initResponse.arr("commands").orEmpty().filterIsInstance<JsonObject>().mapNotNull { c ->
            command(c.str("name"), c.str("description"), c.str("argumentHint"))
        }

    /** The commands in an ACP `available_commands_update`, whose hint is under `input`. */
    fun fromAcp(update: JsonObject): List<SlashCommand> =
        update.arr("availableCommands").orEmpty().filterIsInstance<JsonObject>().mapNotNull { c ->
            command(c.str("name"), c.str("description"), c.obj("input")?.str("hint"))
        }

    /** A command named [name] without its leading slash; null when the name is blank. */
    private fun command(name: String?, description: String?, hint: String?): SlashCommand? {
        val bare = name?.trim()?.removePrefix("/")?.takeIf { it.isNotEmpty() } ?: return null
        return SlashCommand(bare, description.orEmpty(), hint.orEmpty())
    }
}
