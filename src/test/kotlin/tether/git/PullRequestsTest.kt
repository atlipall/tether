package tether.git

import tether.git.PullRequest.Status
import tether.git.PullRequestLookup.Failed
import tether.git.PullRequestLookup.Found
import tether.git.PullRequestLookup.None
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class PullRequestsTest {
    private fun entry(number: Int, state: String, isDraft: Boolean = false, title: String = "t", url: String = "https://github.com/o/r/pull/$number") =
        """{"number":$number,"state":"$state","isDraft":$isDraft,"title":"$title","url":"$url"}"""

    @Test fun parsesAnOpenPullRequest() {
        assertEquals(Found(PullRequest(12, "https://github.com/o/r/pull/12", "Add a button", Status.OPEN)),
            PullRequests.parse("[${entry(12, "OPEN", title = "Add a button")}]"))
    }

    @Test fun anOpenDraftIsADraft() {
        assertEquals(Status.DRAFT, (PullRequests.parse("[${entry(12, "OPEN", isDraft = true)}]") as Found).pullRequest.status)
    }

    @Test fun keepsAMergedPullRequest() {
        assertEquals(Status.MERGED, (PullRequests.parse("[${entry(3, "MERGED")}]") as Found).pullRequest.status)
    }

    @Test fun aMissingTitleIsEmpty() {
        assertEquals("", (PullRequests.parse("""[{"number":3,"state":"OPEN","url":"https://github.com/o/r/pull/3"}]""") as Found).pullRequest.title)
    }

    @Test fun skipsANewerClosedPullRequestForAnOlderMergedOne() {
        assertEquals(Found(PullRequest(3, "https://github.com/o/r/pull/3", "t", Status.MERGED)),
            PullRequests.parse("[${entry(4, "CLOSED")},${entry(3, "MERGED")}]"))
    }

    @Test fun onlyClosedOnesOrNoneIsNone() {
        assertEquals(None, PullRequests.parse("[${entry(3, "CLOSED")}]"))
        assertEquals(None, PullRequests.parse("[]"))
    }

    @Test fun dropsAnUrlThatIsNotHttps() {
        assertEquals(None, PullRequests.parse("[${entry(3, "OPEN", url = "javascript:alert(1)")}]"))
    }

    @Test fun outputOfAnotherShapeIsAFailure() {
        assertEquals(Failed, PullRequests.parse("no pull requests found"))
        assertEquals(Failed, PullRequests.parse(entry(3, "OPEN")))
    }

    @Test fun asksGhForTheBranchsPullRequests() {
        val calls = mutableListOf<Pair<File, List<String>>>()
        val found = PullRequests { dir, args ->
            calls += dir to args
            "[${entry(7, "OPEN", title = "Seven")}]"
        }.find("/repo", "feature/x")
        assertEquals(Found(PullRequest(7, "https://github.com/o/r/pull/7", "Seven", Status.OPEN)), found)
        assertEquals(listOf(File("/repo") to listOf("pr", "list", "--head", "feature/x", "--state", "all", "--limit", "5",
            "--json", "number,url,title,state,isDraft")), calls)
    }

    @Test fun aFailingGhIsAFailure() {
        assertEquals(Failed, PullRequests { _, _ -> null }.find("/repo", "main"))
    }
}
