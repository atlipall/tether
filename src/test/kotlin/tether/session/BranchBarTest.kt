package tether.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BranchBarTest {
    @Test fun showsOnlyWhenBothBranchesAreKnownAndDiffer() {
        assertNull(BranchBar.of(null, "main", busyHere = false, busyElsewhere = false))
        assertNull(BranchBar.of("main", null, busyHere = false, busyElsewhere = false))
        assertNull(BranchBar.of("main", "main", busyHere = false, busyElsewhere = false))
        assertEquals(BranchBar("feature", "main"), BranchBar.of("feature", "main", busyHere = false, busyElsewhere = false))
    }

    @Test fun switchingWaitsWhileAChatWorksInTheCheckout() {
        assertEquals(BranchBar.THIS_CHAT_BUSY, BranchBar.of("feature", "main", busyHere = true, busyElsewhere = true)?.blocked)
        assertEquals(BranchBar.OTHER_CHAT_BUSY, BranchBar.of("feature", "main", busyHere = false, busyElsewhere = true)?.blocked)
    }

    @Test fun theUncommittedNoteCountsAndNamesTheFirstChanges() {
        assertEquals(
            "Commit or stash your changes first: the checkout has 1 uncommitted change (a.kt), and switching would carry them to the other branch.",
            BranchBar.uncommitted(listOf("a.kt")),
        )
        val many = BranchBar.uncommitted(listOf("a", "b", "c", "d"))
        assertEquals(true, many.contains("4 uncommitted changes (a, b, c, …)"))
    }
}
