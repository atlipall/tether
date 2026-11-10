package tether.testutil

import kotlinx.serialization.json.JsonPrimitive

/** Builders for CLI stdout lines, shaped like real claude 2.1.317 output (trimmed). */
object Fx {
    fun q(s: String): String = JsonPrimitive(s).toString()

    fun init(sessionId: String = "sess-1", mode: String = "default", cwd: String = "/proj") =
        """{"type":"system","subtype":"init","cwd":${q(cwd)},"session_id":"$sessionId","tools":["Read","Write"],"permissionMode":"$mode","claude_code_version":"2.1.317","uuid":"u1"}"""

    fun initResponse(requestId: String, mode: String = "default") =
        """{"type":"control_response","response":{"subtype":"success","request_id":"$requestId","response":{"commands":[],"current_permission_mode":"$mode","session_state":"idle","claude_code_version":"2.1.317"}}}"""

    /** The models part of claude 2.1.317's initialize reply, trimmed to a few entries. */
    const val MODELS =
        """[{"value":"default","resolvedModel":"claude-opus-5-5","displayName":"Default (recommended)","description":"Opus 5.5 · Best for everyday, complex tasks","supportsEffort":true,"supportedEffortLevels":["low","medium","high","xhigh","max"]},""" +
        """{"value":"opus","resolvedModel":"claude-opus-5-5","displayName":"Opus 5.5","description":"For complex work and everyday tasks","supportsEffort":true,"supportedEffortLevels":["low","medium","high","xhigh","max"]},""" +
        """{"value":"sonnet","resolvedModel":"claude-sonnet-5-5","displayName":"Sonnet 5.5","description":"Most efficient for simpler tasks","supportsEffort":true,"supportedEffortLevels":["low","medium","high","xhigh","max"]},""" +
        """{"value":"claude-sonnet-4-6","resolvedModel":"claude-sonnet-4-6","displayName":"Sonnet 4.6","description":"Efficient for routine tasks","supportsEffort":true,"supportedEffortLevels":["low","medium","high","max"]},""" +
        """{"value":"claude-haiku-4-5-20251001","resolvedModel":"claude-haiku-4-5-20251001","displayName":"Haiku 4.5","description":"Fastest for quick answers","supportsThinkingOff":true}]"""

    fun initResponseWithModels(requestId: String, models: String = MODELS) =
        """{"type":"control_response","response":{"subtype":"success","request_id":"$requestId","response":{"commands":[],"models":$models,"current_permission_mode":"default","session_state":"idle","claude_code_version":"2.1.317"}}}"""

    fun initResponseWithCommands(requestId: String, commands: String) =
        """{"type":"control_response","response":{"subtype":"success","request_id":"$requestId","response":{"commands":$commands,"current_permission_mode":"default","session_state":"idle","claude_code_version":"2.1.317"}}}"""

    /** A get_settings reply, trimmed from claude 2.1.317 to what Tether reads (and a little around it). */
    fun settingsResponse(requestId: String, model: String = "claude-opus-5-5", effort: String? = "high") =
        """{"type":"control_response","response":{"subtype":"success","request_id":"$requestId","response":{"effective":{"effortLevel":"xhigh"},"sources":[],"applied":{"model":${q(model)},"effort":${effort?.let(::q) ?: "null"},"advisor":null,"ultracode":false}}}}"""

    /** A get_context_usage reply, trimmed from claude 2.1.317 (gridRows and other display fields left out). */
    fun contextResponse(requestId: String, used: Long = 14851, max: Long = 1000000, percent: Int = 1) =
        """{"type":"control_response","response":{"subtype":"success","request_id":"$requestId","response":{"categories":[{"id":"system_prompt","name":"System prompt","tokens":2495,"color":"promptBorder","kind":"used"},{"id":"system_tools","name":"System tools","tokens":646,"color":"inactive","kind":"used"},{"id":"system_tools_deferred","name":"System tools (deferred)","tokens":16286,"color":"inactive","isDeferred":true,"kind":"deferred"},{"id":"skills","name":"Skills","tokens":9924,"color":"warning","kind":"used"},{"id":"messages","name":"Messages","tokens":1290,"color":"purple_FOR_SUBAGENTS_ONLY","kind":"used"},{"id":"empty","name":"Empty","tokens":0,"kind":"used"},{"id":"autocompact_buffer","name":"Autocompact buffer","tokens":33000,"color":"inactive","kind":"buffer"},{"id":"free_space","name":"Free space","tokens":952149,"color":"promptBorder","kind":"free"}],"totalTokens":$used,"maxTokens":$max,"rawMaxTokens":$max,"autocompactSource":"model-default","percentage":$percent}}}"""

