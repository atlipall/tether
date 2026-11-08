package tether.session

import tether.acp.AcpSession
import tether.acp.JsonAcpClient
import tether.cli.ClaudeBinary
import tether.cli.ClaudeCommand
import tether.cli.ClaudeProcess
import tether.cli.CliConnection
import tether.cli.Launch
import tether.cli.PermissionMode
import tether.cli.SessionLaunch
import tether.git.DiffBases
import tether.git.GitWorktrees
import tether.context.EditorContextService
import tether.ide.EditTracker
import tether.ide.GitTracker
import tether.ide.TurnCheckpoints
import tether.ide.tools.ChatIdeHost
import tether.settings.TetherSettings
import com.intellij.openapi.Disposable
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.EnvironmentUtil
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.execution.ParametersListUtil
import java.io.File
import java.io.FileFilter
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class ClaudeNotFoundException : Exception(
    "Claude Code CLI not found. Install it, run `claude` once in a terminal to log in, " +
        "or set its path in Settings > Tools > Tether."
)

/** The project's open chats. Each started chat owns its selected agent's process. */
@Service(Service.Level.PROJECT)
class Chats(private val project: Project) : Disposable {
    var connector: (SessionLaunch) -> CliConnection = ::defaultConnect
    /** Starts the configured ACP command in the chat's folder, preserving the agent's own environment. */
    var acpConnector: (List<String>, String) -> CliConnection = ::defaultAcpConnect
    var store: SessionStore = SessionStore.default()
    var worktrees: GitWorktrees = GitWorktrees(projectDir)

    private val chats = CopyOnWriteArrayList<Chat>()
    private val ids = AtomicInteger()

    val projectDir: String get() = project.basePath ?: System.getProperty("user.home")

    /**
     * Looked up now, like [context] and [checkpoints]: services must not be looked up while the project is being
     * disposed, which is when [close] runs. Getting it also starts recording saves before the first message.
     */
    private val tracker = EditTracker.getInstance(project)
    /** Cached like [tracker]; getting it also starts tracking the focused editor. */
    private val context = EditorContextService.getInstance(project)
    /** Cached like [tracker]. */
    private val checkpoints = TurnCheckpoints.getInstance(project)
    /** Cached like [tracker]. */
    private val git = GitTracker.getInstance(project)

    fun all(): List<Chat> = chats.toList()

    fun mainSpec(): ChatSpec = ChatSpec.inProject(projectDir)

    /**
     * The sessions that were open when the project last closed (see [OpenChats]), each read and ready to [open]; skips
     * any whose folder or Claude transcript is gone, that was archived since, or that can't be read (logged). ACP
     * sessions load their own history when opened, and are skipped when their agent is no longer configured. Off EDT.
     */
    fun restorable(): List<Pair<ChatSpec, Transcript>> = OpenChats.getInstance(project).saved().mapNotNull { saved ->
        val (id, cwd, setup) = saved
        if (!File(cwd).isDirectory) return@mapNotNull null
        if (!Agent.isNative(saved.agentId)) {
            if (configuredAgents().none { it.id == saved.agentId }) {
                LOG.warn("Couldn't reopen session $id: agent ${saved.agentId} is not configured")
                return@mapNotNull null
            }
            val spec = ChatSpec(ChatNames.folderLabel(cwd, projectDir), cwd, resumeId = id,
                title = saved.title, agentId = saved.agentId, agentMode = saved.agentMode)
            return@mapNotNull spec to Transcript(emptyList(), null, null, null)
        }
        try {
            val transcript = store.read(cwd, id)
            transcript.summary?.takeUnless { it.archived }?.let { ChatSpec.resume(projectDir, it).copy(setup = setup) to transcript }
        } catch (e: Exception) {
            if (e is ControlFlowException) throw e
            LOG.warn("Couldn't read session $id to reopen it", e)
            null
        }
    }

