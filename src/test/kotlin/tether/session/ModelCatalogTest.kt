package tether.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tether.cli.ModelChoice
import tether.cli.SessionLaunch
import tether.testutil.FakeConnection
import tether.testutil.Fx

class ModelCatalogTest {
    private val connections = mutableListOf<FakeConnection>()
    private val launches = mutableListOf<SessionLaunch>()
    private val timeouts = mutableListOf<Runnable>()
    private val catalog = ModelCatalog { ModelProbe({ launches += it; FakeConnection().also { c -> connections += c } }, "/proj") { timeouts += it } }

    /** Answers the probe's initialize and get_settings requests as claude would. */
    private fun answer(connection: FakeConnection, model: String = "claude-opus-5-5", effort: String? = "high") {
        connection.emit(Fx.initResponseWithModels(connection.lastRequestId("initialize")))
        connection.emit(Fx.settingsResponse(connection.lastRequestId("get_settings"), model, effort))
    }

    @Test fun aProbeAsksClaudeInTheProjectWhatRunsAndThenStops() {
        catalog.want()
        val probe = connections.single()
        answer(probe)
        assertEquals(SessionLaunch(null, null, "/proj"), launches.single())
        assertTrue(probe.stopped)
        assertEquals("no message is sent, so no session is made", emptyList<String>(), probe.userMessages())
        val shown = catalog.preview(null, null)!!
        assertEquals("default" to "high", shown.model to shown.effort)
        assertEquals("Opus 5.5", shown.modelName)
    }

    @Test fun onlyOneProbeRunsAndNoneOnceTheDefaultsAreKnown() {
        catalog.want()
        catalog.want()
        assertEquals(1, connections.size)
        answer(connections[0])
        catalog.want()
        assertEquals(1, connections.size)
    }

    @Test fun aProbeThatFindsNothingLetsALaterOneTry() {
        catalog.want()
        connections[0].exit(1, "boom")
        assertNull(catalog.preview(null, null))
        catalog.want()
        connections[1].emit(Fx.initResponseWithModels(connections[1].lastRequestId("initialize")))
        timeouts.last().run()
        assertTrue("a probe that takes too long is stopped", connections[1].stopped)
        assertNull(catalog.preview(null, null))
        assertEquals(2, connections.size)
    }

    @Test fun thePreviewShowsTheDraftsPicksWhenTheModelTakesThem() {
        catalog.want()
        answer(connections[0])
        assertEquals("sonnet" to "low", catalog.preview("sonnet", "low")!!.let { it.model to it.effort })
        assertEquals("the default effort with a picked model", "high", catalog.preview("sonnet", null)!!.effort)
        assertEquals("a model without effort shows none", null, catalog.preview("claude-haiku-4-5-20251001", "low")!!.effort)
        assertEquals("a model Claude doesn't offer falls back to the default", "default", catalog.preview("gone", null)!!.model)
    }

    @Test fun aChatThatPickedNothingUpdatesTheDefaultsAndTellsTheListeners() {
        var told = 0
        catalog.subscribe { told++ }
        catalog.want()
        answer(connections[0])
        val sonnet = catalog.preview("sonnet", "low")!!
        catalog.remember(sonnet, pickedNothing = false)
        assertEquals("a chat's own picks are not the defaults", "default", catalog.preview(null, null)!!.model)
        catalog.remember(sonnet, pickedNothing = true)
        assertEquals("sonnet", catalog.preview(null, null)!!.model)
        assertEquals(2, told)
    }

    @Test fun aProbeThatAnswersAfterAClearIsNotRemembered() {
        catalog.want()
        catalog.clear()
        connections[0].emit(Fx.initResponseWithModels("probe-init"), Fx.settingsResponse("probe-settings"))
        assertNull(catalog.preview(null, null))
    }

    @Test fun clearingStopsTheProbeAndForgetsTheDefaults() {
        catalog.remember(ModelChoice(emptyList(), "x", null), pickedNothing = true)
        catalog.clear()
        assertNull(catalog.preview(null, null))
        catalog.want()
        catalog.clear()
        assertTrue(connections.single().stopped)
    }
}
