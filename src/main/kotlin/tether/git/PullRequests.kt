package tether.git

import tether.cli.bool
import tether.cli.long
import tether.cli.str
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.util.EnvironmentUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.FileFilter

/** A GitHub pull request for a checkout's branch. */
data class PullRequest(val number: Int, val url: String, val title: String, val status: Status) {
    /** Where the pull request stands; a closed, unmerged one is never shown. */
    enum class Status { OPEN, DRAFT, MERGED }
}

/** What asking GitHub for a branch's pull request found. */
sealed interface PullRequestLookup {
    data class Found(val pullRequest: PullRequest) : PullRequestLookup

    /** GitHub answered: the branch has no open or merged pull request. */
    data object None : PullRequestLookup

    /** No answer: `gh` is missing, not signed in, offline, or this is not a GitHub checkout. */
    data object Failed : PullRequestLookup
}

/** Runs `gh` with these arguments in a folder and returns its stdout, or null when gh is missing or fails. */
typealias GhRunner = (dir: File, args: List<String>) -> String?

/** Finds the GitHub pull request for a branch, through the GitHub CLI (`gh`). */
class PullRequests(private val gh: GhRunner = ::runGh) {
    /**
     * The newest open or merged pull request from [branch] in [dir]'s GitHub repository. Blocks for up to
     * [GH_TIMEOUT_SECONDS]: call it off the EDT.
     */
    fun find(dir: String, branch: String): PullRequestLookup {
        val out = gh(File(dir), listOf("pr", "list", "--head", branch, "--state", "all", "--limit", "5", "--json", FIELDS))
            ?: return PullRequestLookup.Failed
        return parse(out)
    }

    companion object {
        private const val FIELDS = "number,url,title,state,isDraft"

        /** `gh pr list --json` [FIELDS] output, newest first: the first one still worth showing. */
        internal fun parse(json: String): PullRequestLookup {
            val list = try {
                Json.parseToJsonElement(json) as? JsonArray
            } catch (_: Exception) {
                null
            } ?: return PullRequestLookup.Failed
            return list.firstNotNullOfOrNull { (it as? JsonObject)?.let(::pullRequest) }
                ?.let(PullRequestLookup::Found) ?: PullRequestLookup.None
        }

        /** One entry as a [PullRequest]; null for a closed one or another shape. */
        private fun pullRequest(obj: JsonObject): PullRequest? {
            val status = when (obj.str("state")) {
                "OPEN" -> if (obj.bool("isDraft") == true) PullRequest.Status.DRAFT else PullRequest.Status.OPEN
                "MERGED" -> PullRequest.Status.MERGED
                else -> return null
            }
            val url = obj.str("url")?.takeIf { it.startsWith("https://") } ?: return null
            val number = obj.long("number")?.toInt() ?: return null
            return PullRequest(number, url, obj.str("title").orEmpty(), status)
        }
    }
}

private const val GH_TIMEOUT_SECONDS = 10L

/** Where Homebrew puts `gh`; a GUI-launched IDE's PATH may lack them. */
private val GH_FALLBACK_DIRS = listOf("/opt/homebrew/bin", "/usr/local/bin")

/** Runs `gh` from the shell environment's PATH (or [GH_FALLBACK_DIRS]) in [dir], without prompts. */
private fun runGh(dir: File, args: List<String>): String? {
    val env = EnvironmentUtil.getEnvironmentMap()
    val path = (listOfNotNull(env["PATH"]) + GH_FALLBACK_DIRS).joinToString(File.pathSeparator)
    val gh = PathEnvironmentVariableUtil.findInPath("gh", path, FileFilter { it.canExecute() }) ?: return null
    return runProcessBytes(listOf(gh.path) + args, dir, GH_TIMEOUT_SECONDS,
        env + mapOf("GH_PROMPT_DISABLED" to "1", "GIT_TERMINAL_PROMPT" to "0"))?.toString(Charsets.UTF_8)
}
