package com.homemeds.app

import com.homemeds.app.net.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader
import java.util.concurrent.TimeUnit

class ApiClientTest {
    @Test fun datePhotoCancellationClosesSessionWithoutEmittingResults() = runBlocking {
        val waiting = CompletableDeferred<Unit>()
        val backend = object : AgentBackend() {
            override suspend fun complete(s: AiAgent.Opened, messages: JSONArray, tools: JSONArray?, required: Boolean,
                schema: JSONObject?, emit: (suspend (String, Any) -> Unit)?): JSONObject {
                waiting.complete(Unit)
                awaitCancellation()
            }
        }
        val events = mutableListOf<String>()
        val job = launch { AiAgent.runPackageDetails(listOf(image), "g1", backend) { event, _ -> events += event } }
        waiting.await(); job.cancelAndJoin()
        assertTrue(events.isEmpty())
        assertEquals(listOf("chat" to false), backend.closed)
    }

    @Test fun datePhotoRespectsConfiguredPromptAndStructuredOutputModes() = runBlocking {
        for (mode in listOf("prompt", "json_schema")) {
            var requested: JSONObject? = null
            val backend = object : AgentBackend(reply(json("expiry_date" to null, "batch_number" to null).toString())) {
                override suspend fun complete(s: AiAgent.Opened, messages: JSONArray, tools: JSONArray?, required: Boolean,
                    schema: JSONObject?, emit: (suspend (String, Any) -> Unit)?): JSONObject {
                    requested = schema
                    return super.complete(s, messages, tools, required, schema, emit)
                }
            }
            backend.settings.put("output_mode", mode)
            AiAgent.runPackageDetails(listOf(image), "g1", backend) { _, _ -> }
            if (mode == "prompt") assertNull(requested)
            else assertEquals(listOf("expiry_date", "batch_number"), requested!!.getJSONArray("required").strings())
        }
    }

    @Test fun medicinePhotoAndConversationAcceptMonthOnlyExpiryWithoutInventingDay() = runBlocking {
        val med = medicine().put("expiry_date", "2028.06")
        val lookup = AgentBackend(reply(med.toString()))
        var found: JSONObject? = null
        AiAgent.runLookup("药盒", "g1", true, image, lookup) { event, data -> if (event == "result") found = data as JSONObject }
        assertEquals("2028-06", found!!.getString("expiry_date"))
        val chat = AgentBackend(calls("add_medicine_draft" to med), reply("请核对"))
        AiAgent.runChat(chatBody(), "g1", true, chat) { event, data ->
            if (event == "medicine_draft") assertEquals("2028-06", (data as JSONObject).getString("expiry_date"))
        }
    }

    @Test fun dateCloseupsNeedNoDrugNameSearchOrRemoteToolsAndPreserveSixPhotos() = runBlocking {
        val backend = AgentBackend(reply(json("expiry_date" to "2028年2月", "batch_number" to "LOT-8").toString()))
        val photos = List(6) { "$image-$it" }
        var details: JSONObject? = null
        AiAgent.runPackageDetails(photos, "g1", backend) { event, data -> if (event == "result") details = data as JSONObject }
        assertEquals("2028-02", details!!.getString("expiry_date"))
        assertEquals("LOT-8", details!!.getString("batch_number"))
        assertFalse(details!!.has("name"))
        assertEquals(7, backend.requests.single().getJSONObject(1).getJSONArray("content").length())
        assertTrue(backend.requests.single().getJSONObject(0).getString("content").contains("不得把生产日期当有效期"))
        assertEquals(listOf("chat"), backend.opened)
        assertTrue(backend.remoteCalls.isEmpty())
        assertTrue(backend.searchSessions.isEmpty())
        assertNull(backend.advertised.single())
        assertEquals(listOf("chat" to true), backend.closed)
    }

