package com.rememberflash.app

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rememberflash.app.data.local.database.dao.ContestDao
import com.rememberflash.app.data.local.database.dao.DisciplineDao
import com.rememberflash.app.data.local.database.dao.MockExamAttemptDao
import com.rememberflash.app.data.local.database.dao.QuestionDao
import com.rememberflash.app.data.local.database.entity.ContestEntity
import com.rememberflash.app.data.local.database.entity.DisciplineEntity
import com.rememberflash.app.data.local.database.entity.MockExamAttemptEntity
import com.rememberflash.app.data.local.database.entity.QuestionEntity
import com.rememberflash.app.data.remote.firebase.FirestoreMockExamDataSource
import com.rememberflash.app.data.remote.firebase.MockExamCacheService
import com.rememberflash.app.domain.common.Result
import com.rememberflash.app.domain.model.CacheLookupStatus
import com.rememberflash.app.domain.model.MockExamDocument
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.util.Locale
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * ETAPA 3 — BENCHMARK COMPARATIVO OFICIAL EM KOTLIN (100% NUVEM REAL: SUPABASE SQL vs. FIREBASE FIRESTORE)
 *
 * Conecta diretamente:
 * 1. Ao banco relacional PostgreSQL real no **Supabase** via PostgREST (`contests`, `disciplines`, `questions`).
 * 2. Ao banco Não Relacional real no **Firebase Cloud Firestore** (`mock_exams/contest_{id}_exam_full`).
 *
 * Para executar no terminal:
 *   .\gradlew.bat testDebugUnitTest --tests com.rememberflash.app.BenchmarkComparativoN2Test --rerun-tasks
 */
class BenchmarkComparativoN2Test {

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun loadLocalProperties(): Properties {
        val props = Properties()
        val candidates = listOf(
            File("local.properties"),
            File("../local.properties"),
            File("d:/Rememberflash-persistencia/local.properties")
        )
        val file = candidates.firstOrNull { it.exists() && it.isFile }
            ?: throw IllegalStateException("Arquivo local.properties não encontrado!")
        FileInputStream(file).use { props.load(it) }
        return props
    }

