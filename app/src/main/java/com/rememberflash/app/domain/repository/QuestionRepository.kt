package com.rememberflash.app.domain.repository

import com.rememberflash.app.domain.common.Result
import com.rememberflash.app.domain.model.MockExamLookupResult
import com.rememberflash.app.domain.model.Question
import kotlinx.coroutines.flow.Flow

interface QuestionRepository {
    fun getQuestionsByDiscipline(disciplineId: Long): Flow<List<Question>>
    suspend fun saveQuestions(questions: List<Question>): Result<Unit>
    suspend fun clearQuestionsByDiscipline(disciplineId: Long): Result<Unit>
    suspend fun answerQuestion(questionId: Long, chosenOption: Int, isCorrect: Boolean): Result<Unit>
    fun getAllQuestions(): Flow<List<Question>>
    suspend fun getQuestionById(questionId: Long): Result<Question>
    fun getAttemptsByDiscipline(disciplineId: Long): Flow<List<com.rememberflash.app.domain.model.MockExamAttempt>>
    fun getAttemptsByContest(contestId: Long): Flow<List<com.rememberflash.app.domain.model.MockExamAttempt>>
    fun getAllAttempts(): Flow<List<com.rememberflash.app.domain.model.MockExamAttempt>>
    suspend fun saveMockExamAttempt(attempt: com.rememberflash.app.domain.model.MockExamAttempt): Result<Long>
    suspend fun resetQuestionsForDiscipline(disciplineId: Long): Result<Unit>
    suspend fun resetQuestionsForContest(contestId: Long): Result<Unit>
    fun getQuestionsByTopic(topicId: Long): Flow<List<Question>>
    suspend fun clearQuestionsByTopic(topicId: Long): Result<Unit>

    /**
     * Recupera o simulado completo do concurso aplicando o padrão Cache-Aside
     * sobre a camada de documentos NoSQL (Firebase Cloud Firestore).
     * Separa explicitamente CACHE HIT (leitura O(1)) de CACHE MISS (reconstrução da Fonte da Verdade).
     */
    suspend fun getMockExamCacheAside(contestId: Long): Result<MockExamLookupResult> =
        Result.error("Camada NoSQL/Cache não inicializada neste repositório")

    /**
     * Executa a consulta original isolada diretamente no banco relacional (Baseline SQL sem cache)
     * para comparação rigorosa de desempenho (Antes vs. Depois).
     */
    suspend fun getMockExamRelationalBaseline(contestId: Long): Result<MockExamLookupResult> =
        Result.error("Baseline SQL isolado não inicializado neste repositório")

    /**
     * Invalidação explícita na escrita (Write Invalidation: DEL mock_exam:contest_{id}_exam_full).
     */
    suspend fun invalidateMockExamCache(contestId: Long): Result<Boolean> =
        Result.success(false)
}
