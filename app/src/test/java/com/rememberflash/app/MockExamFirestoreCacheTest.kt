package com.rememberflash.app

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
import com.rememberflash.app.data.repository.QuestionRepositoryImpl
import com.rememberflash.app.domain.common.Result
import com.rememberflash.app.domain.model.CacheLookupStatus
import com.rememberflash.app.domain.model.MockExamDocument
import com.rememberflash.app.domain.model.Question
import com.rememberflash.app.domain.model.QuestionSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MockExamFirestoreCacheTest {

    private lateinit var tempDir: File
    private lateinit var firestoreDataSource: FirestoreMockExamDataSource
    private lateinit var fakeContestDao: InMemoryContestDao
    private lateinit var fakeDisciplineDao: InMemoryDisciplineDao
    private lateinit var fakeQuestionDao: InMemoryQuestionDao
    private lateinit var fakeAttemptDao: InMemoryAttemptDao
    private lateinit var cacheService: MockExamCacheService
    private lateinit var repository: QuestionRepositoryImpl

    class InMemoryContestDao : ContestDao {
        val contests = mutableMapOf<Long, ContestEntity>()
        var queryCount = 0

        override suspend fun insert(contest: ContestEntity): Long {
            contests[contest.id] = contest
            return contest.id
        }

        override suspend fun insertAll(contests: List<ContestEntity>): List<Long> =
            contests.map { insert(it) }

        override suspend fun update(contest: ContestEntity) {
            contests[contest.id] = contest
        }

        override suspend fun softDelete(contestId: Long, updatedAt: Long) {}
        override suspend fun delete(contestId: Long) { contests.remove(contestId) }
        override fun getActiveByUser(userId: String): Flow<List<ContestEntity>> = flowOf(contests.values.toList())
        override fun getArchivedByUser(userId: String): Flow<List<ContestEntity>> = flowOf(emptyList())
        override suspend fun reactivate(contestId: Long, updatedAt: Long) {}
        override fun getAllByUser(userId: String): Flow<List<ContestEntity>> = flowOf(contests.values.toList())
        override suspend fun getById(contestId: Long): ContestEntity? {
            queryCount++
            return contests[contestId]
        }
        override suspend fun getUnsyncedContests(): List<ContestEntity> = emptyList()
        override suspend fun getByIds(ids: List<Long>): List<ContestEntity> = ids.mapNotNull { contests[it] }
        override suspend fun getAllContests(): List<ContestEntity> = contests.values.toList()
        override suspend fun markContestsAsSynced(ids: List<Long>) {}
    }

    class InMemoryDisciplineDao : DisciplineDao {
        val disciplines = mutableMapOf<Long, DisciplineEntity>()
        var queryCount = 0

        override suspend fun insert(discipline: DisciplineEntity): Long {
            disciplines[discipline.id] = discipline
            return discipline.id
        }

        override suspend fun insertAll(disciplines: List<DisciplineEntity>): List<Long> =
            disciplines.map { insert(it) }

        override suspend fun update(discipline: DisciplineEntity) {
            disciplines[discipline.id] = discipline
        }

        override suspend fun delete(disciplineId: Long) { disciplines.remove(disciplineId) }
        override suspend fun deleteByContest(contestId: Long) {}
        override fun getByContest(contestId: Long): Flow<List<DisciplineEntity>> {
            queryCount++
            return flowOf(disciplines.values.filter { it.contestId == contestId })
        }
        override suspend fun getById(disciplineId: Long): DisciplineEntity? = disciplines[disciplineId]
        override suspend fun getUnsyncedDisciplines(): List<DisciplineEntity> = emptyList()
        override suspend fun getByIds(ids: List<Long>): List<DisciplineEntity> = ids.mapNotNull { disciplines[it] }
        override suspend fun getAll(): List<DisciplineEntity> = disciplines.values.toList()
        override suspend fun markDisciplinesAsSynced(ids: List<Long>) {}
        override fun getAllDisciplines(): Flow<List<DisciplineEntity>> = flowOf(disciplines.values.toList())
    }

    class InMemoryQuestionDao : QuestionDao {
        val questions = mutableMapOf<Long, QuestionEntity>()
        var queryCount = 0

        override suspend fun insertAll(questions: List<QuestionEntity>): List<Long> {
            return questions.map { q ->
                val id = if (q.id == 0L) (this.questions.size + 1).toLong() else q.id
                val entity = q.copy(id = id)
                this.questions[id] = entity
                id
            }
        }

        override fun getByDiscipline(disciplineId: Long): Flow<List<QuestionEntity>> {
            queryCount++
            return flowOf(questions.values.filter { it.disciplineId == disciplineId })
        }

        override suspend fun deleteByDiscipline(disciplineId: Long) {
            val toRemove = questions.values.filter { it.disciplineId == disciplineId }.map { it.id }
            toRemove.forEach { questions.remove(it) }
        }

        override suspend fun deleteByTopic(topicId: Long) {}
        override suspend fun updateAnswer(questionId: Long, chosenOption: Int, isCorrect: Boolean, answeredAt: Long) {}
        override fun getAllQuestions(): Flow<List<QuestionEntity>> = flowOf(questions.values.toList())
        override suspend fun getById(questionId: Long): QuestionEntity? = questions[questionId]
        override suspend fun resetAnswersForDiscipline(disciplineId: Long) {}
        override suspend fun resetAnswersForContest(contestId: Long) {
            queryCount++
        }
        override fun getByTopic(topicId: Long): Flow<List<QuestionEntity>> = flowOf(emptyList())
        override suspend fun countByTopic(topicId: Long): Int = 0
        override suspend fun getUnsyncedQuestions(): List<QuestionEntity> = emptyList()
        override suspend fun markQuestionsAsSynced(ids: List<Long>) {}
    }

    class InMemoryAttemptDao : MockExamAttemptDao {
        val attempts = mutableListOf<MockExamAttemptEntity>()
        var queryCount = 0

        override suspend fun insertAttempt(attempt: MockExamAttemptEntity): Long {
            val id = (attempts.size + 1).toLong()
            attempts.add(attempt.copy(id = id))
            return id
        }

        override fun getAttemptsByDiscipline(disciplineId: Long): Flow<List<MockExamAttemptEntity>> =
            flowOf(attempts.filter { it.disciplineId == disciplineId })

        override fun getAttemptsByContest(contestId: Long): Flow<List<MockExamAttemptEntity>> {
            queryCount++
            return flowOf(attempts.filter { it.contestId == contestId })
        }

        override fun getAllAttempts(): Flow<List<MockExamAttemptEntity>> = flowOf(attempts)
    }

    @Before
    fun setUp() = runTest {
        tempDir = Files.createTempDirectory("rf_firestore_test").toFile()
        firestoreDataSource = FirestoreMockExamDataSource(
            storageDirectory = tempDir,
            firebaseProjectId = "",
            firebaseApiKey = "",
            enableLocalL1Cache = true
        )
        fakeContestDao = InMemoryContestDao()
        fakeDisciplineDao = InMemoryDisciplineDao()
        fakeQuestionDao = InMemoryQuestionDao()
        fakeAttemptDao = InMemoryAttemptDao()

        // Popula Fonte da Verdade (Relacional) com Concurso ID 1, 2 disciplinas e 4 questões
        fakeContestDao.insert(
            ContestEntity(
                id = 1L,
                userId = "usr_rf_1",
                title = "Concurso Público Nacional Unificado #1",
                description = "Auditor Fiscal",
                organizerName = "FGV",
                questionType = "Múltipla Escolha",
                aiDifficulty = "Difícil",
                aiRigor = "Rigoroso",
                aiTone = "Formal",
                isActive = true
            )
        )
        fakeDisciplineDao.insert(
            DisciplineEntity(id = 10L, contestId = 1L, name = "Direito Constitucional", weight = 2.0, totalTopics = 5)
        )
        fakeDisciplineDao.insert(
            DisciplineEntity(id = 20L, contestId = 1L, name = "Direito Tributário", weight = 2.0, totalTopics = 5)
        )
        fakeQuestionDao.insertAll(
            listOf(
                QuestionEntity(
                    id = 101L,
                    disciplineId = 10L,
                    statement = "Questão 1 Constitucional",
                    optionsJson = "[\"A) Opção 1\",\"B) Opção 2\"]",
                    correctIndex = 0,
                    explanation = "Art. 5 CF/88"
                ),
                QuestionEntity(
                    id = 102L,
                    disciplineId = 20L,
                    statement = "Questão 2 Tributário",
                    optionsJson = "[\"A) Opção 1\",\"B) Opção 2\"]",
                    correctIndex = 1,
                    explanation = "Art. 150 CF/88"
                )
            )
        )

        cacheService = MockExamCacheService(
            contestDao = fakeContestDao,
            disciplineDao = fakeDisciplineDao,
            questionDao = fakeQuestionDao,
            mockExamAttemptDao = fakeAttemptDao,
            noSqlDataSource = firestoreDataSource
        )
        repository = QuestionRepositoryImpl(fakeQuestionDao, fakeAttemptDao, cacheService)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `cacheAside should return MISS on first read and HIT on subsequent reads without hitting SQL`() = runTest {
        // 1ª Leitura: CACHE MISS (popula o documento contest_1_exam_full no Firestore/Cache)
        val firstRead = repository.getMockExamCacheAside(1L)
        assertTrue(firstRead is Result.Success)
        val missResult = (firstRead as Result.Success).data
        assertEquals(CacheLookupStatus.MISS, missResult.status)
        assertEquals("contest_1_exam_full", missResult.document.id)
        assertEquals("mock_exam:contest_1_exam_full", missResult.cacheKey)
        assertEquals(2, missResult.document.totalQuestions)

        val sqlQueriesAfterMiss = fakeQuestionDao.queryCount
        assertTrue(sqlQueriesAfterMiss > 0)

        // 2ª Leitura: CACHE HIT (retorna direto da camada rápida sem tocar no SQL)
        val secondRead = repository.getMockExamCacheAside(1L)
        assertTrue(secondRead is Result.Success)
        val hitResult = (secondRead as Result.Success).data
        assertEquals(CacheLookupStatus.HIT, hitResult.status)
        assertEquals(2, hitResult.document.questions.size)

        // Comprova que nenhuma query adicional foi feita no banco relacional durante o HIT
        assertEquals(sqlQueriesAfterMiss, fakeQuestionDao.queryCount)

        val metrics = cacheService.getCacheMetrics()
        assertEquals(1L, metrics.missCount)
        assertEquals(1L, metrics.hitCount)
    }

    @Test
    fun `write invalidation should immediately evict cached document when new questions are saved`() = runTest {
        // Popula o cache (MISS -> HIT)
        repository.getMockExamCacheAside(1L)
        val hitBeforeUpdate = (repository.getMockExamCacheAside(1L) as Result.Success).data
        assertEquals(CacheLookupStatus.HIT, hitBeforeUpdate.status)
        assertEquals(2, hitBeforeUpdate.document.totalQuestions)

        // Escrita na Fonte da Verdade: adiciona nova questão na disciplina 10L
        repository.saveQuestions(
            listOf(
                Question(
                    id = 103L,
                    disciplineId = 10L,
                    statement = "Nova Questão Pós-Edital",
                    options = listOf("A) Certa", "B) Errada"),
                    correctIndex = 0,
                    explanation = "Novo gabarito",
                    source = QuestionSource.AI_GENERATED
                )
            )
        )

        // Verifica que a invalidação na escrita foi contabilizada
        assertEquals(1L, cacheService.getCacheMetrics().invalidationCount)

        // Próxima leitura DEVE ser CACHE MISS e trazer as 3 questões atualizadas (nunca dado velho!)
        val readAfterInvalidation = (repository.getMockExamCacheAside(1L) as Result.Success).data
        assertEquals(CacheLookupStatus.MISS, readAfterInvalidation.status)
        assertEquals(3, readAfterInvalidation.document.totalQuestions)
        assertTrue(readAfterInvalidation.document.questions.any { it.statement == "Nova Questão Pós-Edital" })
    }

    @Test
    fun `durable document store should survive volatile memory restart and TTL should expire after 24h`() = runTest {
        val baseTime = 1_700_000_000_000L
        val firstLookup = (cacheService.getMockExamCacheAside(1L, nowMillis = baseTime) as Result.Success).data
        assertEquals(CacheLookupStatus.MISS, firstLookup.status)

        // Simula reinicialização imediata do app (limpa memória volátil L1, mantendo persistência L2 do Firestore)
        firestoreDataSource.simulateAppRestartClearVolatileMemoryOnly()

        // Leitura imediatamente após reinício: ainda é CACHE HIT a partir do armazenamento durável de documentos!
        val afterRestartLookup = (cacheService.getMockExamCacheAside(1L, nowMillis = baseTime + 5_000L) as Result.Success).data
        assertEquals(CacheLookupStatus.HIT, afterRestartLookup.status)
        assertNotNull(afterRestartLookup.document)

        // Avança o relógio além do TTL de 24 horas (86.400.000 ms + 1 ms)
        val expiredTime = baseTime + MockExamDocument.DEFAULT_TTL_MILLIS + 1L
        val afterTtlLookup = (cacheService.getMockExamCacheAside(1L, nowMillis = expiredTime) as Result.Success).data
        assertEquals(CacheLookupStatus.MISS, afterTtlLookup.status)
    }
}