    /**
     * DAO Relacional conectado diretamente ao **Supabase PostgreSQL (PostgREST API)** na nuvem.
     * Cada chamada de leitura executa uma requisição HTTPS real ao banco PostgreSQL do Supabase.
     */
    inner class SupabaseRemoteContestDao(
        private val supabaseUrl: String,
        private val supabaseKey: String
    ) : ContestDao {
        var httpRequestsCount = 0

        override suspend fun getById(contestId: Long): ContestEntity? {
            httpRequestsCount++
            val url = "${supabaseUrl.trimEnd('/')}/rest/v1/contests?id=eq.$contestId&select=*"
            val request = Request.Builder()
                .url(url)
                .header("apikey", supabaseKey)
                .header("Authorization", "Bearer $supabaseKey")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Erro Supabase GET contests (HTTP ${response.code}): ${response.body?.string()}")
                }
                val arr = JsonParser.parseString(response.body?.string() ?: "[]").asJsonArray
                if (arr.isEmpty) return null
                val obj = arr[0].asJsonObject
                return ContestEntity(
                    id = obj.get("id").asLong,
                    userId = obj.get("user_id")?.asString ?: "usr_benchmark",
                    title = obj.get("title")?.asString ?: "Simulado Concurso",
                    description = obj.get("description")?.takeIf { !it.isJsonNull }?.asString ?: "",
                    organizerName = obj.get("organizer_name")?.takeIf { !it.isJsonNull }?.asString ?: "FGV",
                    questionType = obj.get("question_type")?.takeIf { !it.isJsonNull }?.asString ?: "Múltipla Escolha",
                    aiDifficulty = obj.get("ai_difficulty")?.takeIf { !it.isJsonNull }?.asString ?: "Difícil",
                    aiRigor = obj.get("ai_rigor")?.takeIf { !it.isJsonNull }?.asString ?: "Rigoroso",
                    aiTone = obj.get("ai_tone")?.takeIf { !it.isJsonNull }?.asString ?: "Formal",
                    isActive = obj.get("is_active")?.takeIf { !it.isJsonNull }?.asBoolean ?: true
                )
            }
        }

        override suspend fun insert(contest: ContestEntity): Long = contest.id
        override suspend fun insertAll(contests: List<ContestEntity>): List<Long> = contests.map { it.id }
        override suspend fun update(contest: ContestEntity) {}
        override suspend fun softDelete(contestId: Long, updatedAt: Long) {}
        override suspend fun delete(contestId: Long) {}
        override fun getActiveByUser(userId: String): Flow<List<ContestEntity>> = flowOf(emptyList())
        override fun getArchivedByUser(userId: String): Flow<List<ContestEntity>> = flowOf(emptyList())
        override suspend fun reactivate(contestId: Long, updatedAt: Long) {}
        override fun getAllByUser(userId: String): Flow<List<ContestEntity>> = flowOf(emptyList())
        override suspend fun getUnsyncedContests(): List<ContestEntity> = emptyList()
        override suspend fun getByIds(ids: List<Long>): List<ContestEntity> = emptyList()
        override suspend fun getAllContests(): List<ContestEntity> = emptyList()
        override suspend fun markContestsAsSynced(ids: List<Long>) {}
    }

    inner class SupabaseRemoteDisciplineDao(
        private val supabaseUrl: String,
        private val supabaseKey: String
    ) : DisciplineDao {
        var httpRequestsCount = 0

        override fun getByContest(contestId: Long): Flow<List<DisciplineEntity>> = flow {
            httpRequestsCount++
            val url = "${supabaseUrl.trimEnd('/')}/rest/v1/disciplines?contest_id=eq.$contestId&is_active=eq.true&order=id.asc&select=*"
            val request = Request.Builder()
                .url(url)
                .header("apikey", supabaseKey)
                .header("Authorization", "Bearer $supabaseKey")
                .get()
                .build()

            val list = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Erro Supabase GET disciplines (HTTP ${response.code}): ${response.body?.string()}")
                }
                val arr = JsonParser.parseString(response.body?.string() ?: "[]").asJsonArray
                arr.map { elem ->
                    val obj = elem.asJsonObject
                    DisciplineEntity(
                        id = obj.get("id").asLong,
                        contestId = obj.get("contest_id").asLong,
                        name = obj.get("name").asString,
                        weight = obj.get("weight")?.takeIf { !it.isJsonNull }?.asDouble ?: 1.0,
                        totalTopics = obj.get("total_topics")?.takeIf { !it.isJsonNull }?.asInt ?: 5,
                        isActive = obj.get("is_active")?.takeIf { !it.isJsonNull }?.asBoolean ?: true
                    )
                }
            }
            emit(list)
        }

        override suspend fun getById(disciplineId: Long): DisciplineEntity? {
            httpRequestsCount++
            val url = "${supabaseUrl.trimEnd('/')}/rest/v1/disciplines?id=eq.$disciplineId&select=*"
            val request = Request.Builder()
                .url(url)
                .header("apikey", supabaseKey)
                .header("Authorization", "Bearer $supabaseKey")
                .get()
                .build()

            return httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val arr = JsonParser.parseString(response.body?.string() ?: "[]").asJsonArray
                if (arr.isEmpty) return@use null
                val obj = arr[0].asJsonObject
                DisciplineEntity(
                    id = obj.get("id").asLong,
                    contestId = obj.get("contest_id").asLong,
                    name = obj.get("name").asString,
                    weight = obj.get("weight")?.takeIf { !it.isJsonNull }?.asDouble ?: 1.0,
                    totalTopics = obj.get("total_topics")?.takeIf { !it.isJsonNull }?.asInt ?: 5,
                    isActive = obj.get("is_active")?.takeIf { !it.isJsonNull }?.asBoolean ?: true
                )
            }
        }

        override suspend fun insert(discipline: DisciplineEntity): Long = discipline.id
        override suspend fun insertAll(disciplines: List<DisciplineEntity>): List<Long> = disciplines.map { it.id }
        override suspend fun update(discipline: DisciplineEntity) {}
        override suspend fun delete(disciplineId: Long) {}
        override suspend fun deleteByContest(contestId: Long) {}
        override suspend fun getUnsyncedDisciplines(): List<DisciplineEntity> = emptyList()
        override suspend fun getByIds(ids: List<Long>): List<DisciplineEntity> = emptyList()
        override suspend fun getAll(): List<DisciplineEntity> = emptyList()
        override suspend fun markDisciplinesAsSynced(ids: List<Long>) {}
        override fun getAllDisciplines(): Flow<List<DisciplineEntity>> = flowOf(emptyList())
    }

    inner class SupabaseRemoteQuestionDao(
        private val supabaseUrl: String,
        private val supabaseKey: String
    ) : QuestionDao {
        var httpRequestsCount = 0

        override fun getByDiscipline(disciplineId: Long): Flow<List<QuestionEntity>> = flow {
            httpRequestsCount++
            val qUrl = "${supabaseUrl.trimEnd('/')}/rest/v1/questions?discipline_id=eq.$disciplineId&order=id.asc&select=*"
            val qReq = Request.Builder()
                .url(qUrl)
                .header("apikey", supabaseKey)
                .header("Authorization", "Bearer $supabaseKey")
                .get()
                .build()

            val list = httpClient.newCall(qReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Erro Supabase GET questions (HTTP ${resp.code}): ${resp.body?.string()}")
                }
                val arr = JsonParser.parseString(resp.body?.string() ?: "[]").asJsonArray
                arr.map { elem ->
                    val obj = elem.asJsonObject
                    QuestionEntity(
                        id = obj.get("id").asLong,
                        disciplineId = obj.get("discipline_id").asLong,
                        topicId = obj.get("topic_id")?.takeIf { !it.isJsonNull }?.asLong,
                        statement = obj.get("statement").asString,
                        optionsJson = obj.get("options_json").asString,
                        correctIndex = obj.get("correct_index").asInt,
                        explanation = obj.get("explanation")?.takeIf { !it.isJsonNull }?.asString,
                        source = obj.get("source")?.takeIf { !it.isJsonNull }?.asString ?: "AI_GENERATED",
                        createdAt = obj.get("created_at")?.takeIf { !it.isJsonNull }?.asLong ?: System.currentTimeMillis()
                    )
                }
            }
            emit(list)
        }

        override suspend fun resetAnswersForContest(contestId: Long) {
            httpRequestsCount++
            val url = "${supabaseUrl.trimEnd('/')}/rest/v1/disciplines?contest_id=eq.$contestId&select=id"
            val req = Request.Builder()
                .url(url)
                .header("apikey", supabaseKey)
                .header("Authorization", "Bearer $supabaseKey")
                .get()
                .build()
            httpClient.newCall(req).execute().close()
        }

        /**
         * Atualiza uma questão real no Supabase PostgreSQL para demonstrar a Invalidação na Escrita ao vivo.
         */
        fun updateQuestionStatementInSupabase(questionId: Long, newStatement: String) {
            httpRequestsCount++
            val url = "${supabaseUrl.trimEnd('/')}/rest/v1/questions?id=eq.$questionId"
            val bodyJson = JsonObject().apply {
                addProperty("statement", newStatement)
            }
            val req = Request.Builder()
                .url(url)
                .header("apikey", supabaseKey)
                .header("Authorization", "Bearer $supabaseKey")
                .header("Prefer", "return=minimal")
                .patch(bodyJson.toString().toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Erro ao atualizar questão no Supabase (HTTP ${resp.code}): ${resp.body?.string()}")
                }
            }
        }

        override suspend fun insertAll(questions: List<QuestionEntity>): List<Long> = questions.map { it.id }
        override suspend fun deleteByDiscipline(disciplineId: Long) {}
        override suspend fun deleteByTopic(topicId: Long) {}
        override suspend fun updateAnswer(questionId: Long, chosenOption: Int, isCorrect: Boolean, answeredAt: Long) {}
        override fun getAllQuestions(): Flow<List<QuestionEntity>> = flowOf(emptyList())
        override suspend fun getById(questionId: Long): QuestionEntity? = null
        override suspend fun resetAnswersForDiscipline(disciplineId: Long) {}
        override fun getByTopic(topicId: Long): Flow<List<QuestionEntity>> = flowOf(emptyList())
        override suspend fun countByTopic(topicId: Long): Int = 0
        override suspend fun getUnsyncedQuestions(): List<QuestionEntity> = emptyList()
        override suspend fun markQuestionsAsSynced(ids: List<Long>) {}
    }

    inner class InMemoryAttemptDao : MockExamAttemptDao {
        override suspend fun insertAttempt(attempt: MockExamAttemptEntity): Long = 1L
        override fun getAttemptsByDiscipline(disciplineId: Long): Flow<List<MockExamAttemptEntity>> = flowOf(emptyList())
        override fun getAttemptsByContest(contestId: Long): Flow<List<MockExamAttemptEntity>> = flowOf(emptyList())
        override fun getAllAttempts(): Flow<List<MockExamAttemptEntity>> = flowOf(emptyList())
    }

    /**
     * Garante que o banco relacional PostgreSQL no Supabase possui um Concurso com 4 Disciplinas e 40 Questões reais
     * prontos para o benchmark comparativo. Retorna o `contestId` real utilizado no Supabase.
     */
    private fun getMaxIdInTable(baseUrl: String, supabaseKey: String, tableName: String): Long {
        val req = Request.Builder()
            .url("$baseUrl/rest/v1/$tableName?select=id&order=id.desc&limit=1")
            .header("apikey", supabaseKey)
            .header("Authorization", "Bearer $supabaseKey")
            .get()
            .build()
        return httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use 0L
            val arr = JsonParser.parseString(resp.body?.string() ?: "[]").asJsonArray
            if (arr.isEmpty) 0L else arr[0].asJsonObject.get("id").asLong
        }
    }

    private fun ensureRealDatasetInSupabase(supabaseUrl: String, supabaseKey: String): Long {
        val baseUrl = supabaseUrl.trimEnd('/')

        val getContestsReq = Request.Builder()
            .url("$baseUrl/rest/v1/contests?select=*&order=id.asc")
            .header("apikey", supabaseKey)
            .header("Authorization", "Bearer $supabaseKey")
            .get()
            .build()

        val existingContests = httpClient.newCall(getContestsReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Falha ao conectar ao Supabase em $baseUrl (HTTP ${resp.code}): ${resp.body?.string()}")
            }
            JsonParser.parseString(resp.body?.string() ?: "[]").asJsonArray
        }

        // Verifica se já existe um concurso no Supabase com questões
        var targetContestId: Long? = null
        var targetDisciplineIds: List<Long> = emptyList()
        var existingQuestionCount = 0

        for (elem in existingContests) {
            val cObj = elem.asJsonObject
            val cId = cObj.get("id").asLong
            val discReq = Request.Builder()
                .url("$baseUrl/rest/v1/disciplines?contest_id=eq.$cId&is_active=eq.true&select=id")
                .header("apikey", supabaseKey)
                .header("Authorization", "Bearer $supabaseKey")
                .get()
                .build()
            val discIds = httpClient.newCall(discReq).execute().use { r ->
                JsonParser.parseString(r.body?.string() ?: "[]").asJsonArray.map { it.asJsonObject.get("id").asLong }
            }
            if (discIds.isNotEmpty()) {
                val qReq = Request.Builder()
                    .url("$baseUrl/rest/v1/questions?discipline_id=in.(${discIds.joinToString(",")})&select=id")
                    .header("apikey", supabaseKey)
                    .header("Authorization", "Bearer $supabaseKey")
                    .get()
                    .build()
                val qCount = httpClient.newCall(qReq).execute().use { r ->
                    JsonParser.parseString(r.body?.string() ?: "[]").asJsonArray.size()
                }
                if (qCount >= 40 && discIds.size >= 4) {
                    return cId
                }
                if (targetContestId == null || qCount > existingQuestionCount) {
                    targetContestId = cId
                    targetDisciplineIds = discIds
                    existingQuestionCount = qCount
                }
            } else if (targetContestId == null) {
                targetContestId = cId
            }
        }

        val benchmarkUserId = ensureBenchmarkUserInSupabase(baseUrl, supabaseKey)

        // Se não existe nenhum concurso ainda, cria um novo com ID explícito (maxId + 1)
        val contestId = if (targetContestId != null) {
            targetContestId
        } else {
            val nextContestId = getMaxIdInTable(baseUrl, supabaseKey, "contests") + 1L
            val contestPayload = JsonObject().apply {
                addProperty("id", nextContestId)
                addProperty("user_id", benchmarkUserId)
                addProperty("title", "Concurso Público Nacional Unificado (CNU) — Simulado Oficial N2")
                addProperty("description", "Auditor Fiscal e Analista de Sistemas — Simulado Completo")
                addProperty("organizer_name", "FGV / Cesgranrio")
                addProperty("question_type", "Múltipla Escolha")
                addProperty("ai_difficulty", "Difícil")
                addProperty("ai_rigor", "Rigoroso")
                addProperty("ai_tone", "Acadêmico")
                addProperty("is_active", true)
            }
            val insertContestReq = Request.Builder()
                .url("$baseUrl/rest/v1/contests")
                .header("apikey", supabaseKey)
                .header("Authorization", "Bearer $supabaseKey")
                .header("Prefer", "return=representation")
                .post(contestPayload.toString().toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(insertContestReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Erro ao inserir concurso real no Supabase (HTTP ${resp.code}): ${resp.body?.string()}")
                }
                val arr = JsonParser.parseString(resp.body?.string() ?: "[]").asJsonArray
                arr[0].asJsonObject.get("id").asLong
            }
        }

        val disciplineNames = listOf(
            "Direito Constitucional",
            "Direito Administrativo",
            "Direito Tributário e Financeiro",
            "Engenharia de Dados e Bancos NoSQL"
        )

        val activeDisciplineIds = if (targetDisciplineIds.size >= 4) {
            targetDisciplineIds
        } else {
            val missingCount = 4 - targetDisciplineIds.size
            var nextDiscId = getMaxIdInTable(baseUrl, supabaseKey, "disciplines") + 1L
            val disciplinesArray = JsonArray()
            for (i in 0 until missingCount) {
                val dName = disciplineNames[(targetDisciplineIds.size + i) % disciplineNames.size]
                disciplinesArray.add(
                    JsonObject().apply {
                        addProperty("id", nextDiscId++)
                        addProperty("contest_id", contestId)
                        addProperty("name", dName)
                        addProperty("weight", 2.0)
                        addProperty("total_topics", 10)
                        addProperty("is_active", true)
                    }
                )
            }

            val insertDiscReq = Request.Builder()
                .url("$baseUrl/rest/v1/disciplines")
                .header("apikey", supabaseKey)
                .header("Authorization", "Bearer $supabaseKey")
                .header("Prefer", "return=representation")
                .post(disciplinesArray.toString().toRequestBody(jsonMediaType))
                .build()

            val newlyInsertedIds = httpClient.newCall(insertDiscReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Erro ao inserir disciplinas reais no Supabase (HTTP ${resp.code}): ${resp.body?.string()}")
                }
                val arr = JsonParser.parseString(resp.body?.string() ?: "[]").asJsonArray
                arr.map { it.asJsonObject.get("id").asLong }
            }
            targetDisciplineIds + newlyInsertedIds
        }

        val neededQuestions = (40 - existingQuestionCount).coerceAtLeast(0)
        if (neededQuestions > 0) {
            var nextQuestionId = getMaxIdInTable(baseUrl, supabaseKey, "questions") + 1L
            val questionsArray = JsonArray()
            for (idx in 0 until neededQuestions) {
                val qNum = existingQuestionCount + idx + 1
                val dId = activeDisciplineIds[idx % activeDisciplineIds.size]
                val dName = disciplineNames[idx % disciplineNames.size]
                val opts = listOf(
                    "A) A modelagem orientada a agregados elimina junções relacionais (JOINs) em leituras intensivas de simulados.",
                    "B) O padrão Cache-Aside verifica primeiro a camada NoSQL e, em caso de Miss, consulta o SQL e popula o documento.",
                    "C) A invalidação na escrita (DEL) garante que edições no banco relacional removam imediatamente o cache obsoleto.",
                    "D) O TTL de 24 horas atua como rede de segurança adicional contra dados órfãos na coleção mock_exams.",
                    "E) A durabilidade do Cloud Firestore preserva os documentos JSON mesmo após o reinício imediato da aplicação."
                )
                questionsArray.add(
                    JsonObject().apply {
                        addProperty("id", nextQuestionId++)
                        addProperty("discipline_id", dId)
                        addProperty(
                            "statement",
                            "[Questão #$qNum - $dName] No contexto de arquiteturas híbridas Poliglota (PostgreSQL/Supabase + Firebase Cloud Firestore), assinale a alternativa correta referente ao item $qNum:"
                        )
                        addProperty("options_json", gson.toJson(opts))
                        addProperty("correct_index", qNum % 5)
                        addProperty(
                            "explanation",
                            "Comentário oficial da banca para a questão #$qNum ($dName): o documento autocontido reduz a latência de leitura repetida."
                        )
                        addProperty("source", "AI_GENERATED")
                        addProperty("tokens_spent", 250)
                        addProperty("created_at", System.currentTimeMillis() - (40 - qNum) * 1000L)
                    }
                )
            }

            val insertQuestionsReq = Request.Builder()
                .url("$baseUrl/rest/v1/questions")
                .header("apikey", supabaseKey)
                .header("Authorization", "Bearer $supabaseKey")
                .header("Prefer", "return=representation")
                .post(questionsArray.toString().toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(insertQuestionsReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Erro ao inserir questões reais no Supabase (HTTP ${resp.code}): ${resp.body?.string()}")
                }
            }
        }

        return contestId
    }

    private fun ensureBenchmarkUserInSupabase(baseUrl: String, supabaseKey: String): String {
        val getUsersReq = Request.Builder()
            .url("$baseUrl/rest/v1/users?select=id&limit=1")
            .header("apikey", supabaseKey)
            .header("Authorization", "Bearer $supabaseKey")
            .get()
            .build()

        httpClient.newCall(getUsersReq).execute().use { resp ->
            if (resp.isSuccessful) {
                val arr = JsonParser.parseString(resp.body?.string() ?: "[]").asJsonArray
                if (!arr.isEmpty) {
                    return arr[0].asJsonObject.get("id").asString
                }
            }
        }

        val newUserId = "usr_n2_benchmark"
        val userPayload = JsonObject().apply {
            addProperty("id", newUserId)
            addProperty("name", "Grupo RememberFlash N2")
            addProperty("email", "n2@rememberflash.app")
            addProperty("cpf", "00000000000")
            addProperty("password_hash", "benchmark_hash_n2")
        }
        val postUserReq = Request.Builder()
            .url("$baseUrl/rest/v1/users")
            .header("apikey", supabaseKey)
            .header("Authorization", "Bearer $supabaseKey")
            .header("Prefer", "return=minimal")
            .post(userPayload.toString().toRequestBody(jsonMediaType))
            .build()

        httpClient.newCall(postUserReq).execute().close()
        return newUserId
    }

    @Test
    fun executarBenchmarkComparativoN2_DadosReaisSupabaseEFirebase(): Unit = runBlocking {
        val props = loadLocalProperties()
        val supabaseUrl = (props.getProperty("SUPABASE_URL") ?: "").trim()
        val supabaseAnonKey = (props.getProperty("SUPABASE_ANON_KEY") ?: "").trim()
        val supabaseServiceKey = (props.getProperty("SUPABASE_SERVICE_ROLE_KEY") ?: "").trim()
        val supabaseKey = supabaseServiceKey.ifBlank { supabaseAnonKey }

        val firebaseProjectId = (props.getProperty("FIREBASE_PROJECT_ID") ?: "").trim()
        val firebaseApiKey = (props.getProperty("FIREBASE_API_KEY") ?: "").trim()

        require(supabaseUrl.isNotBlank() && supabaseUrl.startsWith("http")) {
            "SUPABASE_URL não configurado em local.properties! Adicione SUPABASE_URL=https://SEU_PROJETO.supabase.co em local.properties."
        }
        require(supabaseKey.isNotBlank()) {
            "Chave do Supabase não encontrada em local.properties!"
        }
        require(firebaseProjectId.isNotBlank() && firebaseApiKey.isNotBlank()) {
            "Credenciais do Firebase (FIREBASE_PROJECT_ID / FIREBASE_API_KEY) não encontradas em local.properties!"
        }

        val tempDir = Files.createTempDirectory("rf_cloud_benchmark").toFile()

        // 1. Garante que os dados reais existem no Supabase PostgreSQL e obtém o contestId real
        val realContestId = ensureRealDatasetInSupabase(supabaseUrl, supabaseKey)
        val docId = MockExamDocument.buildDocumentId(realContestId)

        // Instancia os DAOs que batem diretamente no Supabase via HTTPS (PostgREST)
        val supabaseContestDao = SupabaseRemoteContestDao(supabaseUrl, supabaseKey)
        val supabaseDisciplineDao = SupabaseRemoteDisciplineDao(supabaseUrl, supabaseKey)
        val supabaseQuestionDao = SupabaseRemoteQuestionDao(supabaseUrl, supabaseKey)
        val attemptDao = InMemoryAttemptDao()

        // Instancia o DataSource conectado ao Firebase Cloud Firestore REAL (sem cache L1 para medir a nuvem pura)
        val remoteOnlyFirestoreDataSource = FirestoreMockExamDataSource(
            storageDirectory = tempDir,
            firebaseProjectId = firebaseProjectId,
            firebaseApiKey = firebaseApiKey,
            enableLocalL1Cache = false
        )

        // Instancia também o DataSource com L1/L2 habilitado (comportamento padrão do SDK Firebase Firestore no Android)
        val hybridFirestoreDataSource = FirestoreMockExamDataSource(
            storageDirectory = tempDir,
            firebaseProjectId = firebaseProjectId,
            firebaseApiKey = firebaseApiKey,
            enableLocalL1Cache = true
        )

        val remoteCacheService = MockExamCacheService(
            contestDao = supabaseContestDao,
            disciplineDao = supabaseDisciplineDao,
            questionDao = supabaseQuestionDao,
            mockExamAttemptDao = attemptDao,
            noSqlDataSource = remoteOnlyFirestoreDataSource
        )

        val hybridCacheService = MockExamCacheService(
            contestDao = supabaseContestDao,
            disciplineDao = supabaseDisciplineDao,
            questionDao = supabaseQuestionDao,
            mockExamAttemptDao = attemptDao,
            noSqlDataSource = hybridFirestoreDataSource
        )

        // Limpa o documento no Firebase Cloud Firestore para garantir Cold Start real (Cache MISS genuíno)
        remoteOnlyFirestoreDataSource.deleteDocument(docId)
        hybridFirestoreDataSource.clearAll()

        val nIterations = 15

        // =========================================================================
        // 1. MEDIÇÃO DO BASELINE (SQL RELACIONAL PURO NO SUPABASE POSTGRESQL CLOUD)
        // =========================================================================
        val baselineLatencies = mutableListOf<Double>()
        var questionsLoadedCount = 0

        for (i in 1..nIterations) {
            val baseRes = (remoteCacheService.getMockExamRelationalBaseline(realContestId) as Result.Success).data
            assertEquals(CacheLookupStatus.BASELINE_SQL, baseRes.status)
            baselineLatencies.add(baseRes.elapsedMs)
            questionsLoadedCount = baseRes.document.totalQuestions
        }

        // =========================================================================
        // 2. MEDIÇÃO DA 1ª LEITURA NOSQL/CACHE (CACHE MISS REAL: SUPABASE -> FIREBASE)
        // =========================================================================
        val missResult = (remoteCacheService.getMockExamCacheAside(realContestId) as Result.Success).data
        assertEquals(CacheLookupStatus.MISS, missResult.status)
        val firstMissLatencyMs = missResult.elapsedMs

        // Mede mais 4 amostras de Cache Miss reais (deletando no Firebase + recriando a partir do Supabase)
        val missLatencies = mutableListOf<Double>(firstMissLatencyMs)
        for (i in 2..5) {
            remoteOnlyFirestoreDataSource.deleteDocument(docId)
            val r = (remoteCacheService.getMockExamCacheAside(realContestId) as Result.Success).data
            assertEquals(CacheLookupStatus.MISS, r.status)
            missLatencies.add(r.elapsedMs)
        }

        // =========================================================================
        // 3. MEDIÇÃO DAS LEITURAS SUBSEQUENTES (CACHE HIT DIRETO NO FIREBASE CLOUD FIRESTORE)
        // =========================================================================
        // 3A. Cache HIT Remoto (1 requisição GET direta ao documento único no Firebase Cloud Firestore)
        val remoteHitLatencies = mutableListOf<Double>()
        for (i in 1..nIterations) {
            val hitRes = (remoteCacheService.getMockExamCacheAside(realContestId) as Result.Success).data
            assertEquals(CacheLookupStatus.HIT, hitRes.status)
            assertEquals(questionsLoadedCount, hitRes.document.questions.size)
            remoteHitLatencies.add(hitRes.elapsedMs)
        }

        // 3B. Cache HIT Local L1/L2 do Firestore SDK (após o 1º carregamento para a memória/disco do app)
        hybridFirestoreDataSource.putDocument(missResult.document)
        val localSdkHitLatencies = mutableListOf<Double>()
        for (i in 1..nIterations) {
            val hitRes = (hybridCacheService.getMockExamCacheAside(realContestId) as Result.Success).data
            assertEquals(CacheLookupStatus.HIT, hitRes.status)
            localSdkHitLatencies.add(hitRes.elapsedMs)
        }

        // =========================================================================
        // 4. DEMONSTRAÇÃO AO VIVO DE INVALIDAÇÃO NA ESCRITA (SUPABASE + FIREBASE)
        // =========================================================================
        val firstQuestion = missResult.document.questions.first()
        val originalStatement = firstQuestion.statement
        val updatedStatement = "$originalStatement [ATUALIZADA AO VIVO NA NUVEM v2]"

        // A) Atualiza a questão na Fonte da Verdade (Supabase PostgreSQL)
        supabaseQuestionDao.updateQuestionStatementInSupabase(firstQuestion.questionId, updatedStatement)

        // B) Dispara a Invalidação na Escrita (DELETE real no Firebase Cloud Firestore)
        val tInv0 = System.nanoTime()
        val invalidated = remoteOnlyFirestoreDataSource.invalidateContestExam(realContestId)
        val invalidationLatencyMs = (System.nanoTime() - tInv0) / 1_000_000.0
        assertTrue(invalidated)

        // C) Próxima leitura no Firebase dá CACHE MISS e reconstrói o documento v2 no Firebase Cloud Firestore
        val rebuildResult = (remoteCacheService.getMockExamCacheAside(realContestId) as Result.Success).data
        assertEquals(CacheLookupStatus.MISS, rebuildResult.status)
        val rebuiltFirstQuestion = rebuildResult.document.questions.first { it.questionId == firstQuestion.questionId }
        assertEquals(updatedStatement, rebuiltFirstQuestion.statement)

        // D) Leitura seguinte no Firebase dá CACHE HIT já com o dado atualizado (v2)!
        val hitAfterRebuild = (remoteCacheService.getMockExamCacheAside(realContestId) as Result.Success).data
        assertEquals(CacheLookupStatus.HIT, hitAfterRebuild.status)
        assertEquals(
            updatedStatement,
            hitAfterRebuild.document.questions.first { it.questionId == firstQuestion.questionId }.statement
        )

        // Restaura o enunciado original no Supabase e atualiza o documento final no Firebase para deixar o banco limpo
        supabaseQuestionDao.updateQuestionStatementInSupabase(firstQuestion.questionId, originalStatement)
        remoteOnlyFirestoreDataSource.invalidateContestExam(realContestId)
        val finalCleanRebuild = (remoteCacheService.getMockExamCacheAside(realContestId) as Result.Success).data
        assertEquals(CacheLookupStatus.MISS, finalCleanRebuild.status)

        // =========================================================================
        // 5. CÁLCULO ESTATÍSTICO DOS GANHOS REAIS MEDIDOS
        // =========================================================================
        val baseMean: Double = baselineLatencies.average()
        val baseMedian: Double = percentile(baselineLatencies, 50.0)
        val baseP95: Double = percentile(baselineLatencies, 95.0)
        val baseStd: Double = stdDev(baselineLatencies)

        val missMean: Double = missLatencies.average()
        val missMedian: Double = percentile(missLatencies, 50.0)
        val missP95: Double = percentile(missLatencies, 95.0)

        val remoteHitMean: Double = remoteHitLatencies.average()
        val remoteHitMedian: Double = percentile(remoteHitLatencies, 50.0)
        val remoteHitP95: Double = percentile(remoteHitLatencies, 95.0)
        val remoteHitStd: Double = stdDev(remoteHitLatencies)

        val localHitMean: Double = localSdkHitLatencies.average()
        val localHitMedian: Double = percentile(localSdkHitLatencies, 50.0)
        val localHitP95: Double = percentile(localSdkHitLatencies, 95.0)

        val speedupCloudMean: Double = baseMean / remoteHitMean
        val reductionCloudPct: Double = ((baseMean - remoteHitMean) / baseMean) * 100.0
        val speedupLocalSdkMean: Double = baseMean / localHitMean
        val missOverheadPct: Double = ((missMean - baseMean) / baseMean) * 100.0

        val line = "=".repeat(100)
        val subLine = "-".repeat(100)

        println("\n$line")
        println("  RELATÓRIO DE MEDIÇÃO E BENCHMARK REAL NA NUVEM — PROJETO N2 SEMANA 4 (SUPABASE vs. FIREBASE)")
        println("  Grupo: Gabriel de Oliveira Silva | João Vitor Marques Braga | Maria Eduarda Lira Lemes Braz")
        println(line)
        println("  CONDIÇÕES CONTROLADAS DO AMBIENTE (100% DADOS REAIS EM NUVEM):")
        println("  • Fonte da Verdade (SQL):   Supabase PostgreSQL ($supabaseUrl)")
        println("  • Camada NoSQL / Cache:     Firebase Cloud Firestore (Projeto: $firebaseProjectId)")
        println("  • Coleção / Documento:      mock_exams / $docId")
        println("  • Volume de Dados Testado:  1 Simulado Completo ($questionsLoadedCount questões reais, 4 disciplinas, 200 alternativas)")
        println("  • Amostragem (N):           N = $nIterations execuções reais na nuvem por cenário")
        println(subLine)
        println(
            String.format(
                Locale.US,
                "  %-42s | %12s | %12s | %12s | %12s",
                "OPERAÇÃO MEDIDA (NUVEM REAL)",
                "MÉDIA (ms)",
                "MEDIANA(ms)",
                "P95 (ms)",
                "GANHO"
            )
        )
        println(subLine)
        println(
            String.format(
                Locale.US,
                "  %-42s | %10.2f ms | %10.2f ms | %10.2f ms | %12s",
                "1. Baseline SQL (Supabase: 6 queries)",
                baseMean,
                baseMedian,
                baseP95,
                "1.00x (Ref)"
            )
        )
        println(
            String.format(
                Locale.US,
                "  %-42s | %10.2f ms | %10.2f ms | %10.2f ms | %+10.1f%%",
                "2. Cache MISS (Supabase -> Grava Firebase)",
                missMean,
                missMedian,
                missP95,
                missOverheadPct
            )
        )
        println(
            String.format(
                Locale.US,
                "  %-42s | %10.2f ms | %10.2f ms | %10.2f ms | %10.2fx",
                "3. Cache HIT Cloud (Firebase 1 Documento)",
                remoteHitMean,
                remoteHitMedian,
                remoteHitP95,
                speedupCloudMean
            )
        )
        println(
            String.format(
                Locale.US,
                "  %-42s | %10.4f ms | %10.4f ms | %10.4f ms | %10.1fx",
                "4. Cache HIT Local (Firestore L1 Cache)",
                localHitMean,
                localHitMedian,
                localHitP95,
                speedupLocalSdkMean
            )
        )
        println(subLine)
        println("  CÁLCULO FORMAL DO GANHO (Tempo Antes ÷ Tempo Depois):")
        println(
            String.format(
                Locale.US,
                "  • Ganho Nuvem Pura (Supabase 6 Queries vs. Firebase 1 Doc): %.2f ms ÷ %.2f ms = %.2fx mais rápido (-%.1f%% latência)",
                baseMean,
                remoteHitMean,
                speedupCloudMean,
                reductionCloudPct
            )
        )
        println(
            String.format(
                Locale.US,
                "  • Ganho com Cache Local SDK do Firestore (L1/L2):          %.2f ms ÷ %.4f ms = %.1fx mais rápido",
                baseMean,
                localHitMean,
                speedupLocalSdkMean
            )
        )
        println(
            String.format(
                Locale.US,
                "  • Desvio Padrão (Estabilidade na Nuvem):                   Baseline = %.2f ms | Firebase Hit = %.2f ms",
                baseStd,
                remoteHitStd
            )
        )
        println(subLine)
        println("  VALIDAÇÃO AO VIVO DE INVALIDAÇÃO NA ESCRITA (SUPABASE + FIREBASE FIRESTORE):")
        println("  1. UPDATE na questão #${firstQuestion.questionId} no Supabase PostgreSQL")
        println(
            String.format(
                Locale.US,
                "  2. DELETE imediato no Firebase Firestore (DEL mock_exams/%s) em %.2f ms",
                docId,
                invalidationLatencyMs
            )
        )
        println(
            String.format(
                Locale.US,
                "  3. Leitura pós-invalidação -> Status: %s (%.2f ms) | Reconstrói documento atualizado no Firebase",
                rebuildResult.status.name,
                rebuildResult.elapsedMs
            )
        )
        println(
            String.format(
                Locale.US,
                "  4. Leitura subsequente     -> Status: %s (%.2f ms) | Dado atualizado confirmado sem defasagem!",
                hitAfterRebuild.status.name,
                hitAfterRebuild.elapsedMs
            )
        )
        println("$line\n")

        tempDir.deleteRecursively()
    }

    private fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val index = ((p / 100.0) * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    private fun stdDev(values: List<Double>): Double {
        if (values.size <= 1) return 0.0
        val mean = values.average()
        val sumSq = values.sumOf { (it - mean) * (it - mean) }
        return sqrt(sumSq / (values.size - 1))
    }
}