    @Test fun unreadableDateStaysNullAndMalformedDateGetsOnlyOneRepair() = runBlocking {
        val backend = AgentBackend(reply(json("expiry_date" to "2028-02-31", "batch_number" to null).toString()),
            reply(json("expiry_date" to null, "batch_number" to "ONLY-LOT").toString()))
        var result: JSONObject? = null
        AiAgent.runPackageDetails(listOf(image), "g1", backend) { event, data -> if (event == "result") result = data as JSONObject }
        assertTrue(result!!.isNull("expiry_date"))
        assertEquals("ONLY-LOT", result!!.getString("batch_number"))
        assertEquals(2, backend.requests.size)
        assertEquals(listOf("chat" to true), backend.closed)
    }

    @Test fun packagePhotoValidationRejectsInvalidBatchAndClosesSessionOnFailure() = runBlocking {
        val invalid = reply(json("expiry_date" to "2028-13", "batch_number" to null).toString())
        val backend = AgentBackend(invalid, invalid)
        try { AiAgent.runPackageDetails(listOf(image), "g1", backend) { _, _ -> }; fail("must reject invalid date") }
        catch (_: UserError) { }
        assertEquals(listOf("chat" to false), backend.closed)
        for (images in listOf(emptyList(), List(7) { image })) {
            val unopened = AgentBackend()
            try { AiAgent.runPackageDetails(images, "g1", unopened) { _, _ -> }; fail("must reject photo batch") }
            catch (_: UserError) { }
            assertTrue(unopened.opened.isEmpty())
        }
    }

    @Test fun ordinaryChatProactivelyEmitsBothDraftsAndModelRepliesWithoutSaving() = runBlocking {
        val backend = AgentBackend(calls("add_medicine_draft" to medicine(), "add_plan_draft" to json("medicine_name" to "测试药")),
            reply("请核对卡片。<suggested_replies>[\"我先核对药盒\",\"我补充已有医嘱\"]</suggested_replies>"))
        val events = mutableListOf<Pair<String, Any>>()
        AiAgent.runChat(chatBody(intent = "chat"), "g1", true, backend) { e, d -> events += e to d }
        assertEquals(1, events.count { it.first == "medicine_draft" })
        assertEquals(1, events.count { it.first == "draft" })
        assertEquals(listOf("add_plan_draft"), backend.remoteCalls)
        assertEquals("请核对卡片。", events.filter { it.first == "delta" }.joinToString("") { it.second.toString() })
        assertEquals(listOf("我先核对药盒", "我补充已有医嘱"), (events.single { it.first == "suggestions" }.second as JSONArray).strings())
        assertTrue(backend.requests.first().getJSONObject(0).getString("content").contains("<suggested_replies>"))
        assertEquals("done", events.last().first)
        assertEquals(listOf("chat" to true), backend.closed)
    }

    @Test fun serverModePreservesStringSchemaAndSeparatesSuggestionsFromThinking() = runBlocking {
        val original = chatBody(false).put("messages", JSONArray(List(20) { json("role" to "user", "content" to "原消息$it") }))
        val snapshot = original.toString()
        var sent: JSONObject? = null
        val backend = object : AgentBackend() {
            override suspend fun streamChat(body: JSONObject, group: String?, emit: suspend (String, Any) -> Unit) {
                assertEquals("g1", group)
                sent = body
                val response = "<think>不显示的思考</think>请补拍。<suggested_replies>[\"我补拍盒底\"]</suggested_replies>"
                response.chunked(3).forEach { emit("delta", it) }
                emit("draft", json("draft_id" to "pending"))
                emit("done", JSONObject())
            }
        }
        val events = mutableListOf<Pair<String, Any>>()
        AiAgent.runChat(original, "g1", false, backend) { e, d -> events += e to d }
        assertEquals(snapshot, original.toString())
        assertTrue(backend.opened.isEmpty())
        val messages = sent!!.getJSONArray("messages").objects()
        assertEquals(20, messages.size)
        assertTrue(messages.all { it.getString("role") in setOf("user", "assistant") && it.get("content") is String && it.getString("content").length <= 6000 })
        assertEquals("原消息19", messages[messages.lastIndex - 1].getString("content"))
        assertTrue(messages.last().getString("content").contains("<suggested_replies>"))
        assertEquals("请补拍。", events.filter { it.first == "delta" }.joinToString("") { it.second.toString() })
        assertEquals(listOf("我补拍盒底"), (events.single { it.first == "suggestions" }.second as JSONArray).strings())
        assertEquals("pending", (events.single { it.first == "draft" }.second as JSONObject).getString("draft_id"))
        assertEquals("done", events.last().first)
    }

