package com.rememberflash.app.data.remote.firebase

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rememberflash.app.BuildConfig
import com.rememberflash.app.domain.model.CacheLookupStatus
import com.rememberflash.app.domain.model.MockExamDocument
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Snapshot de telemetria da camada NoSQL/Cache (Firebase Firestore) para auditoria e demonstração ao vivo.
 */
data class CacheMetricsSnapshot(
    val hitCount: Long,
    val missCount: Long,
    val invalidationCount: Long,
    val baselineSqlCount: Long,
    val lastStatus: CacheLookupStatus?,
    val lastLatencyMs: Double,
    val activeDocumentsCount: Int
)

/**
 * Contrato da camada Não Relacional Baseada em Documentos (Firebase Cloud Firestore)
 * dedicada exclusivamente ao domínio de provas/simulados (`mock_exams`).
 */
interface MockExamNoSqlDataSource {
    /**
     * Busca direta O(1) pelo ID do documento (`mock_exams/{documentId}`).
     * Retorna `null` em caso de Cache MISS (documento inexistente ou TTL de 24h expirado).
     */
    fun getDocument(documentId: String, nowMillis: Long = System.currentTimeMillis()): MockExamDocument?

    /**
     * Persiste o documento único e autocontido (`Self-contained Document`) na coleção `mock_exams`
     * no Firebase Cloud Firestore e na camada persistente local.
     */
    fun putDocument(document: MockExamDocument)

    /**
     * Invalidação na Escrita (Write Invalidation): remove imediatamente o documento da camada rápida
     * (`DEL mock_exam:contest_{id}_exam_full`) quando há alteração na Fonte da Verdade (SQL).
     */
    fun deleteDocument(documentId: String): Boolean

    /**
     * Atalho para invalidar o documento de simulado de um concurso específico.
     */
    fun invalidateContestExam(contestId: Long): Boolean

    /**
     * Limpa todos os documentos (útil para reset de testes ou logout).
     */
    fun clearAll()

    /**
     * Registra métrica de execução para relatórios e logs de demonstração.
     */
    fun recordOperation(status: CacheLookupStatus, latencyMs: Double)

    /**
     * Retorna o snapshot atual de métricas (HITs, MISSes, Invalidações).
     */
    fun getMetrics(): CacheMetricsSnapshot
}

/**
 * Implementação da camada NoSQL baseada em Documentos conectada ao **Firebase Cloud Firestore**
 * (`https://firestore.googleapis.com/v1/projects/{projectId}/databases/(default)/documents/mock_exams/{docId}`).
 *
 * Opera com:
 * - **Cloud Firestore Remote Store**: Persiste e consulta o documento real na coleção `mock_exams` do projeto Firebase na nuvem.
 * - **L1/L2 Local Cache**: Cache local de documentos (opcional/configurável via [enableLocalL1Cache]), reproduzindo o comportamento
 *   do SDK do Cloud Firestore e permitindo também medir chamadas 100% remotas (`enableLocalL1Cache = false`).
 */