    /**
     * [spec]'s native transcript as [open] needs it; null for a new chat or an ACP session, which replays its own history.
     * Reads the whole native transcript: off the EDT if it can.
     */
    fun read(spec: ChatSpec): Transcript? =
        if (!Agent.isNative(spec.agentId)) null
        else spec.resumeId?.let { store.read(spec.cwd, it) }

    private fun configuredAgents(): List<Agent> {
        val command = ParametersListUtil.parse(TetherSettings.getInstance().state.acpCommand.orEmpty())
        return listOfNotNull(Agent.CLAUDE, command.takeIf { it.isNotEmpty() }?.let(Agent::acp))
    }

    /**
     * Creates a draft; only a resumed session starts immediately. Its history is [resumed] when already [read], or
     * replayed by the ACP agent. The IDE host asks for the folder only once the chat runs. EDT.
     */
    fun open(spec: ChatSpec, resumed: Transcript? = read(spec)): Chat {
        val history = resumed?.items.orEmpty()
        val started = spec.copy(setup = spec.setup.copy(mode = spec.setup.mode ?: defaultMode()))
        val agents = if (spec.worktree == null) configuredAgents() else listOf(Agent.CLAUDE)
        val settings = TetherSettings.getInstance().state
        val requested = spec.agentId ?: if (spec.resumeId != null) Agent.CLAUDE.id else settings.lastAgent
        val agent = agents.firstOrNull { it.id == requested }
            ?: if (spec.agentId != null) error("Agent ${spec.agentId} is not configured.") else Agent.CLAUDE
        lateinit var chat: Chat
        val ide = ChatIdeHost(project, projectDir) { chat.cwd }
        chat = Chat(
            id = ids.incrementAndGet(),
            spec = started,
            history = history,
            resumeAnchor = resumed?.lastAssistantUuid,
            connector = connector,
            editNote = { c -> listOfNotNull(checkpoints.consumeNote(c.id), tracker.consumeNote(c.id, c.cwd)).joinToString("\n\n").ifEmpty { null } },
            editorContext = { c -> if (TetherSettings.getInstance().state.attachEditorContext) context.contextFor(c.cwd) else null },
            resolveContext = { c, done ->
                if (TetherSettings.getInstance().state.attachEditorContext) context.resolve(c.cwd, done) else done(null)
            },
            gitNotes = git,
            log = { LOG.info(it) },
            startedAt = if (spec.resumeId == null) System.currentTimeMillis() else resumed?.startedAt,
            ide = ide,
            checkpoints = checkpoints,
            background = { AppExecutorUtil.getAppExecutorService().execute(it) },
            defaultName = nameAmongOthers(ChatNames.branchLabel(spec)),
            saveTitle = ::saveTitle,
            nameForBranch = { name -> nameAmongOthers(name, chat) },
            agents = agents,
            agent = agent,
            acpSession = { command, listener ->
                AcpSession({ JsonAcpClient(acpConnector(command, spec.cwd)) }, listener, spec.cwd,
                    Agent.acp(command).name, { LOG.info(it) }, resumeId = spec.resumeId, mode = spec.agentMode)
            },
            onAgentSelected = { settings.lastAgent = it.id },
        )
        tracker.register(chat.id)
        git.settle(chat.id, chat.cwd)
        chats += chat
        recordStartCommit(chat, spec)
        if (spec.resumeId != null) chat.start()
        return chat
    }

    /** [label], numbered past the titles of the open chats other than [self] (see [ChatNames.defaultName]). Any thread. */
    private fun nameAmongOthers(label: String, self: Chat? = null): String =
        ChatNames.defaultName(label, chats.filter { it !== self }.map { it.title })

    /**
     * Records HEAD as the diff base of a new chat in an existing checkout, off the EDT. A resumed session started
     * earlier: its base is found from [Chat.startedAt]. A new worktree is a branch, diffed against main.
     */
    private fun recordStartCommit(chat: Chat, spec: ChatSpec) {
        if (spec.resumeId != null || spec.worktree != null) return
        AppExecutorUtil.getAppExecutorService().execute { chat.startCommit = DiffBases().head(spec.cwd) }
    }

