package tether.cli

import org.junit.Assert.assertEquals
import org.junit.Test

class SlashCommandsTest {
    private fun response(json: String) = parseJsonObject(json)!!

    @Test fun commandsComeFromTheInitializeReply() {
        val reply = response("""{"commands":[
            {"name":"simplify","description":"Instructions for simplifying code. (user)","argumentHint":""},
            {"name":"review","description":"Review a pull request","argumentHint":"<pr>"}
        ]}""")
        assertEquals(
            listOf(
                SlashCommand("simplify", "Instructions for simplifying code. (user)", ""),
                SlashCommand("review", "Review a pull request", "<pr>"),
            ),
            SlashCommands.fromInitialize(reply),
        )
    }

    @Test fun commandsWithoutANameAreSkippedAndAMissingListIsEmpty() {
        assertEquals(listOf("x"), SlashCommands.fromInitialize(response("""{"commands":[{"description":"?"},{"name":" "},{"name":"x"}]}""")).map { it.name })
        assertEquals(SlashCommand("x", "", ""), SlashCommands.fromInitialize(response("""{"commands":[{"name":"x"}]}""")).single())
        assertEquals(emptyList<SlashCommand>(), SlashCommands.fromInitialize(response("""{"models":[]}""")))
    }

    @Test fun aLeadingSlashInANameIsDropped() {
        assertEquals("compact", SlashCommands.fromInitialize(response("""{"commands":[{"name":"/compact"}]}""")).single().name)
    }

    @Test fun acpCommandsTakeTheirHintFromTheInput() {
        val update = response("""{"sessionUpdate":"available_commands_update","availableCommands":[
            {"name":"web","description":"Search the web","input":{"hint":"query to search for"}},
            {"name":"plan","description":"Make a plan"}
        ]}""")
        assertEquals(
            listOf(SlashCommand("web", "Search the web", "query to search for"), SlashCommand("plan", "Make a plan", "")),
            SlashCommands.fromAcp(update),
        )
    }
}
