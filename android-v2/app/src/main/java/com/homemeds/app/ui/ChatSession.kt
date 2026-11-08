package com.homemeds.app.ui

import androidx.compose.runtime.*
import com.homemeds.app.*
import com.homemeds.app.net.AiAgent
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** In-memory conversation owned by the activity ViewModel, not by a particular composition.
 * Never put medical messages or multi-megabyte photos into saved-instance-state Bundles.
 */
internal class ChatSession(
    private val scope: CoroutineScope,
    private val runChat: suspend (JSONObject, String?, Boolean, suspend (String, Any) -> Unit) -> Unit =
        { body, group, phone, emit -> AiAgent.runChat(body, group, phone, emit) },
) {
    private data class Context(val user: String, val group: String, val target: String?, val phone: Boolean)
    private var context: Context? = null
    private var generation = 0
    private var stream: Job? = null

    var text by mutableStateOf("")
    var messages by mutableStateOf<List<ChatMessage>>(emptyList())
        private set
    var proposals by mutableStateOf<List<Proposal>>(emptyList())
    var medicineDrafts by mutableStateOf<List<JSONObject>>(emptyList())
    var intake by mutableStateOf<JSONObject?>(null)
    var photoDialog by mutableStateOf(false)
    var photos by mutableStateOf<List<PhotoAttachment>>(emptyList())
    var editor by mutableStateOf<Proposal?>(null)
    var pause by mutableStateOf<Proposal?>(null)
    var suggestions by mutableStateOf<List<String>>(emptyList())
        private set
    var running by mutableStateOf(false)
        private set
    var partial by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)

    fun bind(state: AppState) {
        val next = state.user?.takeUnless { state.bindingOnly }?.let { user ->
            state.groupId?.let { Context(user.id, it, assistantTarget(state), state.assistantPhone) }
        }
        if (next == context) return
        context = next
        clear()
    }

    fun clear() {
        // Invalidate before cancelling: late events/finally blocks may not revive a cleared session.
        generation++
        stream?.cancel()
        stream = null
        text = ""; messages = emptyList(); proposals = emptyList(); medicineDrafts = emptyList()
        intake = null; photoDialog = false; photos = emptyList(); editor = null; pause = null
        suggestions = emptyList(); running = false; partial = ""; error = null
    }

    fun stop() { stream?.cancel() }

    fun send() {
        val current = context ?: return
        val target = current.target ?: return
        val prompt = text.trim().ifBlank {
            if (photos.isNotEmpty()) "请综合这些照片中同一药盒的各个角度识别药品并生成待确认入库草稿" else ""
        }
        if (running || prompt.isBlank()) return
        val epoch = generation
        text = ""; error = null; partial = ""; suggestions = emptyList(); running = true
        messages = (messages + ChatMessage("user", prompt.take(6000), photos.map { it.dataUrl })).takeLast(19)
        photos = emptyList()
        val history = messages
        stream = scope.launch {
            try {
                runChat(json("user_id" to target, "intent" to "chat",
                    "messages" to JSONArray(history.map {
                        if (it.role == "user") AiAgent.userMessage(it.content.take(6000), it.imageDataUrls)
                        else json("role" to it.role, "content" to it.content.take(6000))
                    })), current.group, current.phone) { event, data ->
                    withContext(Dispatchers.Main) {
                        if (epoch == generation) when (event) {
                            "delta" -> partial += data.toString()
                            "suggestions" -> suggestions = (data as JSONArray).strings()
                            "draft" -> try {
                                val proposal = parseProposal(data as JSONObject, target)
                                if (proposals.none { it.id == proposal.id }) proposals = proposals + proposal
                            } catch (e: Exception) { error = e.message ?: "草稿不完整，未加入列表" }
                            "draft_error" -> error = (data as? JSONObject)?.optString("message")
                            "medicine_draft" -> {
                                val draft = data as JSONObject
                                if (medicineDrafts.none { it.optString("draft_id") == draft.getString("draft_id") })
                                    medicineDrafts = medicineDrafts + draft
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                if (epoch == generation) { suggestions = emptyList(); error = "已取消，草稿尚未保存" }
                throw e
            } catch (e: Exception) {
                if (epoch == generation) { suggestions = emptyList(); error = e.message ?: "对话失败，请重试" }
            } finally {
                if (epoch == generation) {
                    if (partial.isNotBlank()) messages = (messages + ChatMessage("assistant", partial.take(6000))).takeLast(20)
                    partial = ""; running = false
                }
            }
        }
    }
}
