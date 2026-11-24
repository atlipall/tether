package tether.session

import junit.framework.TestCase

class PromptHistoryTest : TestCase() {
    fun testAppendKeepsOrderAndSkipsImmediateRepeat() = assertEquals(
        listOf("a", "b", "a"),
        listOf("a", "b", "b", "a").fold(emptyList<String>()) { acc, t -> PromptHistory.append(acc, t) })

    fun testAppendIgnoresBlank() = assertEquals(listOf("a"), PromptHistory.append(listOf("a"), "  "))

    fun testAppendCapsAtOneHundredDroppingOldest() {
        val next = PromptHistory.append((1..100).map { "m$it" }, "m101")
        assertEquals(100, next.size)
        assertEquals("m2", next.first())
        assertEquals("m101", next.last())
    }

    fun testAddStoresAndNotifiesSubscribers() {
        val history = PromptHistory()
        val seen = mutableListOf<List<String>>()
        val unsubscribe = history.subscribe { seen += it }
        history.add("one")
        history.add("one")
        history.add("two")
        unsubscribe()
        history.add("three")
        assertEquals(listOf("one", "two", "three"), history.entries())
        assertEquals(listOf(listOf("one"), listOf("one", "two")), seen)
    }

    fun testReplaceSwapsTheEntriesAndNotifiesOnlyOnChange() {
        val history = PromptHistory(listOf("old"))
        val seen = mutableListOf<List<String>>()
        history.subscribe { seen += it }
        history.replace(listOf("a", "a", "b"))
        history.replace(listOf("a", "b"))
        assertEquals(listOf("a", "b"), history.entries())
        assertEquals(listOf(listOf("a", "b")), seen)
    }

    fun testStartsWithTheInitialMessagesAppendedAsSent() =
        assertEquals(listOf("x", "y"), PromptHistory(listOf("x", "x", " ", "y")).entries())
}
