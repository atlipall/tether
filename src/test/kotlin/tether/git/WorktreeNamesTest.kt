package tether.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WorktreeNamesTest {
    @Test fun acceptsValidNewNames() {
        assertNull(WorktreeNames.error("feature_1.x-y", setOf("wt-1")))
        assertNull(WorktreeNames.error("wt-2", setOf("wt-1")))
    }

    @Test fun rejectsInvalidNames() {
        for (bad in listOf("", "has space", "a/b", "ü", "-x", ".", "..", "a..b", "x.", "x.lock")) {
            assertNotNull("expected '$bad' to be rejected", WorktreeNames.error(bad, emptySet()))
        }
    }

    @Test fun rejectsExistingName() =
        assertEquals("A worktree named 'wt-1' already exists.", WorktreeNames.error("wt-1", setOf("wt-1")))

    @Test fun rejectsMainInAnyCase() {
        for (name in listOf("main", "MAIN", "Main")) {
            assertEquals("\"main\" is reserved for the project itself.", WorktreeNames.error(name, emptySet()))
        }
    }

    @Test fun suggestionsNeverReserved() {
        assertNull(WorktreeNames.error(WorktreeNames.suggest(emptySet()), emptySet()))
        assertNull(WorktreeNames.error(WorktreeNames.suggest(setOf("wt-1")), setOf("wt-1")))
    }

    @Test fun suggestsFirstFreeWtName() {
        assertEquals("wt-1", WorktreeNames.suggest(emptySet()))
        assertEquals("wt-3", WorktreeNames.suggest(setOf("wt-1", "wt-2", "other")))
    }

    @Test fun typedSeparatorsBecomeDashes() {
        assertEquals("my-new-feature", WorktreeNames.fromTyped("my new feature"))
        assertEquals("a-b-c", WorktreeNames.fromTyped("a\tb\nc"))
        assertEquals("feature-login-x", WorktreeNames.fromTyped("feature/login\\x"))
        assertEquals("wt-1", WorktreeNames.fromTyped("wt-1"))
    }
}