    fun modeResponse(requestId: String, mode: String) =
        """{"type":"control_response","response":{"subtype":"success","request_id":"$requestId","response":{"mode":"$mode"}}}"""

    fun modelResponse(requestId: String, model: String) =
        """{"type":"control_response","response":{"subtype":"success","request_id":"$requestId","response":{"model":"$model"}}}"""

    fun errorResponse(requestId: String, error: String) =
        """{"type":"control_response","response":{"subtype":"error","request_id":"$requestId","error":${q(error)}}}"""

    fun hookStarted() =
        """{"type":"system","subtype":"hook_started","hook_id":"h1","hook_name":"SessionStart:startup","hook_event":"SessionStart","uuid":"u0","session_id":"sess-1"}"""

    fun rateLimit() =
        """{"type":"rate_limit_event","rate_limit_info":{"status":"allowed","resetsAt":1794110400,"rateLimitType":"seven_day"},"uuid":"u9","session_id":"sess-1"}"""

    fun messageStart(msg: String) =
        """{"type":"stream_event","event":{"type":"message_start","message":{"model":"claude-opus-5-5","id":"$msg","type":"message","role":"assistant","content":[]}},"session_id":"sess-1","parent_tool_use_id":null,"uuid":"u2","api_message_id":"$msg"}"""

    /** A message_start from inside a subagent (parent_tool_use_id set). */
    fun subagentMessageStart(msg: String, parent: String) =
        """{"type":"stream_event","event":{"type":"message_start","message":{"model":"claude-opus-5-5","id":"$msg","type":"message","role":"assistant","content":[]}},"session_id":"sess-1","parent_tool_use_id":"$parent","uuid":"u2","api_message_id":"$msg"}"""

    /** The CLI's list of background tasks (e.g. agents started with run_in_background) after it changed. */
    fun backgroundTasks(vararg descriptions: String) =
        """{"type":"system","subtype":"background_tasks_changed","tasks":[${descriptions.mapIndexed { i, d -> """{"task_id":"a$i","run_id":"r$i","task_type":"local_agent","subagent_type":"general-purpose","description":${q(d)}}""" }.joinToString(",")}],"uuid":"u13","session_id":"sess-1"}"""

    /** A background_tasks_changed listing [tasks] as (task id, task type, description). */
    fun backgroundTaskList(vararg tasks: Triple<String, String, String>) =
        """{"type":"system","subtype":"background_tasks_changed","tasks":[${tasks.joinToString(",") { (id, type, d) -> """{"task_id":"$id","task_type":"$type","description":${q(d)}}""" }}],"uuid":"u13","session_id":"sess-1"}"""

    /** A shell command Bash call [toolUseId] started in the background, as task [taskId]. */
    fun shellTaskStarted(toolUseId: String, taskId: String) =
        """{"type":"system","subtype":"task_started","task_id":"$taskId","tool_use_id":"$toolUseId","description":"npm run dev","is_backgrounded":true,"task_type":"local_bash","uuid":"u28","session_id":"sess-1"}"""

    fun subagentToolUse(parent: String, toolUseId: String, name: String, inputJson: String) =
        """{"type":"assistant","message":{"model":"claude-haiku-5-5","id":"sm-$toolUseId","type":"message","role":"assistant","content":[{"type":"tool_use","id":"$toolUseId","name":"$name","input":$inputJson}],"stop_reason":null},"parent_tool_use_id":"$parent","session_id":"sess-1","uuid":"u20"}"""

    fun subagentText(parent: String, msg: String, text: String) =
        """{"type":"assistant","message":{"model":"claude-haiku-5-5","id":"$msg","type":"message","role":"assistant","content":[{"type":"text","text":${q(text)}}],"stop_reason":null},"parent_tool_use_id":"$parent","session_id":"sess-1","uuid":"u21"}"""

    fun subagentPrompt(parent: String, text: String) =
        """{"type":"user","message":{"role":"user","content":[{"type":"text","text":${q(text)}}]},"parent_tool_use_id":"$parent","session_id":"sess-1","uuid":"u22"}"""

    fun subagentToolResult(parent: String, toolUseId: String, content: String, toolUseResultJson: String? = null): String {
        val tur = if (toolUseResultJson != null) ""","tool_use_result":$toolUseResultJson""" else ""
        return """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"$toolUseId","type":"tool_result","content":${q(content)}}]},"parent_tool_use_id":"$parent","session_id":"sess-1","uuid":"u23"$tur}"""
    }

    fun subagentTextDelta(parent: String, msg: String, text: String) =
        """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":${q(text)}}},"session_id":"sess-1","parent_tool_use_id":"$parent","uuid":"u24","api_message_id":"$msg"}"""