    @Test fun ordinaryChatAsksForMissingDetailsInsteadOfCreatingAnUnrequestedPlan() = runBlocking {
        val backend = AgentBackend(calls("request_plan_details" to json("question" to "请提供已有医嘱中的时间和用量")))
        val events = mutableListOf<Pair<String, Any>>()
        AiAgent.runChat(chatBody(false, "chat"), "g1", true, backend) { e, d -> events += e to d }
        assertTrue(backend.remoteCalls.isEmpty())
        assertFalse(events.any { it.first == "draft" })
        assertTrue((events.last().second as JSONObject).getBoolean("needs_details"))
    }

    private val image = "data:image/jpeg;base64,cGhvdG8="
    private val source = "https://example.com/medicine"
    private fun medicine() = json("name" to "测试药", "generic_name" to "测试通用名", "manufacturer" to "测试厂家",
        "specification" to "10mg × 24片", "base_unit" to "片", "units_per_package" to 24,
        "expiry_date" to "2028-06-30", "batch_number" to "B202611", "code" to "12345678901234567890",
        "indications" to null, "precautions" to null, "sources" to JSONArray())
    private fun reply(text: String) = json("role" to "assistant", "content" to text)
    private fun calls(vararg pairs: Pair<String, JSONObject>) = json("role" to "assistant", "content" to "",
        "tool_calls" to JSONArray(pairs.mapIndexed { i, (name, args) ->
            json("id" to "call-$i", "type" to "function", "function" to json("name" to name, "arguments" to args.toString()))
        }))
    private fun chatBody(withImage: Boolean = true, intent: String = "chat") = json("user_id" to "u1", "intent" to intent,
        "messages" to JSONArray().put(AiAgent.userMessage("请识别药品", if (withImage) image else null)))

    private open inner class AgentBackend(vararg responses: JSONObject) : AiAgent.Backend() {
        val queue = ArrayDeque(responses.toList())
        val opened = mutableListOf<String>()
        val closed = mutableListOf<Pair<String, Boolean>>()
        val requests = mutableListOf<JSONArray>()
        val advertised = mutableListOf<JSONArray?>()
        val remoteCalls = mutableListOf<String>()
        val searchSessions = mutableListOf<String>()
        val pages = mutableListOf<String>()
        val settings = json("max_llm_calls" to 6, "max_tool_calls" to 5, "search_mode" to "external",
            "query_mode" to "tools", "output_mode" to "prompt")
        override suspend fun currentUser(group: String?) = "u1"
        override suspend fun open(kind: String, body: JSONObject, group: String?): AiAgent.Opened? {
            assertEquals("g1", group)
            opened += kind
            return AiAgent.Opened(kind, settings)
        }
        override suspend fun complete(s: AiAgent.Opened, messages: JSONArray, tools: JSONArray?, required: Boolean,
            schema: JSONObject?, emit: (suspend (String, Any) -> Unit)?): JSONObject {
            requests += JSONArray(messages.toString())
            advertised += tools
            val result = queue.removeFirst()
            if (result.optString("content").isNotBlank()) emit?.invoke("delta", result.getString("content"))
            return result
        }
        override suspend fun remote(s: AiAgent.Opened, name: String, args: JSONObject, group: String?): JSONObject {
            assertEquals("chat", s.id)
            remoteCalls += name
            return if (name == "add_plan_draft") json("draft" to json("operation" to "create",
                "draft" to plan().body().put("user_id", "u1"))) else json("result" to JSONObject())
        }
        override suspend fun search(s: AiAgent.Opened, query: String, extraAllowed: () -> Boolean): List<JSONObject> {
            searchSessions += s.id
            return listOf(json("url" to source, "title" to "公开说明书", "content" to "测试药 24片"))
        }
        override suspend fun page(s: AiAgent.Opened, url: String): JSONObject {
            pages += url
            return json("url" to url, "content" to "测试药公开说明书")
        }
        override suspend fun finish(s: AiAgent.Opened, group: String?, success: Boolean) { closed += s.id to success }
    }

