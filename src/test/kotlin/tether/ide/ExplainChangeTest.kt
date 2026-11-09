package tether.ide

import com.intellij.diff.util.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tether.ui.WebAssets

class ExplainChangeTest {
    private val modified = ChangedLines(2, 4, 2, 5)
    private val added = ChangedLines(7, 7, 8, 10)
    private val removed = ChangedLines(12, 14, 15, 15)
    private val changes = listOf(modified, added, removed)

    @Test fun aModifiedLineFindsItsHunkOnEitherSide() {
        assertEquals(modified, ChangedLines.at(changes, Side.RIGHT, 4))
        assertEquals(modified, ChangedLines.at(changes, Side.LEFT, 3))
    }

    @Test fun anUnchangedLineHasNoHunk() {
        assertNull(ChangedLines.at(changes, Side.RIGHT, 5))
        assertNull(ChangedLines.at(changes, Side.LEFT, 1))
    }

    @Test fun addedAndRemovedLinesAreFoundOnTheSideThatHasThem() {
        assertEquals(added, ChangedLines.at(changes, Side.RIGHT, 9))
        assertEquals(removed, ChangedLines.at(changes, Side.LEFT, 13))
    }

    @Test fun theUnchangedLineNextToAnAddOrRemoveIsNotPartOfIt() {
        assertNull(ChangedLines.at(changes, Side.LEFT, 7))
        assertNull(ChangedLines.at(changes, Side.RIGHT, 15))
    }

    @Test fun anUnchangedLineMovesByTheChangesAboveIt() {
        assertEquals(1, ChangedLines.afterLineOf(changes, Side.LEFT, 1))
        assertEquals(5, ChangedLines.afterLineOf(changes, Side.LEFT, 4))
        assertEquals(17, ChangedLines.afterLineOf(changes, Side.LEFT, 16))
        assertEquals(16, ChangedLines.afterLineOf(changes, Side.RIGHT, 16))
    }

    @Test fun aDiffHunkIsQuotedWithItsTitles() = assertEquals(
        "Explain why this change at @src/a.kt#L3-4 was made (abc123 → Your version):\n" +
            "```diff\n-val x = 1\n+val x = 2\n+val y = 3\n```\n" +
            "Use the git history of these lines (commit messages, `git log -L`, blame) and the surrounding code. " +
            "Focus on why it changed, not a line-by-line restatement of what changed.",
        TetherMessages.explainChange(
            ChangeToExplain.Hunk("src/a.kt", 3, 4, listOf("val x = 1"), listOf("val x = 2", "val y = 3"), ChangeToExplain.Origin.Diff("abc123", "Your version")),
        ),
    )

    @Test fun anUncommittedChangeSaysSo() {
        val text = TetherMessages.explainChange(
            ChangeToExplain.Hunk("a.kt", 5, 5, listOf("old"), listOf("new"), ChangeToExplain.Origin.Uncommitted),
        )
        assertTrue(text.startsWith("Explain this uncommitted change at @a.kt#L5:\n```diff\n-old\n+new\n```"))
    }

    @Test fun aCommittedLinePointsClaudeAtBlame() {
        val text = TetherMessages.explainChange(ChangeToExplain.CommittedLine("src/a.kt", 42))
        assertTrue(text.startsWith("Explain why @src/a.kt#L42 is the way it is."))
        assertTrue(text.contains("`git blame -L 42,42 -- src/a.kt`"))
    }

    @Test fun aHunkContainingAFenceCannotCloseIt() {
        val text = TetherMessages.explainChange(
            ChangeToExplain.Hunk("a.md", 1, 1, emptyList(), listOf("```"), ChangeToExplain.Origin.Diff()),
        )
        assertEquals(2, Regex("^```", RegexOption.MULTILINE).findAll(text).count())
    }

    @Test fun aHugeHunkIsCut() {
        val text = TetherMessages.explainChange(
            ChangeToExplain.Hunk("a.kt", 1, 500, emptyList(), (1..500).map { "line $it" }, ChangeToExplain.Origin.Diff()),
        )
        assertTrue(text.contains("+line 200\n+… 300 more lines"))
        assertTrue(!text.contains("line 201"))
    }

    /** The webview shows these messages as a collapsed row by matching their wording; see [TetherMessages.explainChange]. */
    @Test fun theChatPageKnowsTheWordingItCollapses() {
        val page = WebAssets.page()
        for (fragment in TetherMessages.EXPLAIN_WORDING) assertTrue(fragment, page.contains(fragment))
    }
}
