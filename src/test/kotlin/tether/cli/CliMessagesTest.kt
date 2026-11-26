package tether.cli

import tether.image.Image
import tether.image.ImageType
import tether.testutil.TestImages
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CliMessagesTest {
    @Test fun initialize() = assertEquals(
        """{"type":"control_request","request_id":"i1","request":{"subtype":"initialize","hooks":null}}""",
        CliMessages.initialize("i1"))

    @Test fun initializeWithServersAndHooks() = assertEquals(
        """{"type":"control_request","request_id":"i1","request":{"subtype":"initialize","hooks":{"PostToolUse":[""" +
            """{"matcher":"Edit|Write","hookCallbackIds":["after-edit"],"timeout":60}]},"sdkMcpServers":["tether"]}}""",
        CliMessages.initialize("i1", listOf("tether"), mapOf("PostToolUse" to listOf(HookRegistration("Edit|Write", "after-edit", 60)))))

    @Test fun mcpResponse() = assertEquals(
        """{"type":"control_response","response":{"subtype":"success","request_id":"r1","response":{"mcp_response":{"jsonrpc":"2.0","result":{},"id":0}}}}""",
        CliMessages.mcpResponse("r1", McpServer.ACK))

    @Test fun hookResponseWithContext() = assertEquals(
        """{"type":"control_response","response":{"subtype":"success","request_id":"r1","response":{"hookSpecificOutput":""" +
            """{"hookEventName":"PostToolUse","additionalContext":"2 errors"}}}}""",
        CliMessages.hookResponse("r1", "PostToolUse", "2 errors"))

    @Test fun hookResponseWithoutContextIsEmpty() = assertEquals(
        """{"type":"control_response","response":{"subtype":"success","request_id":"r1","response":{}}}""",
        CliMessages.hookResponse("r1", "PostToolUse", null))

    @Test fun controlError() = assertEquals(
        """{"type":"control_response","response":{"subtype":"error","request_id":"r1","error":"nope"}}""",
        CliMessages.controlError("r1", "nope"))

    @Test fun userText() = assertEquals(
        """{"type":"user","message":{"role":"user","content":"hi \"there\""}}""",
        CliMessages.userText("hi \"there\""))

    @Test fun userTextWithUuid() = assertEquals(
        """{"type":"user","uuid":"id-1","message":{"role":"user","content":"hi"}}""",
        CliMessages.userText("hi", "id-1"))

    @Test fun remoteControl() {
        assertEquals(
            """{"type":"control_request","request_id":"r1","request":{"subtype":"remote_control","enabled":true}}""",
            CliMessages.remoteControl("r1", true))
        assertEquals(
            """{"type":"control_request","request_id":"r2","request":{"subtype":"remote_control","enabled":false}}""",
            CliMessages.remoteControl("r2", false))
    }

    @Test fun allowEchoesInput() {
        val input = buildJsonObject { put("file_path", JsonPrimitive("/a")) }
        assertEquals(
            """{"type":"control_response","response":{"subtype":"success","request_id":"r1","response":{"behavior":"allow","updatedInput":{"file_path":"/a"}}}}""",
            CliMessages.allow("r1", input))
    }

    @Test fun deny() = assertEquals(
        """{"type":"control_response","response":{"subtype":"success","request_id":"r1","response":{"behavior":"deny","message":"no"}}}""",
        CliMessages.deny("r1", "no"))

    @Test fun interrupt() = assertEquals(
        """{"type":"control_request","request_id":"x","request":{"subtype":"interrupt"}}""",
        CliMessages.interrupt("x"))

    @Test fun getContextUsage() = assertEquals(
        """{"type":"control_request","request_id":"c","request":{"subtype":"get_context_usage"}}""",
        CliMessages.getContextUsage("c"))

    @Test fun setPermissionMode() = assertEquals(
        """{"type":"control_request","request_id":"m","request":{"subtype":"set_permission_mode","mode":"plan"}}""",
        CliMessages.setPermissionMode("m", "plan"))

    @Test fun getSettings() = assertEquals(
        """{"type":"control_request","request_id":"s","request":{"subtype":"get_settings"}}""",
        CliMessages.getSettings("s"))

    @Test fun setModel() = assertEquals(
        """{"type":"control_request","request_id":"m","request":{"subtype":"set_model","model":"sonnet"}}""",
        CliMessages.setModel("m", "sonnet"))

    @Test fun setEffortGoesThroughFlagSettings() = assertEquals(
        """{"type":"control_request","request_id":"e","request":{"subtype":"apply_flag_settings","settings":{"effortLevel":"low"}}}""",
        CliMessages.setEffort("e", "low"))

    private val red = Image(ImageType.PNG, TestImages.bytes(TestImages.RED_PNG_BASE64), 4, 4)

    @Test fun userContentMatchesTheProbedShape() = assertEquals(
        """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"What color is this image? One word."},{"type":"image","source":{"type":"base64","media_type":"image/png","data":"${TestImages.RED_PNG_BASE64}"}}]}}""",
        CliMessages.userContent("What color is this image? One word.", listOf(red)))

    @Test fun userContentWithoutTextHasOnlyImageBlocks() = assertEquals(
        """{"type":"user","message":{"role":"user","content":[{"type":"image","source":{"type":"base64","media_type":"image/png","data":"${TestImages.RED_PNG_BASE64}"}},{"type":"image","source":{"type":"base64","media_type":"image/png","data":"${TestImages.RED_PNG_BASE64}"}}]}}""",
        CliMessages.userContent(" ", listOf(red, red)))

    @Test fun userContentWithoutImagesIsPlainText() = assertEquals(CliMessages.userText("hi"), CliMessages.userContent("hi", emptyList()))
}
