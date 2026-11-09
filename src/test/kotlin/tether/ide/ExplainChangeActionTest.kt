package tether.ide

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ExplainChangeActionTest : BasePlatformTestCase() {
    fun testOfferedInTheEditorAndDiffPopupMenus() {
        val actions = ActionManager.getInstance()
        assertInstanceOf(actions.getAction("tether.ExplainChange"), ExplainChangeAction::class.java)
        for (group in listOf("EditorPopupMenu", "Diff.EditorPopupMenu")) {
            val ids = (actions.getAction(group) as DefaultActionGroup).getChildActionsOrStubs().map(actions::getId)
            assertTrue("missing from $group", "tether.ExplainChange" in ids)
        }
    }
}
