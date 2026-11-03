package com.rememberflash.app.domain.model

import com.google.gson.annotations.SerializedName
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Status do acesso à camada NoSQL/Cache (Firebase Firestore) segundo o padrão Cache-Aside.
 */
enum class CacheLookupStatus {
    /** Primeira leitura ou leitura após invalidação/expiração: busca na Fonte da Verdade (SQL) e popula o NoSQL. */
    MISS,
    /** Leitura subsequente atendida diretamente pelo documento desnormalizado na camada rápida NoSQL/Cache. */
    HIT,
    /** Leitura executada diretamente na camada relacional (Baseline SQL sem cache) para fins de comparação. */
    BASELINE_SQL
}

/**
 * Item de questão desnormalizado dentro do documento único do simulado ([MockExamDocument]).
 *
 * Elimina a necessidade de JOINs com a tabela `disciplines` e elimina o parsing individual
 * de `options_json` a cada leitura repetida, pois a lista de alternativas já fica estruturada
 * nativamente no array do documento JSON/Firestore.
 */
@Serializable
data class MockExamQuestionItem(
    @SerializedName("question_id")
    @SerialName("question_id")
    val questionId: Long,

    @SerializedName("discipline_id")
    @SerialName("discipline_id")
    val disciplineId: Long,

    @SerializedName("discipline_name")
    @SerialName("discipline_name")
    val disciplineName: String,

    @SerializedName("topic_id")
    @SerialName("topic_id")
    val topicId: Long? = null,

    @SerializedName("statement")
    @SerialName("statement")
    val statement: String,

    @SerializedName("options")
    @SerialName("options")
    val options: List<String>,

    @SerializedName("correct_index")
    @SerialName("correct_index")
    val correctIndex: Int,

    @SerializedName("explanation")
    @SerialName("explanation")
    val explanation: String? = null,

    @SerializedName("source")
    @SerialName("source")
    val source: String = "AI_GEMINI",

    @SerializedName("created_at")
    @SerialName("created_at")
    val createdAt: Long = 0L
) {
    fun toDomainQuestion(): Question = Question(
        id = questionId,
        disciplineId = disciplineId,
        topicId = topicId,
        statement = statement,
        options = options,
        correctIndex = correctIndex,
        explanation = explanation,
        source = if (source.contains("AI", ignoreCase = true) || source.contains("GEMINI", ignoreCase = true)) {
            QuestionSource.AI_GENERATED
        } else {
            QuestionSource.MANUAL
        },
        createdAt = createdAt
    )
}

/**
 * Resumo agregado do histórico de tentativas de resolução do simulado embutido no documento.
 */
@Serializable
data class MockExamAttemptsSummary(
    @SerializedName("total_attempts")
    @SerialName("total_attempts")
    val totalAttempts: Int = 0,

    @SerializedName("last_score")
    @SerialName("last_score")
    val lastScore: Int? = null,

    @SerializedName("last_attempt_at")
    @SerialName("last_attempt_at")
    val lastAttemptAt: Long? = null,

    @SerializedName("last_answers")
    @SerialName("last_answers")
    val lastAnswers: Map<String, Int> = emptyMap(),

    @SerializedName("last_times")
    @SerialName("last_times")
    val lastTimes: Map<String, Long> = emptyMap()
)

/**
 * Unidade de Dado Não Relacional Baseada em Documentos (Firebase Cloud Firestore).
 *
 * Coleção: `mock_exams`
 * Chave do Documento (`_id`): `contest_{contest_id}_exam_full`
 * Chave de Cache: `mock_exam:contest_{contest_id}_exam_full`
 *
 * Regra de Ouro Aplicada: "O que muda junto e é consultado junto, fica junto no mesmo documento".
 * Armazena metadados do concurso, todas as questões desnormalizadas com alternativas já estruturadas,
 * gabaritos comentados e o resumo da última tentativa, com controle de versão e TTL de 24h.
 */
@Serializable
data class MockExamDocument(
    @SerializedName("_id")
    @SerialName("_id")
    val id: String,

    @SerializedName("contest_id")
    @SerialName("contest_id")
    val contestId: Long,

    @SerializedName("contest_title")
    @SerialName("contest_title")
    val contestTitle: String,

    @SerializedName("organizer_name")
    @SerialName("organizer_name")
    val organizerName: String,

    @SerializedName("question_type")
    @SerialName("question_type")
    val questionType: String = "Múltipla Escolha",

    @SerializedName("ai_difficulty")
    @SerialName("ai_difficulty")
    val aiDifficulty: String = "Difícil",

    @SerializedName("total_questions")
    @SerialName("total_questions")
    val totalQuestions: Int,

    @SerializedName("time_limit_minutes")
    @SerialName("time_limit_minutes")
    val timeLimitMinutes: Int = 240,

    @SerializedName("created_at")
    @SerialName("created_at")
    val createdAt: Long,

    @SerializedName("updated_at")
    @SerialName("updated_at")
    val updatedAt: Long,

    @SerializedName("ttl_expires_at")
    @SerialName("ttl_expires_at")
    val ttlExpiresAt: Long,

    @SerializedName("version")
    @SerialName("version")
    val version: Int = 1,

    @SerializedName("questions")
    @SerialName("questions")
    val questions: List<MockExamQuestionItem>,

    @SerializedName("attempts_summary")
    @SerialName("attempts_summary")
    val attemptsSummary: MockExamAttemptsSummary = MockExamAttemptsSummary()
) {
    fun isExpired(nowMillis: Long = System.currentTimeMillis()): Boolean {
        return nowMillis >= ttlExpiresAt
    }

    companion object {
        /** TTL padrão de 24 horas (86.400 segundos = 86.400.000 ms) conforme modelagem da Semana 2 */
        const val DEFAULT_TTL_MILLIS: Long = 24L * 60L * 60L * 1000L

        /** Gera o identificador determinístico O(1) do documento na coleção `mock_exams` */
        fun buildDocumentId(contestId: Long): String = "contest_${contestId}_exam_full"

        /** Gera a chave lógica de cache conforme especificação da Semana 2 */
        fun buildCacheKey(contestId: Long): String = "mock_exam:${buildDocumentId(contestId)}"
    }
}

/**
 * Envelope de retorno da busca de simulado contendo métricas de diagnóstico (HIT/MISS/Baseline e latência em ms).
 */
data class MockExamLookupResult(
    val document: MockExamDocument,
    val status: CacheLookupStatus,
    val elapsedMs: Double,
    val cacheKey: String = MockExamDocument.buildCacheKey(document.contestId)
)