    fun taskStarted(toolUseId: String, background: Boolean = false, prompt: String = "Do it") =
        """{"type":"system","subtype":"task_started","task_id":"a1","run_id":"r1","tool_use_id":"$toolUseId","description":"Do it","subagent_type":"general-purpose","is_backgrounded":$background,"spawn_depth":1,"task_type":"local_agent","prompt":${q(prompt)},"uuid":"u25","session_id":"sess-1"}"""

    fun taskProgress(toolUseId: String, toolUses: Int, durationMs: Long, lastTool: String) =
        """{"type":"system","subtype":"task_progress","task_id":"a1","run_id":"r1","tool_use_id":"$toolUseId","description":"Do it","subagent_type":"general-purpose","usage":{"total_tokens":100,"tool_uses":$toolUses,"duration_ms":$durationMs},"last_tool_name":"$lastTool","uuid":"u26","session_id":"sess-1"}"""

    fun taskNotification(toolUseId: String, status: String = "completed", report: String? = "DONE") =
        """{"type":"system","subtype":"task_notification","task_id":"a1","run_id":"r1","tool_use_id":"$toolUseId","status":"$status","output_file":"/tmp/x.output","summary":"s","usage":{"total_tokens":120,"tool_uses":3,"duration_ms":4063},"handback":"send"${if (report != null) ""","handback_report":{"text":${q(report)}}""" else ""},"uuid":"u27","session_id":"sess-1"}"""

    fun textDelta(msg: String, index: Int, text: String) =
        """{"type":"stream_event","event":{"type":"content_block_delta","index":$index,"delta":{"type":"text_delta","text":${q(text)}}},"session_id":"sess-1","parent_tool_use_id":null,"uuid":"u3","api_message_id":"$msg"}"""

    fun thinkingStart(msg: String, index: Int) =
        """{"type":"stream_event","event":{"type":"content_block_start","index":$index,"content_block":{"type":"thinking","thinking":"","signature":""}},"session_id":"sess-1","parent_tool_use_id":null,"uuid":"u3","api_message_id":"$msg"}"""

    fun thinkingDelta(msg: String, index: Int, text: String) =
        """{"type":"stream_event","event":{"type":"content_block_delta","index":$index,"delta":{"type":"thinking_delta","thinking":${q(text)}}},"session_id":"sess-1","parent_tool_use_id":null,"uuid":"u3","api_message_id":"$msg"}"""

    fun blockStop(msg: String, index: Int) =
        """{"type":"stream_event","event":{"type":"content_block_stop","index":$index},"session_id":"sess-1","parent_tool_use_id":null,"uuid":"u4","api_message_id":"$msg"}"""

    fun assistantText(msg: String, text: String, uuid: String = "u5") =
        """{"type":"assistant","message":{"model":"claude-opus-5-5","id":"$msg","type":"message","role":"assistant","content":[{"type":"text","text":${q(text)}}],"stop_reason":null},"parent_tool_use_id":null,"session_id":"sess-1","uuid":"$uuid"}"""

    /** The synthetic assistant line the CLI writes for a local command such as `/usage`. */
    fun commandOutput(msg: String, text: String) =
        """{"type":"assistant","message":{"model":"<synthetic>","id":"$msg","type":"message","role":"assistant","content":[{"type":"text","text":${q(text)}}],"stop_reason":"end_turn"},"parent_tool_use_id":null,"local_command_source":${q("<local-command-stdout>$text</local-command-stdout>")},"session_id":"sess-1","uuid":"u7"}"""

    fun assistantThinking(msg: String, uuid: String = "u5") =
        """{"type":"assistant","message":{"model":"claude-opus-5-5","id":"$msg","type":"message","role":"assistant","content":[{"type":"thinking","thinking":"","signature":"sig"}],"stop_reason":null},"parent_tool_use_id":null,"session_id":"sess-1","uuid":"$uuid"}"""

    fun toolUse(msg: String, toolUseId: String, name: String, inputJson: String, uuid: String = "u6") =
        """{"type":"assistant","message":{"model":"claude-opus-5-5","id":"$msg","type":"message","role":"assistant","content":[{"type":"tool_use","id":"$toolUseId","name":"$name","input":$inputJson,"caller":{"type":"direct"}}],"stop_reason":null},"parent_tool_use_id":null,"session_id":"sess-1","uuid":"$uuid"}"""

    /** A CLI-forwarded MCP message for in-process server [server] ([messageJson] is the JSON-RPC message). */
    fun mcpMessage(requestId: String, server: String, messageJson: String) =
        """{"type":"control_request","request_id":"$requestId","request":{"subtype":"mcp_message","server_name":"$server","message":$messageJson}}"""

