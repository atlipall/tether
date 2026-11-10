package tether.session

import tether.cli.SlashCommand
import java.util.concurrent.ConcurrentHashMap

/**
 * The slash commands each agent last reported, shared by a project's chats so a new chat can offer them before its
 * own process starts and reports. Thread-safe: sessions report from their reader threads.
 */
class CommandCatalog {
    private val byAgent = ConcurrentHashMap<String, List<SlashCommand>>()

    /** What [agent] last reported in any chat; empty if it hasn't yet. */
    fun commandsOf(agent: Agent): List<SlashCommand> = byAgent[agent.id].orEmpty()

    /** Keeps [commands] as [agent]'s, unless there are none: a reply without a list says nothing about the others. */
    fun remember(agent: Agent, commands: List<SlashCommand>) {
        if (commands.isNotEmpty()) byAgent[agent.id] = commands
    }

    /** Forgets every agent's commands, as a fresh project has none. */
    fun clear() = byAgent.clear()
}
