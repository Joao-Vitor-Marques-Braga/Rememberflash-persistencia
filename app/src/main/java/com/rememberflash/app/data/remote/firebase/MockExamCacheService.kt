package com.rememberflash.app.data.remote.firebase

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rememberflash.app.data.local.database.Converters
import com.rememberflash.app.data.local.database.dao.ContestDao
import com.rememberflash.app.data.local.database.dao.DisciplineDao
import com.rememberflash.app.data.local.database.dao.MockExamAttemptDao
import com.rememberflash.app.data.local.database.dao.QuestionDao
import com.rememberflash.app.domain.common.Result
import com.rememberflash.app.domain.model.CacheLookupStatus
import com.rememberflash.app.domain.model.MockExamAttemptsSummary
import com.rememberflash.app.domain.model.MockExamDocument
import com.rememberflash.app.domain.model.MockExamLookupResult
import com.rememberflash.app.domain.model.MockExamQuestionItem
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serviço de orquestração do padrão **Cache-Aside** sobre a camada NoSQL baseada em Documentos
 * (Firebase Cloud Firestore) para o domínio isolado de Provas e Questões de Simulado.
 *
 * Implementa rigorosamente:
 * 1. **Consulta Original Isolada (Baseline Relacional / SQL)**: executa as consultas encadeadas
 *    (`contests` -> `disciplines` -> `questions` + `mock_exam_attempts`) e o parsing individual de JSON.
 * 2. **Consulta Otimizada (Cache-Aside NoSQL / Firestore)**:
 *    - **CACHE HIT**: retorna o `MockExamDocument` desnormalizado em $O(1)$ direto por ID (`contest_{id}_exam_full`).
 *    - **CACHE MISS**: consulta a Fonte da Verdade (SQL), constrói o documento autocontido com TTL de 24h,
 *      persiste no Firestore/Cache e retorna à aplicação.
 * 3. **Invalidação na Escrita (Write Invalidation)**: ao inserir, atualizar ou excluir questões/gabaritos
 *    na Fonte da Verdade, executa `DEL mock_exam:contest_{id}_exam_full` garantindo que o sistema nunca
 *    exiba dado obsoleto (stale data).
 */
