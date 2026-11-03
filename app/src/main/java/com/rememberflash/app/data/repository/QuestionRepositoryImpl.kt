package com.rememberflash.app.data.repository

import com.rememberflash.app.data.local.database.dao.QuestionDao
import com.rememberflash.app.data.mapper.toDomain
import com.rememberflash.app.data.mapper.toEntity
import com.rememberflash.app.data.remote.firebase.MockExamCacheService
import com.rememberflash.app.domain.common.Result
import com.rememberflash.app.domain.model.MockExamLookupResult
import com.rememberflash.app.domain.model.Question
import com.rememberflash.app.domain.repository.QuestionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QuestionRepositoryImpl @Inject constructor(
    private val questionDao: QuestionDao,
    private val mockExamAttemptDao: com.rememberflash.app.data.local.database.dao.MockExamAttemptDao,
    private val mockExamCacheService: MockExamCacheService
) : QuestionRepository {

    override fun getQuestionsByDiscipline(disciplineId: Long): Flow<List<Question>> {
        return questionDao.getByDiscipline(disciplineId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun saveQuestions(questions: List<Question>): Result<Unit> {
        return try {
            // 1. Regra de Ouro do Cache-Aside: Grava primeiro na Fonte da Verdade (Relacional)
            val entities = questions.map { it.toEntity() }
            questionDao.insertAll(entities)

            // 2. Invalidação na Escrita (Write Invalidation): apaga a chave do simulado correspondente no NoSQL
            questions.map { it.disciplineId }.distinct().forEach { discId ->
                mockExamCacheService.invalidateCacheForDiscipline(discId)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.error("Falha ao salvar lote de questões: ${e.localizedMessage}", e)
        }
    }

    override suspend fun clearQuestionsByDiscipline(disciplineId: Long): Result<Unit> {
        return try {
            questionDao.deleteByDiscipline(disciplineId)
            mockExamCacheService.invalidateCacheForDiscipline(disciplineId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.error("Falha ao limpar questões: ${e.localizedMessage}", e)
        }
    }

    override suspend fun answerQuestion(questionId: Long, chosenOption: Int, isCorrect: Boolean): Result<Unit> {
        return try {
            questionDao.updateAnswer(questionId, chosenOption, isCorrect, System.currentTimeMillis())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.error("Falha ao registrar resposta da questão: ${e.localizedMessage}", e)
        }
    }

    override fun getAllQuestions(): Flow<List<Question>> {
        return questionDao.getAllQuestions().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getQuestionById(questionId: Long): Result<Question> {
        return try {
            val entity = questionDao.getById(questionId)
                ?: return Result.error("Questão não encontrada")
            Result.success(entity.toDomain())
        } catch (e: Exception) {
            Result.error("Falha ao recuperar questão: ${e.localizedMessage}", e)
        }
    }

    override fun getAttemptsByDiscipline(disciplineId: Long): Flow<List<com.rememberflash.app.domain.model.MockExamAttempt>> {
        return mockExamAttemptDao.getAttemptsByDiscipline(disciplineId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getAttemptsByContest(contestId: Long): Flow<List<com.rememberflash.app.domain.model.MockExamAttempt>> {
        return mockExamAttemptDao.getAttemptsByContest(contestId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getAllAttempts(): Flow<List<com.rememberflash.app.domain.model.MockExamAttempt>> {
        return mockExamAttemptDao.getAllAttempts().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun saveMockExamAttempt(attempt: com.rememberflash.app.domain.model.MockExamAttempt): Result<Long> {
        return try {
            val id = mockExamAttemptDao.insertAttempt(attempt.toEntity())
            attempt.contestId?.let { cId ->
                mockExamCacheService.invalidateContestCache(cId)
            }
            Result.success(id)
        } catch (e: Exception) {
            Result.error("Erro ao salvar tentativa de simulado: ${e.localizedMessage}", e)
        }
    }

    override suspend fun resetQuestionsForDiscipline(disciplineId: Long): Result<Unit> {
        return try {
            questionDao.resetAnswersForDiscipline(disciplineId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.error("Erro ao resetar questões da disciplina: ${e.localizedMessage}", e)
        }
    }

    override suspend fun resetQuestionsForContest(contestId: Long): Result<Unit> {
        return try {
            questionDao.resetAnswersForContest(contestId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.error("Erro ao resetar questões do concurso: ${e.localizedMessage}", e)
        }
    }

    override fun getQuestionsByTopic(topicId: Long): Flow<List<Question>> {
        return questionDao.getByTopic(topicId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun clearQuestionsByTopic(topicId: Long): Result<Unit> {
        return try {
            questionDao.deleteByTopic(topicId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.error("Falha ao limpar questões do tópico: ${e.localizedMessage}", e)
        }
    }

    override suspend fun getMockExamCacheAside(contestId: Long): Result<MockExamLookupResult> {
        return mockExamCacheService.getMockExamCacheAside(contestId)
    }

    override suspend fun getMockExamRelationalBaseline(contestId: Long): Result<MockExamLookupResult> {
        return mockExamCacheService.getMockExamRelationalBaseline(contestId)
    }

    override suspend fun invalidateMockExamCache(contestId: Long): Result<Boolean> {
        return Result.success(mockExamCacheService.invalidateContestCache(contestId))
    }

    private fun com.rememberflash.app.data.local.database.entity.MockExamAttemptEntity.toDomain() = com.rememberflash.app.domain.model.MockExamAttempt(
        id = id,
        contestId = contestId,
        disciplineId = disciplineId,
        score = score,
        totalQuestions = totalQuestions,
        answersJson = answersJson,
        timesJson = timesJson,
        createdAt = createdAt
    )

    private fun com.rememberflash.app.domain.model.MockExamAttempt.toEntity() = com.rememberflash.app.data.local.database.entity.MockExamAttemptEntity(
        id = id,
        contestId = contestId,
        disciplineId = disciplineId,
        score = score,
        totalQuestions = totalQuestions,
        answersJson = answersJson,
        timesJson = timesJson,
        createdAt = createdAt
    )
}