    /** A PostToolUse hook callback for an edit of [filePath] with [tool]. */
    fun hookCallback(requestId: String, callbackId: String, tool: String, filePath: String) =
        """{"type":"control_request","request_id":"$requestId","request":{"subtype":"hook_callback","callback_id":"$callbackId",""" +
            """"input":{"hook_event_name":"PostToolUse","tool_name":"$tool","tool_input":{"file_path":"$filePath"}},"tool_use_id":"t9"}}"""

    fun canUseTool(requestId: String, toolUseId: String, name: String, inputJson: String) =
        """{"type":"control_request","request_id":"$requestId","request":{"subtype":"can_use_tool","tool_name":"$name","display_name":"$name","input":$inputJson,"description":"x","permission_suggestions":[{"type":"setMode","mode":"acceptEdits","destination":"session"}],"tool_use_id":"$toolUseId"}}"""

    /** The input of the AskUserQuestion call claude 2.1.317 made in the v6 probe (one single-select question). */
    const val COLOUR_QUESTION =
        """{"questions":[{"question":"Which colour do you prefer?","header":"Colour","options":[{"label":"Red","description":"The colour red"},{"label":"Blue","description":"The colour blue"}],"multiSelect":false}]}"""

    /** A single-select and a multi-select question, the second with a preview on one option and a comma in a label. */
    const val TWO_QUESTIONS =
        """{"questions":[{"question":"Which colour?","header":"Colour","options":[{"label":"Red","description":"r"},{"label":"Blue","description":"b"}],"multiSelect":false},""" +
            """{"question":"Which extras?","header":"Extras","options":[{"label":"A, B","description":"ab","preview":"+--+\n|AB|\n+--+"},{"label":"C","description":"c"}],"multiSelect":true}]}"""

    fun askQuestion(requestId: String, toolUseId: String, inputJson: String = COLOUR_QUESTION) =
        """{"type":"control_request","request_id":"$requestId","request":{"subtype":"can_use_tool","tool_name":"AskUserQuestion","display_name":"AskUserQuestion","input":$inputJson,"tool_use_id":"$toolUseId","requires_user_interaction":true}}"""

    fun toolResult(toolUseId: String, content: String, isError: Boolean = false, toolUseResultJson: String? = null): String {
        val err = if (isError) ""","is_error":true""" else ""
        val tur = if (toolUseResultJson != null) ""","tool_use_result":$toolUseResultJson""" else ""
        return """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"$toolUseId","type":"tool_result","content":${q(content)}$err}]},"parent_tool_use_id":null,"session_id":"sess-1","uuid":"u7","timestamp":"2026-11-03T09:54:19.577Z"$tur}"""
    }

    /** Read on a PNG, as claude 2.1.317 reports it: an image block inside tool_result.content, the bytes again in tool_use_result.file. */
    fun readImageResult(toolUseId: String, base64: String, mediaType: String = "image/png") =
        """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"$toolUseId","type":"tool_result","content":[{"type":"image","source":{"type":"base64","data":"$base64","media_type":"$mediaType"}}]}]},"parent_tool_use_id":null,"session_id":"sess-1","uuid":"u12","timestamp":"2026-11-03T13:55:27.693Z","tool_use_result":{"type":"image","file":{"base64":"$base64","type":"$mediaType","originalSize":73,"dimensions":{"originalWidth":4,"originalHeight":4,"displayWidth":4,"displayHeight":4}}},"tool_result_meta":[{"id":"$toolUseId","permission_decision":{"decision":"accept","source":"config","reason_type":"mode"}}]}"""

    fun editResult(path: String, original: String) =
        """{"filePath":${q(path)},"oldString":"two","newString":"2","originalFile":${q(original)},"structuredPatch":[],"userModified":false,"replaceAll":false}"""

    fun createResult(path: String) =
        """{"type":"create","filePath":${q(path)},"content":"x","structuredPatch":[],"originalFile":null,"userModified":false}"""

    fun interruptedUser() =
        """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user]"}]},"parent_tool_use_id":null,"session_id":"sess-1","uuid":"u8"}"""

    fun result(text: String = "DONE") =
        """{"type":"result","subtype":"success","is_error":false,"duration_ms":5566,"duration_api_ms":4514,"num_turns":2,"result":${q(text)},"stop_reason":"end_turn","session_id":"sess-1","total_cost_usd":0.1944822,"uuid":"u10"}"""

    fun promptSuggestion(text: String) =
        """{"type":"prompt_suggestion","suggestion":${q(text)},"uuid":"u12","session_id":"sess-1"}"""

    fun errorResult() =
        """{"type":"result","subtype":"error_during_execution","is_error":true,"duration_ms":4010,"num_turns":2,"stop_reason":null,"session_id":"sess-1","total_cost_usd":0.19,"errors":["[ede_diagnostic] result_type=user"],"uuid":"u11"}"""
}