@Singleton
class MockExamCacheService @Inject constructor(
    private val contestDao: ContestDao,
    private val disciplineDao: DisciplineDao,
    private val questionDao: QuestionDao,
    private val mockExamAttemptDao: MockExamAttemptDao,
    private val noSqlDataSource: MockExamNoSqlDataSource
) {
    private val gson = Gson()
    private val converters = Converters()
    private val documentVersionCounter = AtomicInteger(1)

    /**
     * Executa o fluxo **Cache-Aside** completo (verifica HIT -> se MISS reconstrói da Fonte da Verdade e popula o NoSQL).
     */
    suspend fun getMockExamCacheAside(
        contestId: Long,
        nowMillis: Long = System.currentTimeMillis()
    ): Result<MockExamLookupResult> {
        val startNano = System.nanoTime()
        val docId = MockExamDocument.buildDocumentId(contestId)

        // 1. Verifica na camada rápida NoSQL (Firebase Firestore Document Store)
        val cachedDoc = noSqlDataSource.getDocument(docId, nowMillis)
        if (cachedDoc != null) {
            // Projeta cópia isolada dos itens de questão para a camada de apresentação (ViewModel)
            val isolatedQuestions = ArrayList<MockExamQuestionItem>(cachedDoc.questions.size)
            for (q in cachedDoc.questions) {
                isolatedQuestions.add(q.copy(options = ArrayList(q.options)))
            }
            val projectedDoc = cachedDoc.copy(questions = isolatedQuestions)

            val elapsedMs = (System.nanoTime() - startNano) / 1_000_000.0
            noSqlDataSource.recordOperation(CacheLookupStatus.HIT, elapsedMs)
            return Result.success(
                MockExamLookupResult(
                    document = projectedDoc,
                    status = CacheLookupStatus.HIT,
                    elapsedMs = elapsedMs
                )
            )
        }

        // 2. CACHE MISS: Busca na Fonte da Verdade (Relacional), monta o documento único e grava no NoSQL
        return try {
            val rebuiltDoc = reconstructFromRelationalSource(
                contestId = contestId,
                nowMillis = nowMillis,
                assignNewVersion = true
            ) ?: return Result.error("Concurso $contestId não encontrado na Fonte da Verdade (SQL).")

            noSqlDataSource.putDocument(rebuiltDoc)

            val elapsedMs = (System.nanoTime() - startNano) / 1_000_000.0
            noSqlDataSource.recordOperation(CacheLookupStatus.MISS, elapsedMs)

            Result.success(
                MockExamLookupResult(
                    document = rebuiltDoc,
                    status = CacheLookupStatus.MISS,
                    elapsedMs = elapsedMs
                )
            )
        } catch (e: Exception) {
            Result.error("Falha no Cache MISS ao reconstruir simulado: ${e.localizedMessage}", e)
        }
    }

    /**
     * Executa exclusivamente a consulta relacional original (Baseline SQL sem cache)
     * para fins de comparação direta de desempenho sob as mesmas condições.
     */
    suspend fun getMockExamRelationalBaseline(
        contestId: Long,
        nowMillis: Long = System.currentTimeMillis()
    ): Result<MockExamLookupResult> {
        val startNano = System.nanoTime()
        return try {
            val doc = reconstructFromRelationalSource(
                contestId = contestId,
                nowMillis = nowMillis,
                assignNewVersion = false
            ) ?: return Result.error("Concurso $contestId não encontrado no banco relacional.")

            val elapsedMs = (System.nanoTime() - startNano) / 1_000_000.0
            noSqlDataSource.recordOperation(CacheLookupStatus.BASELINE_SQL, elapsedMs)

            Result.success(
                MockExamLookupResult(
                    document = doc,
                    status = CacheLookupStatus.BASELINE_SQL,
                    elapsedMs = elapsedMs
                )
            )
        } catch (e: Exception) {
            Result.error("Erro na consulta Baseline SQL: ${e.localizedMessage}", e)
        }
    }

    /**
     * Reconstrói o documento agregando os dados das 4 tabelas relacionais:
     * `contests` -> `disciplines` -> `questions` + `mock_exam_attempts`.
     */
    private suspend fun reconstructFromRelationalSource(
        contestId: Long,
        nowMillis: Long,
        assignNewVersion: Boolean
    ): MockExamDocument? {
        // Passo 1: Reset relacional de respostas anteriores (subquery com IN)
        questionDao.resetAnswersForContest(contestId)

        // Passo 2: Busca metadados do concurso pai
        val contest = contestDao.getById(contestId) ?: return null

        // Passo 3: Busca todas as disciplinas ativas do concurso
        val disciplines = disciplineDao.getByContest(contestId).first().filter { it.isActive }

        // Passo 4: Busca as questões de cada disciplina (N+1 queries) e faz parsing do JSON de alternativas
        val questionItems = mutableListOf<MockExamQuestionItem>()
        for (disc in disciplines) {
            val entities = questionDao.getByDiscipline(disc.id).first()
            for (qEntity in entities) {
                val parsedOptions = converters.toStringList(qEntity.optionsJson)
                questionItems.add(
                    MockExamQuestionItem(
                        questionId = qEntity.id,
                        disciplineId = disc.id,
                        disciplineName = disc.name,
                        topicId = qEntity.topicId,
                        statement = qEntity.statement,
                        options = parsedOptions,
                        correctIndex = qEntity.correctIndex,
                        explanation = qEntity.explanation,
                        source = qEntity.source,
                        createdAt = qEntity.createdAt
                    )
                )
            }
        }

        // Passo 5: Busca histórico de tentativas anteriores e decodifica mapas JSON
        val attempts = mockExamAttemptDao.getAttemptsByContest(contestId).first()
        val latestAttempt = attempts.firstOrNull()
        val parsedAnswers: Map<String, Int> = if (latestAttempt != null && latestAttempt.answersJson.isNotBlank()) {
            try {
                val type = object : TypeToken<Map<String, Int>>() {}.type
                gson.fromJson(latestAttempt.answersJson, type) ?: emptyMap()
            } catch (_: Exception) {
                emptyMap()
            }
        } else {
            emptyMap()
        }

        val parsedTimes: Map<String, Long> = if (latestAttempt?.timesJson != null && latestAttempt.timesJson.isNotBlank()) {
            try {
                val type = object : TypeToken<Map<String, Long>>() {}.type
                gson.fromJson(latestAttempt.timesJson, type) ?: emptyMap()
            } catch (_: Exception) {
                emptyMap()
            }
        } else {
            emptyMap()
        }

        val summary = MockExamAttemptsSummary(
            totalAttempts = attempts.size,
            lastScore = latestAttempt?.score,
            lastAttemptAt = latestAttempt?.createdAt,
            lastAnswers = parsedAnswers,
            lastTimes = parsedTimes
        )

        val version = if (assignNewVersion) documentVersionCounter.getAndIncrement() else documentVersionCounter.get()

        return MockExamDocument(
            id = MockExamDocument.buildDocumentId(contestId),
            contestId = contestId,
            contestTitle = contest.title,
            organizerName = contest.organizerName,
            questionType = contest.questionType,
            aiDifficulty = contest.aiDifficulty,
            totalQuestions = questionItems.size,
            timeLimitMinutes = 240,
            createdAt = contest.createdAt,
            updatedAt = nowMillis,
            ttlExpiresAt = nowMillis + MockExamDocument.DEFAULT_TTL_MILLIS,
            version = version,
            questions = questionItems.sortedBy { it.createdAt },
            attemptsSummary = summary
        )
    }

    /**
     * Invalidação na Escrita (Write Invalidation) por ID de concurso:
     * Executa `DEL mock_exam:contest_{contestId}_exam_full`.
     */
    fun invalidateContestCache(contestId: Long): Boolean {
        return noSqlDataSource.invalidateContestExam(contestId)
    }

    /**
     * Invalidação na Escrita (Write Invalidation) a partir de uma disciplina modificada:
     * Descobre o `contestId` pai na Fonte da Verdade e remove o documento correspondente do cache.
     */
    suspend fun invalidateCacheForDiscipline(disciplineId: Long) {
        val discipline = disciplineDao.getById(disciplineId)
        if (discipline != null) {
            noSqlDataSource.invalidateContestExam(discipline.contestId)
        } else {
            noSqlDataSource.clearAll()
        }
    }

    fun resetVersionCounter(startAt: Int = 1) {
        documentVersionCounter.set(startAt)
    }

    fun getCacheMetrics(): CacheMetricsSnapshot = noSqlDataSource.getMetrics()
}