    @Test fun multimodalUserMessageRetainsPlainTextCompatibility() {
        assertEquals("文字", AiAgent.userMessage("文字").getString("content"))
        assertEquals("文字", AiAgent.userMessage("文字", "").getString("content"))
        val parts = AiAgent.userMessage("照片", image).getJSONArray("content")
        assertEquals(2, parts.length())
        assertEquals("text", parts.getJSONObject(0).getString("type"))
        assertEquals("照片", parts.getJSONObject(0).getString("text"))
        assertEquals("image_url", parts.getJSONObject(1).getString("type"))
        assertEquals(image, parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
    }

    @Test fun multiAngleLookupSendsAllSixImagesInOrderInOneSessionAndOneResult() = runBlocking {
        val images = List(MAX_MEDICINE_PHOTOS) { "data:image/jpeg;base64,angle-$it" }
        val backend = AgentBackend(reply(medicine().toString()))
        val events = mutableListOf<Pair<String, Any>>()
        AiAgent.runLookup("综合正面背面盒底信息", "g1", false, null, backend, imageDataUrls = images) { e, d -> events += e to d }
        assertEquals(listOf("chat"), backend.opened)
        val content = backend.requests.single().getJSONObject(1).getJSONArray("content")
        assertEquals(7, content.length())
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals(images, (1 until content.length()).map { content.getJSONObject(it).getJSONObject("image_url").getString("url") })
        assertEquals(1, events.count { it.first == "result" })
        val result = events.single { it.first == "result" }.second as JSONObject
        assertEquals("2028-06-30", result.getString("expiry_date"))
        assertEquals("B202611", result.getString("batch_number"))
        assertEquals("pending", result.getString("status"))
        assertTrue(backend.remoteCalls.isEmpty())
        assertEquals(listOf("chat" to true), backend.closed)
    }

    @Test fun emptyPhotoListRetainsTextLookupAndOldSinglePhotoCanBeCombinedWithMoreAngles() = runBlocking {
        assertEquals("纯文本", AiAgent.userMessage("纯文本", emptyList()).getString("content"))
        assertEquals(listOf(image, "second"), medicineImages(image, listOf("", "second")))
        val backend = AgentBackend(reply(medicine().toString()))
        AiAgent.runLookup("药盒", "g1", true, image, backend, imageDataUrls = listOf("second")) { _, _ -> }
        assertEquals(3, backend.requests.single().getJSONObject(1).getJSONArray("content").length())
    }

    @Test fun oversizedPhotoBatchIsRejectedBeforeOpeningAnySession() = runBlocking {
        val backend = AgentBackend()
        try {
            AiAgent.runLookup("药盒", "g1", true, null, backend, imageDataUrls = List(7) { image }) { _, _ -> }
            fail("expected photo limit")
        } catch (e: UserError) { assertTrue(e.message!!.contains("最多添加 6 张")) }
        assertTrue(backend.opened.isEmpty())
        try {
            AiAgent.userMessage("药盒", List(7) { image })
            fail("expected photo limit")
        } catch (e: UserError) { assertTrue(e.message!!.contains("最多添加 6 张")) }
        val rawParts = JSONArray().put(json("type" to "text", "text" to "照片"))
        repeat(7) { rawParts.put(json("type" to "image_url", "image_url" to json("url" to image))) }
        try {
            AiAgent.runChat(chatBody(false).put("messages", JSONArray().put(json("role" to "user", "content" to rawParts))),
                "g1", true, backend) { _, _ -> }
            fail("expected chat photo limit")
        } catch (e: UserError) { assertTrue(e.message!!.contains("最多添加 6 张")) }
        assertTrue(backend.opened.isEmpty())
    }

    @Test fun chatForcesDirectMultiImageMessageAndEmitsOneMedicineDraft() = runBlocking {
        val images = List(3) { "data:image/jpeg;base64,chat-angle-$it" }
        val backend = AgentBackend(calls("add_medicine_draft" to medicine()), reply("请核对"))
        val body = chatBody(false).put("messages", JSONArray().put(AiAgent.userMessage("综合各角度识别", images)))
        val events = mutableListOf<String>()
        AiAgent.runChat(body, "g1", false, backend) { e, _ -> events += e }
        assertEquals(listOf("chat"), backend.opened)
        assertEquals(4, backend.requests.first().getJSONObject(1).getJSONArray("content").length())
        assertEquals(1, events.count { it == "medicine_draft" })
        assertTrue(backend.remoteCalls.isEmpty())
    }

    @Test fun photoLookupForcesDirectConnectionAndParsesContainerFieldsWithoutSearch() = runBlocking {
        val backend = AgentBackend(reply(medicine().toString()))
        val events = mutableListOf<Pair<String, Any>>()
        AiAgent.runLookup("识别药盒", "g1", false, image, backend) { e, d -> events += e to d }
        assertEquals(listOf("chat"), backend.opened)
        val content = backend.requests.single().getJSONObject(1).getJSONArray("content")
        assertEquals(image, content.getJSONObject(1).getJSONObject("image_url").getString("url"))
        val result = events.single { it.first == "result" }.second as JSONObject
        assertEquals("2028-06-30", result.getString("expiry_date"))
        assertEquals("B202611", result.getString("batch_number"))
        assertEquals("12345678901234567890", result.getString("code"))
        assertEquals(24, result.getInt("units_per_package"))
        assertEquals("pending", result.getString("status"))
        assertTrue(result.getBoolean("requires_confirmation"))
        assertEquals(0, result.getJSONArray("sources").length())
        assertTrue(backend.remoteCalls.isEmpty())
        assertEquals(listOf("chat" to true), backend.closed)
    }

    @Test fun chatEmitsBothDraftTypesAndDeduplicatesWithoutSaving() = runBlocking {
        val med = medicine()
        val backend = AgentBackend(calls("add_medicine_draft" to med, "add_plan_draft" to json("medicine_name" to "测试药")),
            calls("add_medicine_draft" to med), reply("请核对卡片"))
        val events = mutableListOf<Pair<String, Any>>()
        AiAgent.runChat(chatBody(intent = "draft"), "g1", false, backend) { e, d -> events += e to d }
        assertEquals(listOf("chat"), backend.opened)
        assertEquals(1, events.count { it.first == "medicine_draft" })
        assertEquals(1, events.count { it.first == "draft" })
        assertEquals(listOf("add_plan_draft"), backend.remoteCalls)
        val medDraft = events.single { it.first == "medicine_draft" }.second as JSONObject
        assertTrue(medDraft.getBoolean("requires_confirmation"))
        assertTrue(medDraft.getString("draft_id").isNotBlank())
        val planDraft = events.single { it.first == "draft" }.second as JSONObject
        assertEquals("u1", com.homemeds.app.ui.parseProposal(planDraft, "u1").draft.getString("user_id"))
        assertEquals(2, (events.last().second as JSONObject).getInt("drafts_added"))
    }

    @Test fun localSearchSessionIsReusedAndDoesNotConsumeFiveRemoteToolSlots() = runBlocking {
        val searches = Array(4) { "web_search" to json("query" to "测试药") }
        val remotes = Array(5) { "list_medicines" to JSONObject() }
        val backend = AgentBackend(calls(*searches, "add_medicine_draft" to medicine()),
            calls(*remotes), reply("请核对"))
        val events = mutableListOf<String>()
        AiAgent.runChat(chatBody(false), "g1", true, backend) { e, _ -> events += e }
        assertEquals(listOf("chat", "lookup"), backend.opened)
        assertEquals(List(4) { "lookup" }, backend.searchSessions)
        assertEquals(5, backend.remoteCalls.size)
        assertEquals(1, events.count { it == "medicine_draft" })
        assertEquals(listOf("lookup" to true, "chat" to true), backend.closed)
    }

    @Test fun textLookupRejectsInventedSourcesButAcceptsActualSearchResults() = runBlocking {
        val forged = medicine().put("sources", JSONArray().put(json("url" to source, "title" to "伪造来源")))
        val invalid = AgentBackend(reply(forged.toString()), reply(forged.toString()))
        try {
            AiAgent.runLookup("测试药", "g1", true, null, invalid) { _, _ -> }
            fail("text lookup must have an actual search")
        } catch (e: UserError) { assertTrue(e.message!!.contains("来源")) }
        assertEquals(listOf("lookup" to false), invalid.closed)

        val valid = AgentBackend(calls("web_search" to json("query" to "测试药")), reply(forged.toString()))
        var result: JSONObject? = null
        AiAgent.runLookup("测试药", "g1", true, null, valid) { e, d -> if (e == "result") result = d as JSONObject }
        assertEquals("测试药", valid.requests.first().getJSONObject(1).getString("content"))
        assertEquals("公开说明书", result!!.getJSONArray("sources").getJSONObject(0).getString("title"))
        assertEquals(source, result!!.getJSONArray("sources").getJSONObject(0).getString("url"))
    }

    @Test fun fetchPageIsRestrictedToDiscoveredSources() = runBlocking {
        val backend = AgentBackend(calls("web_search" to json("query" to "测试药")),
            calls("fetch_page" to json("url" to "https://unrelated.example.com/"),
                "fetch_page" to json("url" to source)), reply(medicine().toString()))
        AiAgent.runLookup("测试药", "g1", true, null, backend) { _, _ -> }
        assertEquals(listOf(source), backend.pages)
        val errors = backend.requests.last().objects().filter { it.optString("role") == "tool" }
            .map { JSONObject(it.getString("content")) }.filter { it.has("error") }
        assertEquals(1, errors.size)
        assertTrue(errors.single().getString("error").contains("搜索结果"))
    }

    @Test fun visionErrorsHaveActionableMessageAndCloseSessions() = runBlocking {
        for (status in listOf(400, 422)) {
            for (chat in listOf(true, false)) {
                val backend = object : AgentBackend() {
                    override suspend fun complete(s: AiAgent.Opened, messages: JSONArray, tools: JSONArray?, required: Boolean,
                        schema: JSONObject?, emit: (suspend (String, Any) -> Unit)?): JSONObject = throw ApiException(status, "unsupported")
                }
                try {
                    if (chat) AiAgent.runChat(chatBody(), "g1", false, backend) { _, _ -> }
                    else AiAgent.runLookup("识图", "g1", false, image, backend) { _, _ -> }
                    fail("expected vision error")
                } catch (e: UserError) { assertEquals("当前模型不支持图片识别，请在 AI 配置中切换支持视觉的模型（如 qwen3.5）", e.message) }
                assertEquals(listOf("chat" to false), backend.closed)
            }
        }
    }

    @Test fun imageNeverFallsBackToStringOnlyServerWhenSessionUnavailable() = runBlocking {
        val backend = object : AgentBackend() {
            override suspend fun open(kind: String, body: JSONObject, group: String?): AiAgent.Opened? = null
        }
        try {
            AiAgent.runChat(chatBody(), "g1", false, backend) { _, _ -> }
            fail("expected direct-session error")
        } catch (e: UserError) { assertTrue(e.message!!.contains("手机直连")) }
        try {
            AiAgent.runLookup("识图", "g1", false, image, backend) { _, _ -> }
            fail("expected direct-session error")
        } catch (e: UserError) { assertTrue(e.message!!.contains("手机直连")) }
    }

    @Test fun missingSearchCredentialsStillAllowVisiblePhotoDraft() = runBlocking {
        val backend = object : AgentBackend(calls("web_search" to json("query" to "测试药")), reply(medicine().toString())) {
            override suspend fun open(kind: String, body: JSONObject, group: String?): AiAgent.Opened? {
                if (kind == "lookup") throw ApiException(503, "请配置 Tavily")
                return super.open(kind, body, group)
            }
        }
        var result: JSONObject? = null
        AiAgent.runLookup("识图", "g1", true, image, backend) { e, d -> if (e == "result") result = d as JSONObject }
        assertEquals("测试药", result!!.getString("name"))
        assertEquals(0, result!!.getJSONArray("sources").length())
    }

    @Test fun requestDetailsStopsThisTurnAndDoesNotExecuteLaterDrafts() = runBlocking {
        val backend = AgentBackend(calls("request_plan_details" to json("question" to "请提供服药时间"),
            "add_plan_draft" to JSONObject()))
        val events = mutableListOf<Pair<String, Any>>()
        AiAgent.runChat(chatBody(false, "draft"), "g1", true, backend) { e, d -> events += e to d }
        assertEquals(1, backend.requests.size)
        assertTrue(backend.remoteCalls.isEmpty())
        assertTrue((events.last().second as JSONObject).getBoolean("needs_details"))
        assertEquals(listOf("chat" to true), backend.closed)
    }

    @Test fun cancellationClosesPrimaryAndLazySearchSessions() = runBlocking {
        val waiting = CompletableDeferred<Unit>()
        val backend = object : AgentBackend(calls("web_search" to json("query" to "测试药"))) {
            override suspend fun complete(s: AiAgent.Opened, messages: JSONArray, tools: JSONArray?, required: Boolean,
                schema: JSONObject?, emit: (suspend (String, Any) -> Unit)?): JSONObject {
                if (queue.isNotEmpty()) return super.complete(s, messages, tools, required, schema, emit)
                waiting.complete(Unit)
                awaitCancellation()
            }
        }
        val job = launch { AiAgent.runChat(chatBody(false), "g1", true, backend) { _, _ -> } }
        withTimeout(2000) { waiting.await() }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(listOf("lookup" to true, "chat" to false), backend.closed)
    }

    @Test fun remoteAndLocalBudgetsRejectExtraCallsEvenWithinTheSameModelResponse() = runBlocking {
        val backend = AgentBackend(
            calls(*Array(4) { "list_medicines" to JSONObject() }),
            calls("list_medicines" to JSONObject(), "list_medicines" to JSONObject()),
            calls("add_medicine_draft" to medicine(), "add_medicine_draft" to medicine()),
            reply("请核对"))
        backend.settings.put("max_tool_calls", 1)
        val events = mutableListOf<String>()
        AiAgent.runChat(chatBody(), "g1", true, backend) { e, _ -> events += e }
        assertEquals(5, backend.remoteCalls.size)
        assertEquals(1, events.count { it == "medicine_draft" })
        val errors = backend.requests.last().objects().filter { it.optString("role") == "tool" }
            .map { JSONObject(it.getString("content")) }.filter { it.has("error") }
        assertEquals(2, errors.size)
        assertTrue(errors.any { it.getString("error").contains("服务端") })
        assertTrue(errors.any { it.getString("error").contains("本地") })
    }

    @Test fun loopNeverExceedsConfiguredLlmRounds() = runBlocking {
        val backend = AgentBackend(*Array(6) { calls("list_medicines" to JSONObject()) })
        backend.settings.put("max_llm_calls", 3)
        try {
            AiAgent.runChat(chatBody(false), "g1", true, backend) { _, _ -> }
            fail("expected loop limit")
        } catch (e: UserError) { assertTrue(e.message!!.contains("调用上限")) }
        assertEquals(3, backend.requests.size)
        assertNull(backend.advertised.last())
        assertEquals(2, backend.remoteCalls.size)
        assertEquals(listOf("chat" to false), backend.closed)
    }

    @Test fun builtinSearchUsesThePrimarySessionAndCountsTowardLlmBudget() = runBlocking {
        var builtinCalls = 0
        val backend = object : AgentBackend(reply(medicine().toString())) {
            override suspend fun builtin(s: AiAgent.Opened, query: String): JSONObject {
                builtinCalls++
                return json("content" to "公开说明书", "sources" to JSONArray().put(json("url" to source, "title" to "内置来源")))
            }
        }
        backend.settings.put("search_mode", "builtin").put("max_llm_calls", 3)
        var result: JSONObject? = null
        AiAgent.runLookup("测试药", "g1", true, null, backend) { e, d -> if (e == "result") result = d as JSONObject }
        assertEquals(listOf("lookup"), backend.opened)
        assertEquals(1, builtinCalls)
        assertEquals("内置来源", result!!.getJSONArray("sources").getJSONObject(0).getString("title"))
    }

    @Test fun cookieOriginGroupAndArrayContracts() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            var cookie: String? = null
            val origin = server.url("/").toString().trimEnd('/')
            val api = NativeApi(origin, { cookie }, { cookie = it }, { "g1" })
            server.enqueue(MockResponse().setHeader("Set-Cookie", "hm_session=test-token; Path=/; Max-Age=3600; HttpOnly").setBody("""{"ok":true}"""))
            api.obj("POST", "/auth/login", json("username" to "test"))
            assertEquals("test-token", cookie)
            val login = server.takeRequest()
            assertEquals(origin, login.getHeader("Origin"))
            assertEquals("/api/auth/login", login.path)
            server.enqueue(MockResponse().setBody("""[{"id":"p1"}]"""))
            assertEquals("p1", api.array("/plans").getJSONObject(0).getString("id"))
            val plans = server.takeRequest()
            assertEquals("hm_session=test-token", plans.getHeader("Cookie"))
            assertEquals("g1", plans.getHeader("X-Group-Id"))
            assertNull(plans.getHeader("Origin"))
            server.enqueue(MockResponse().setHeader("Set-Cookie", "hm_session=; Max-Age=0; Path=/").setBody("""{"ok":true}"""))
            api.obj("POST", "/auth/logout")
            assertNull(cookie)
        }
    }
    @Test fun errorsAndMalformedJsonAreNotSilentlyAccepted() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            var cookie: String? = "old"
            val api = NativeApi(server.url("/").toString().trimEnd('/'), { cookie }, { cookie = it }, { null })
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"请先登录"}"""))
            try { api.obj("GET", "/auth/me"); fail("expected 401") }
            catch (e: ApiException) { assertEquals(401, e.status); assertEquals("请先登录", e.message) }
            assertNull(cookie)
            server.enqueue(MockResponse().setBody("<html>not JSON</html>"))
            try { api.obj("GET", "/today"); fail("expected invalid JSON") }
            catch (e: Exception) { assertTrue(e is java.io.IOException || e is org.json.JSONException) }
        }
    }
    @Test fun streamFramingIncludesMultilineAndFinalEvent() = runBlocking {
        val events = mutableListOf<Pair<String, String>>()
        readSse(StringReader(": heartbeat\r\nevent: delta\r\ndata: line1\r\ndata: line2\r\n\r\nevent: done\ndata: {}").buffered()) { e,d -> events += e to d }
        assertEquals(listOf("delta" to "line1\nline2", "done" to "{}"), events)
    }
    @Test fun sseReportsServerErrors() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api = NativeApi(server.url("/").toString().trimEnd('/'), { null }, {}, { null })
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("event: error\ndata: \"查询失败\"\n\n"))
            try { api.sse("/ai/chat", json()) { _,_ -> }; fail("expected error event") }
            catch (e: UserError) { assertEquals("查询失败", e.message) }
        }
    }
    @Test fun cancelsSocketWhenCoroutineIsCancelled() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api = NativeApi(server.url("/").toString().trimEnd('/'), { null }, {}, { null })
            server.enqueue(MockResponse().setBody("""{"ok":true}""").setBodyDelay(10, TimeUnit.SECONDS))
            val job = launch(Dispatchers.Default) { api.obj("GET", "/today") }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
            withTimeout(2000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        }
    }
}