@Singleton
class FirestoreMockExamDataSource(
    private val storageDirectory: File? = null,
    private val firebaseProjectId: String = BuildConfig.FIREBASE_PROJECT_ID,
    private val firebaseApiKey: String = BuildConfig.FIREBASE_API_KEY,
    private val enableLocalL1Cache: Boolean = true
) : MockExamNoSqlDataSource {

    @Inject
    constructor() : this(
        storageDirectory = null,
        firebaseProjectId = BuildConfig.FIREBASE_PROJECT_ID,
        firebaseApiKey = BuildConfig.FIREBASE_API_KEY,
        enableLocalL1Cache = true
    )

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // Camada L1: Cache de documentos em memória
    private val memoryStore = ConcurrentHashMap<String, MockExamDocument>()

    // Diretório L2: Persistência durável local de documentos JSON
    private val collectionDir: File by lazy {
        val baseDir = storageDirectory ?: File(System.getProperty("java.io.tmpdir"), "rememberflash_firestore")
        File(baseDir, COLLECTION_MOCK_EXAMS).apply {
            if (!exists()) mkdirs()
        }
    }

    private val hitCounter = AtomicLong(0L)
    private val missCounter = AtomicLong(0L)
    private val invalidationCounter = AtomicLong(0L)
    private val baselineCounter = AtomicLong(0L)

    @Volatile
    private var lastStatus: CacheLookupStatus? = null

    @Volatile
    private var lastLatencyMs: Double = 0.0

    fun isRemoteFirebaseConfigured(): Boolean {
        return firebaseProjectId.isNotBlank() && firebaseApiKey.isNotBlank()
    }

    private fun buildFirestoreDocumentUrl(documentId: String): String {
        return "https://firestore.googleapis.com/v1/projects/$firebaseProjectId/databases/(default)/documents/$COLLECTION_MOCK_EXAMS/$documentId?key=$firebaseApiKey"
    }

    override fun getDocument(documentId: String, nowMillis: Long): MockExamDocument? {
        // 1. Se o cache L1 local estiver habilitado, verifica memória primeiro
        if (enableLocalL1Cache) {
            val cachedInMemory = memoryStore[documentId]
            if (cachedInMemory != null) {
                if (!cachedInMemory.isExpired(nowMillis)) {
                    return cachedInMemory
                } else {
                    deleteDocumentInternal(documentId)
                    return null
                }
            }
        }

        // 2. Se o Firebase Cloud Firestore estiver configurado, busca diretamente na nuvem (GET)
        if (isRemoteFirebaseConfigured()) {
            val remoteDoc = fetchFromRemoteFirestore(documentId)
            if (remoteDoc != null) {
                if (!remoteDoc.isExpired(nowMillis)) {
                    if (enableLocalL1Cache) {
                        memoryStore[documentId] = remoteDoc
                        saveToLocalDisk(remoteDoc)
                    }
                    return remoteDoc
                } else {
                    deleteDocumentInternal(documentId)
                    return null
                }
            }
            // Se não existe no Cloud Firestore remoto quando enableLocalL1Cache=false, é MISS real!
            if (!enableLocalL1Cache) {
                return null
            }
        }

        // 3. Fallback na camada L2 Durável em Disco
        val docFile = File(collectionDir, "$documentId.json")
        if (docFile.exists() && docFile.isFile) {
            return try {
                val json = docFile.readText(Charsets.UTF_8)
                val doc = gson.fromJson(json, MockExamDocument::class.java)
                if (doc != null && !doc.isExpired(nowMillis)) {
                    if (enableLocalL1Cache) {
                        memoryStore[documentId] = doc
                    }
                    doc
                } else {
                    deleteDocumentInternal(documentId)
                    null
                }
            } catch (_: Exception) {
                null
            }
        }

        return null
    }

    override fun putDocument(document: MockExamDocument) {
        if (enableLocalL1Cache) {
            memoryStore[document.id] = document
        }
        saveToLocalDisk(document)

        if (isRemoteFirebaseConfigured()) {
            upsertToRemoteFirestore(document)
        }
    }

    override fun deleteDocument(documentId: String): Boolean {
        val removed = deleteDocumentInternal(documentId)
        invalidationCounter.incrementAndGet()
        safeLog("INVALIDATION", "DEL mock_exam:$documentId (Invalidação na escrita executada com sucesso)")
        return removed
    }

    override fun invalidateContestExam(contestId: Long): Boolean {
        val docId = MockExamDocument.buildDocumentId(contestId)
        return deleteDocument(docId)
    }

    private fun deleteDocumentInternal(documentId: String): Boolean {
        val removedFromMem = memoryStore.remove(documentId) != null
        val docFile = File(collectionDir, "$documentId.json")
        val removedFromDisk = if (docFile.exists()) docFile.delete() else false
        val removedFromRemote = if (isRemoteFirebaseConfigured()) {
            deleteFromRemoteFirestore(documentId)
        } else {
            false
        }
        return removedFromMem || removedFromDisk || removedFromRemote
    }

    private fun saveToLocalDisk(document: MockExamDocument) {
        try {
            val docFile = File(collectionDir, "${document.id}.json")
            docFile.writeText(gson.toJson(document), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    /**
     * Busca o documento diretamente na API REST v1 do Firebase Cloud Firestore.
     */
    fun fetchFromRemoteFirestore(documentId: String): MockExamDocument? {
        val request = Request.Builder()
            .url(buildFirestoreDocumentUrl(documentId))
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (response.code == 404) {
                return null
            }
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                throw IllegalStateException(
                    "Erro ao consultar Firebase Cloud Firestore (HTTP ${response.code}): $errBody"
                )
            }
            val bodyStr = response.body?.string() ?: return null
            val rootObj = JsonParser.parseString(bodyStr).asJsonObject
            val fieldsObj = rootObj.getAsJsonObject("fields") ?: return null
            val payloadJson = fieldsObj
                .getAsJsonObject("payload_json")
                ?.get("stringValue")
                ?.asString
                ?: return null
            return gson.fromJson(payloadJson, MockExamDocument::class.java)
        }
    }

    /**
     * Persiste (Create/Update via PATCH) o documento autocontido no Firebase Cloud Firestore.
     * Salva tanto os metadados estruturados (visíveis diretamente no Console Web do Firebase)
     * quanto a lista estruturada de questões e o `payload_json` completo.
     */
    fun upsertToRemoteFirestore(document: MockExamDocument) {
        val firestoreDoc = JsonObject()
        val fields = JsonObject()

        fields.add("id", stringField(document.id))
        fields.add("contest_id", intField(document.contestId))
        fields.add("contest_title", stringField(document.contestTitle))
        fields.add("organizer_name", stringField(document.organizerName))
        fields.add("version", intField(document.version.toLong()))
        fields.add("updated_at", intField(document.updatedAt))
        fields.add("ttl_expires_at", intField(document.ttlExpiresAt))
        fields.add("total_questions", intField(document.totalQuestions.toLong()))

        // Amostra estruturada no Console do Firebase Firestore + Payload completo O(1)
        val questionsValues = JsonArray()
        document.questions.take(5).forEach { q ->
            val qFields = JsonObject()
            qFields.add("question_id", intField(q.questionId))
            qFields.add("discipline_id", intField(q.disciplineId))
            qFields.add("discipline_name", stringField(q.disciplineName))
            qFields.add("topic_id", intField(q.topicId ?: 0L))
            qFields.add("statement", stringField(q.statement))
            qFields.add("correct_index", intField(q.correctIndex.toLong()))
            qFields.add("explanation", stringField(q.explanation ?: ""))

            val optsArray = JsonArray()
            q.options.forEach { opt -> optsArray.add(stringField(opt)) }
            val optsArrayWrapper = JsonObject().apply {
                add("arrayValue", JsonObject().apply { add("values", optsArray) })
            }
            qFields.add("options", optsArrayWrapper)

            val mapValueWrapper = JsonObject().apply {
                add("mapValue", JsonObject().apply { add("fields", qFields) })
            }
            questionsValues.add(mapValueWrapper)
        }
        fields.add(
            "questions_sample",
            JsonObject().apply {
                add("arrayValue", JsonObject().apply { add("values", questionsValues) })
            }
        )

        // Payload completo serializado contendo todas as 40 questões para desserialização O(1)
        fields.add("payload_json", stringField(gson.toJson(document)))

        firestoreDoc.add("fields", fields)

        val request = Request.Builder()
            .url(buildFirestoreDocumentUrl(document.id))
            .patch(firestoreDoc.toString().toRequestBody(jsonMediaType))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                throw IllegalStateException(
                    "Erro ao gravar documento no Firebase Cloud Firestore (HTTP ${response.code}): $errBody"
                )
            }
        }
    }

    /**
     * Remove (DELETE) o documento real na coleção `mock_exams` do Firebase Cloud Firestore.
     */
    fun deleteFromRemoteFirestore(documentId: String): Boolean {
        val request = Request.Builder()
            .url(buildFirestoreDocumentUrl(documentId))
            .delete()
            .build()

        httpClient.newCall(request).execute().use { response ->
            return response.isSuccessful
        }
    }

    private fun stringField(value: String): JsonObject =
        JsonObject().apply { addProperty("stringValue", value) }

    private fun intField(value: Long): JsonObject =
        JsonObject().apply { addProperty("integerValue", value.toString()) }

    override fun clearAll() {
        memoryStore.clear()
        try {
            collectionDir.listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }

    /**
     * Simula reinicialização do processo limpando apenas a memória volátil (L1),
     * mantendo os documentos persistidos na camada durável do Firestore.
     */
    fun simulateAppRestartClearVolatileMemoryOnly() {
        memoryStore.clear()
    }

    override fun recordOperation(status: CacheLookupStatus, latencyMs: Double) {
        lastStatus = status
        lastLatencyMs = latencyMs
        when (status) {
            CacheLookupStatus.HIT -> hitCounter.incrementAndGet()
            CacheLookupStatus.MISS -> missCounter.incrementAndGet()
            CacheLookupStatus.BASELINE_SQL -> baselineCounter.incrementAndGet()
        }
        safeLog(
            status.name,
            "Operação=${status.name} | Latência=${String.format(java.util.Locale.US, "%.4f", latencyMs)} ms"
        )
    }

    override fun getMetrics(): CacheMetricsSnapshot {
        return CacheMetricsSnapshot(
            hitCount = hitCounter.get(),
            missCount = missCounter.get(),
            invalidationCount = invalidationCounter.get(),
            baselineSqlCount = baselineCounter.get(),
            lastStatus = lastStatus,
            lastLatencyMs = lastLatencyMs,
            activeDocumentsCount = memoryStore.size
        )
    }

    private fun safeLog(tagSuffix: String, message: String) {
        try {
            android.util.Log.i("FirestoreCache[$tagSuffix]", message)
        } catch (_: Throwable) {
            // Ignora em execução de testes unitários JVM puros sem mock do android.util.Log
        }
    }

    companion object {
        const val COLLECTION_MOCK_EXAMS = "mock_exams"
    }
}