    /** Stops the chat's process. Its transcript stays on disk. */
    fun close(chat: Chat) {
        if (chats.remove(chat)) {
            chat.dispose()
            tracker.forget(chat.id)
            checkpoints.forget(chat.id)
            git.forget(chat.id)
        }
    }

    fun closeAll() = all().forEach(::close)

    /** Takes [session] out of the archive, keeping its name. Off the EDT (writes its transcript). */
    fun unarchive(session: SessionSummary) {
        saveTitle(session.folder, session.id, session.customTitle.orEmpty(), archived = false)
    }

    /**
     * The past sessions of the project and its worktrees that are not archived and not open in a tab: what
     * [archiveAll] would archive. Off the EDT (runs git, reads every transcript).
     */
    fun archivable(): List<SessionSummary> {
        val open = openSessionIds(Agent.CLAUDE.id)
        return store.listAll(worktrees.knownFolders(worktrees.list()), limit = Int.MAX_VALUE).filterNot { it.id in open }
    }

    /**
     * Archives [sessions], keeping their names, except any opened in a tab since they were listed: its chat would
     * still be live, and archiving it would only hide it from the next restart. Returns how many were archived (a
     * transcript that can't be written is logged and not counted). Off the EDT (writes the transcripts).
     */
    fun archiveAll(sessions: List<SessionSummary>): Int {
        val open = openSessionIds(Agent.CLAUDE.id)
        return sessions.filterNot { it.id in open }
            .count { saveTitle(it.folder, it.id, it.customTitle.orEmpty(), archived = true) }
    }

    /** The ids of the sessions open in a tab (a new chat has none until the CLI starts). */
    fun openSessionIds(agentId: String? = null): Set<String> =
        chats.filter { agentId == null || it.agent.id == agentId }.mapNotNull { it.session.sessionId }.toSet()

    /** Appends [title] to [sessionId]'s transcript; false (logged) when it can't be written. */
    private fun saveTitle(cwd: String, sessionId: String, title: String, archived: Boolean): Boolean = try {
        store.saveTitle(cwd, sessionId, title, archived)
        true
    } catch (e: IOException) {
        LOG.warn("Couldn't save the name of session $sessionId in its transcript", e)
        false
    }

    private fun defaultMode(): PermissionMode? = PermissionMode.fromCli(TetherSettings.getInstance().state.defaultMode)

    /**
     * The real claude process for [launch]. Returns at once; the shell environment (which can take seconds to load
     * right after the IDE starts), the binary lookup and the spawn happen on the process's own launch thread.
     */
    private fun defaultConnect(launch: SessionLaunch): CliConnection {
        val settings = TetherSettings.getInstance().state
        val claudePath = settings.claudePath.orEmpty()
        val extraArgs = ParametersListUtil.parse(settings.extraArgs.orEmpty())
        return ClaudeProcess(File(launch.cwd)) {
            val env = EnvironmentUtil.getEnvironmentMap()
            val binary = ClaudeBinary.find(claudePath, env) ?: throw ClaudeNotFoundException()
            Launch(ClaudeCommand.build(binary, launch, extraArgs), env)
        }
    }

    /** Resolves the shell environment and executable on the process's launch thread, not the EDT. */
    private fun defaultAcpConnect(command: List<String>, cwd: String): CliConnection =
        ClaudeProcess(File(cwd), stripped = emptyList()) {
            val env = EnvironmentUtil.getEnvironmentMap()
            val name = command.first()
            val executable = if ('/' in name) name else PathEnvironmentVariableUtil.findInPath(name, env["PATH"], FileFilter { it.canExecute() })?.path
                ?: throw IllegalStateException("`$name` was not found on PATH. Set the ACP agent command, with the full path if needed, in Settings > Tools > Tether.")
            Launch(listOf(executable) + command.drop(1), env)
        }

    override fun dispose() = closeAll()

    companion object {
        private val LOG = logger<Chats>()
        fun getInstance(project: Project): Chats = project.service()
    }
}
