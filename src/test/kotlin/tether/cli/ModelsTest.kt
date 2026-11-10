package tether.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tether.testutil.Fx

class ModelsTest {
    private fun response(line: String) = parseJsonObject(line)!!.obj("response")!!.obj("response")!!

    private val options = Models.options(response(Fx.initResponseWithModels("i")))

    @Test fun optionsComeFromTheInitializeReply() {
        assertEquals(listOf("default", "opus", "sonnet", "claude-sonnet-4-6", "claude-haiku-4-5-20251001"), options.map { it.value })
        assertEquals(ModelOption("sonnet", "claude-sonnet-5-5", "Sonnet 5.5", "Most efficient for simpler tasks", listOf("low", "medium", "high", "xhigh", "max")), options[2])
        assertEquals(listOf("low", "medium", "high", "max"), options[3].efforts)
        assertEquals("a model without effort has no levels", emptyList<String>(), options[4].efforts)
    }

    @Test fun optionsWithoutAValueAreSkippedAndAMissingListIsEmpty() {
        assertEquals(listOf("x"), Models.options(response(Fx.initResponseWithModels("i", """[{"displayName":"?"},{"value":"x"}]"""))).map { it.value })
        assertEquals("x", Models.options(response(Fx.initResponseWithModels("i", """[{"value":"x"}]"""))).single().name)
        assertEquals(emptyList<ModelOption>(), Models.options(response(Fx.initResponse("i"))))
    }

    @Test fun theRunningModelComesFromGetSettings() {
        assertEquals(RunningModel("claude-sonnet-5-5", "low"), Models.running(response(Fx.settingsResponse("s", "claude-sonnet-5-5", "low"))))
        assertEquals(RunningModel("claude-haiku-4-5-20251001", null), Models.running(response(Fx.settingsResponse("s", "claude-haiku-4-5-20251001", null))))
        assertNull("no applied block: an older CLI", Models.running(response(Fx.modeResponse("s", "plan"))))
    }

    @Test fun theFirstOptionRunningTheModelIsShown() {
        val choice = ModelChoice.of(options, chosen = null, RunningModel("claude-opus-5-5", "high"))
        assertEquals("default", choice.model)
        assertEquals("high", choice.effort)
        assertEquals(options, choice.options)
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), choice.efforts)
    }

    @Test fun theChosenOptionIsShownWhileItIsTheOneRunning() {
        assertEquals("opus", ModelChoice.of(options, chosen = "opus", RunningModel("claude-opus-5-5", "high")).model)
        assertEquals("sonnet", ModelChoice.of(options, chosen = "sonnet", RunningModel("claude-sonnet-5-5", "high")).model)
        assertEquals("a choice that isn't running gives way", "default", ModelChoice.of(options, chosen = "sonnet", RunningModel("claude-opus-5-5", "high")).model)
        assertEquals("an option can be named by its own id", "claude-sonnet-4-6", ModelChoice.of(options, null, RunningModel("claude-sonnet-4-6", "max")).model)
    }

    @Test fun aModelOutsideTheListIsAddedAsItsOwnOption() {
        val choice = ModelChoice.of(options, chosen = null, RunningModel("claude-opus-5-5[1m]", "high"))
        assertEquals("claude-opus-5-5[1m]", choice.model)
        assertEquals(ModelOption("claude-opus-5-5[1m]", "claude-opus-5-5[1m]", "claude-opus-5-5[1m]", null, listOf("low", "medium", "high", "xhigh", "max")), choice.options.last())
        assertEquals(options.size + 1, choice.options.size)
        assertEquals("without effort it offers none", emptyList<String>(), ModelChoice.of(options, null, RunningModel("custom", null)).efforts)
    }

    @Test fun theChipNamesTheModelRatherThanTheDefaultAlias() {
        assertEquals("Opus 5.5", ModelChoice.of(options, null, RunningModel("claude-opus-5-5", "high")).modelName)
        assertEquals("Sonnet 5.5", ModelChoice.of(options, "sonnet", RunningModel("claude-sonnet-5-5", "high")).modelName)
        val fable = listOf(ModelOption("default", "claude-fable-5-2", "Default (recommended)", "For your toughest challenges", emptyList()),
            ModelOption("fable", "claude-fable-5-2", "Fable 5.2", "For your toughest challenges", emptyList()))
        assertEquals("Fable 5.2", ModelChoice(fable, "default", null).modelName)
        assertEquals("Fable 5.2", ModelChoice(fable, "fable", null).modelName)
        assertEquals("a default no other option names keeps its own name", "Default (recommended)", ModelChoice(fable.take(1), "default", null).modelName)
        assertEquals("a custom id is its own name", "claude-opus-5-5[1m]", ModelChoice.of(options, null, RunningModel("claude-opus-5-5[1m]", "high")).modelName)
    }

    @Test fun aModelWithoutEffortOffersNoLevels() =
        assertEquals(emptyList<String>(), ModelChoice.of(options, null, RunningModel("claude-haiku-4-5-20251001", null)).efforts)
}
