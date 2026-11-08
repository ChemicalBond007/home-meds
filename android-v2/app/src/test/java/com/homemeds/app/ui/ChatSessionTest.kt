package com.homemeds.app.ui

import com.homemeds.app.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSessionTest {
    private val dispatcher = StandardTestDispatcher()
    private val user = User("u", "本人", "user", "g", false)
    private val other = User("v", "家人", "user", "g", false)
    private val state = AppState(loading = false, user = user, groupId = "g", members = listOf(user, other))
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun ordinaryRefreshKeepsDraftsAndCompletedHistoryAndNextRequestUsesHistory() = runTest(dispatcher) {
        val bodies = mutableListOf<String>()
        val chat = ChatSession(backgroundScope) { body, group, phone, emit ->
            bodies += body.toString()
            assertEquals("g", group); assertTrue(phone)
            emit("delta", "原回复")
            emit("suggestions", JSONArray().put("推荐问题"))
            emit("medicine_draft", json("draft_id" to "medicine", "name" to "演示药品"))
        }
        chat.bind(state); chat.text = "原问题"; chat.send(); runCurrent()
        chat.text = "未发送补充"
        chat.bind(state.copy(today = "2026-11-09", busy = true, notice = "更新"))
        assertEquals("未发送补充", chat.text)
        assertEquals(listOf("原问题", "原回复"), chat.messages.map { it.content })
        assertEquals(listOf("推荐问题"), chat.suggestions)
        assertEquals(1, chat.medicineDrafts.size)
        chat.send(); runCurrent()
        assertEquals(listOf("原问题", "原回复", "未发送补充"),
            org.json.JSONObject(bodies[1]).getJSONArray("messages").objects().map { it.getString("content") })
    }

    @Test fun stoppingKeepsReceivedTextButClearingDoesNot() = runTest(dispatcher) {
        val chat = ChatSession(backgroundScope) { _, _, _, emit ->
            emit("delta", "已收到的部分"); awaitCancellation()
        }
        chat.bind(state); chat.text = "问题"; chat.send(); runCurrent()
        assertTrue(chat.running)
        chat.stop(); runCurrent()
        assertFalse(chat.running)
        assertEquals(listOf("问题", "已收到的部分"), chat.messages.map { it.content })
        assertTrue(chat.error!!.contains("已取消"))
        chat.clear(); runCurrent()
        assertTrue(chat.messages.isEmpty()); assertNull(chat.error)
    }

    @Test fun lateEventsAndFinallyFromClearedStreamCannotOverwriteNewReply() = runTest(dispatcher) {
        val releaseOld = CompletableDeferred<Unit>()
        var requests = 0
        var cancelled = 0
        val chat = ChatSession(backgroundScope) { _, _, _, emit ->
            if (++requests == 1) {
                try { emit("delta", "旧回复"); awaitCancellation() }
                finally {
                    cancelled++
                    withContext(NonCancellable) {
                        releaseOld.await()
                        emit("delta", "旧流延迟返回")
                        emit("suggestions", JSONArray().put("旧建议"))
                        emit("medicine_draft", json("draft_id" to "old", "name" to "旧草稿"))
                    }
                }
            } else { emit("delta", "新回复"); awaitCancellation() }
        }
        chat.bind(state); chat.text = "旧问题"; chat.send(); runCurrent()
        chat.clear(); chat.text = "新问题"; chat.send(); runCurrent()
        assertEquals(1, cancelled)
        releaseOld.complete(Unit); runCurrent()
        assertTrue(chat.running)
        assertEquals("新回复", chat.partial)
        assertEquals(listOf("新问题"), chat.messages.map { it.content })
        assertTrue(chat.suggestions.isEmpty()); assertTrue(chat.medicineDrafts.isEmpty()); assertNull(chat.error)
        chat.clear(); runCurrent()
        assertFalse(chat.running); assertTrue(chat.messages.isEmpty())
    }

    @Test fun contextChangesCancelAndEraseHistoryEvenWithoutAVisibleChatScreen() = runTest(dispatcher) {
        val changes = listOf(
            AppState(loading = false),
            state.copy(user = other),
            state.copy(groupId = "another-family"),
            state.copy(assistantTarget = other.id),
            state.copy(assistantPhone = false),
            state.copy(members = emptyList()),
            state.copy(bindingOnly = true),
        )
        for (next in changes) {
            var cancelled = false
            val chat = ChatSession(backgroundScope) { _, _, _, emit ->
                try {
                    emit("delta", "旧回复")
                    emit("suggestions", JSONArray().put("旧建议"))
                    emit("medicine_draft", json("draft_id" to "old", "name" to "旧草稿"))
                    awaitCancellation()
                } finally { cancelled = true }
            }
            chat.bind(state); chat.text = "旧问题"; chat.send(); runCurrent()
            chat.text = "未发送"; chat.photoDialog = true
            chat.bind(next); runCurrent()
            assertTrue(cancelled); assertFalse(chat.running)
            assertTrue(chat.messages.isEmpty()); assertTrue(chat.partial.isEmpty()); assertTrue(chat.text.isEmpty())
            assertTrue(chat.medicineDrafts.isEmpty()); assertTrue(chat.proposals.isEmpty()); assertTrue(chat.suggestions.isEmpty())
            assertFalse(chat.photoDialog); assertNull(chat.error)
        }
    }

    @Test fun viewModelScopeCancellationStopsTheRequest() = runTest(dispatcher) {
        val owner = CoroutineScope(SupervisorJob() + dispatcher)
        var cancelled = false
        val chat = ChatSession(owner) { _, _, _, _ ->
            try { awaitCancellation() } finally { cancelled = true }
        }
        chat.bind(state); chat.text = "问题"; chat.send(); runCurrent()
        owner.cancel(); runCurrent()
        assertTrue(cancelled); assertFalse(chat.running)
    }
}
