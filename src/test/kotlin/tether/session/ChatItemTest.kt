package tether.session

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatItemTest {
    @Test fun user() = assertEquals(
        """{"id":"u1","kind":"user","text":"hi"}""",
        ChatItem.UserText("u1", "hi").toJson().toString())

    @Test fun userWithThumbnails() = assertEquals(
        """{"id":"u1","kind":"user","text":"","images":["data:image/png;base64,AAAA",""]}""",
        ChatItem.UserText("u1", "", listOf("data:image/png;base64,AAAA", "")).toJson().toString())

    @Test fun assistant() = assertEquals(
        """{"id":"a1","kind":"assistant","text":"x","streaming":true}""",
        ChatItem.AssistantText("a1", "x", true).toJson().toString())

    @Test fun tool() {
        val input = buildJsonObject { put("file_path", JsonPrimitive("/p/a.ts")) }
        assertEquals(
            """{"id":"t1","kind":"tool","name":"Edit","input":{"file_path":"/p/a.ts"},"filePath":"/p/a.ts","status":"awaiting_permission","permissionRequestId":"r1","resultPreview":null,"hasDiff":false}""",
            ChatItem.ToolCall("t1", "Edit", input, "/p/a.ts", ToolStatus.AWAITING_PERMISSION, "r1", null, false).toJson().toString())
    }

    @Test fun toolWithImages() {
        val input = buildJsonObject { put("file_path", JsonPrimitive("/p/a.png")) }
        assertEquals(
            """{"id":"t1","kind":"tool","name":"Read","input":{"file_path":"/p/a.png"},"filePath":"/p/a.png","status":"done","permissionRequestId":null,"resultPreview":"","hasDiff":false,"images":["data:image/png;base64,AAAA",""]}""",
            ChatItem.ToolCall("t1", "Read", input, "/p/a.png", ToolStatus.DONE, null, "", false, listOf("data:image/png;base64,AAAA", "")).toJson().toString())
    }

    @Test fun permissionCardWithTheAgentsChoices() = assertEquals(
        """{"id":"perm-check-1","kind":"tool","name":"Run ls","input":{},"filePath":null,"status":"awaiting_permission","permissionRequestId":"perm-2","resultPreview":null,"hasDiff":false,""" +
            """"choices":[{"id":"allow_once","label":"Allow once"},{"id":"deny","label":"Deny"}]}""",
        ChatItem.ToolCall("perm-check-1", "Run ls", buildJsonObject {}, null, ToolStatus.AWAITING_PERMISSION, "perm-2", null, false,
            choices = listOf(Choice("allow_once", "Allow once"), Choice("deny", "Deny"))).toJson().toString())

    @Test fun subagentTextHasItsParent() = assertEquals(
        """{"id":"a1","kind":"assistant","text":"x","streaming":false,"parentId":"t1"}""",
        ChatItem.AssistantText("a1", "x", false, "t1").toJson().toString())

    @Test fun commandOutputIsFlagged() = assertEquals(
        """{"id":"a1","kind":"assistant","text":"x","streaming":false,"commandOutput":true}""",
        ChatItem.AssistantText("a1", "x", false, commandOutput = true).toJson().toString())

    @Test fun agentCall() = assertEquals(
        """{"id":"t1","kind":"tool","name":"Agent","input":{},"filePath":null,"status":"done","permissionRequestId":null,"resultPreview":null,"hasDiff":false,""" +
            """"agent":{"subagentType":"general-purpose","background":true,"prompt":"p","state":"running","toolUses":2,"durationMs":900,"lastTool":"Bash","report":null,"taskId":"a1"}}""",
        ChatItem.ToolCall("t1", "Agent", buildJsonObject {}, null, ToolStatus.DONE, null, null, false,
            agent = AgentInfo("general-purpose", true, "p", AgentState.RUNNING, 2, 900, "Bash", taskId = "a1")).toJson().toString())

    @Test fun subagentToolHasItsParent() = assertEquals(
        """{"id":"t2","kind":"tool","name":"Bash","input":{},"filePath":null,"status":"running","permissionRequestId":null,"resultPreview":null,"hasDiff":false,"parentId":"t1"}""",
        ChatItem.ToolCall("t2", "Bash", buildJsonObject {}, null, ToolStatus.RUNNING, null, null, false, parentId = "t1").toJson().toString())

    @Test fun result() = assertEquals(
        """{"id":"r1","kind":"result","isError":false,"text":null,"durationMs":5566,"costUsd":0.19}""",
        ChatItem.TurnResult("r1", false, null, 5566, 0.19).toJson().toString())

    @Test fun notice() = assertEquals(
        """{"id":"n1","kind":"notice","text":"Interrupted","isError":false}""",
        ChatItem.Notice("n1", "Interrupted", false).toJson().toString())

    @Test fun resultWithEditedFiles() = assertEquals(
        """{"id":"r1","kind":"result","isError":false,"text":null,"durationMs":5,"costUsd":null,"files":[{"path":"/p/a.kt","added":3,"removed":1,"isNew":false}]}""",
        ChatItem.TurnResult("r1", false, null, 5, null, listOf(TurnFile("/p/a.kt", 3, 1, false))).toJson().toString())

    @Test fun resultWithoutEditedFilesHasNoList() = assertEquals(
        """{"id":"r1","kind":"result","isError":false,"text":null,"durationMs":5,"costUsd":null}""",
        ChatItem.TurnResult("r1", false, null, 5, null).toJson().toString())
}
