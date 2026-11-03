package com.rememberflash.app.presentation.question.resolve

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rememberflash.app.domain.common.Result
import com.rememberflash.app.domain.repository.ContestRepository
import com.rememberflash.app.domain.repository.DisciplineRepository
import com.rememberflash.app.domain.repository.QuestionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class QuestionResolveContestViewModel @Inject constructor(
    private val contestRepository: ContestRepository,
    private val disciplineRepository: DisciplineRepository,
    private val questionRepository: QuestionRepository,
    private val proposeScheduleRecalculationUseCase: com.rememberflash.app.domain.usecase.schedule.ProposeScheduleRecalculationUseCase,
    private val acceptProposedScheduleUseCase: com.rememberflash.app.domain.usecase.schedule.AcceptProposedScheduleUseCase,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    // [ITEM 1.8 — FIX] Instância única reutilizada em vez de `Gson()` criada a cada
    // chamada de saveAttempt(). Gson é thread-safe e tem custo de inicialização.
    private val gson = com.google.gson.Gson()

    private val _uiState = MutableStateFlow(QuestionResolveContestUiState())
    val uiState: StateFlow<QuestionResolveContestUiState> = _uiState.asStateFlow()

    private val contestId: Long = checkNotNull(savedStateHandle["contestId"]) {
        "contestId é obrigatório"
    }

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            // 1. Tenta buscar o simulado unificado via camada NoSQL/Cache (Firebase Firestore Cache-Aside)
            when (val cacheAsideResult = questionRepository.getMockExamCacheAside(contestId)) {
                is Result.Success -> {
                    val lookup = cacheAsideResult.data
                    val unifiedFromDoc = lookup.document.questions.map { item ->
                        QuestionWithDiscipline(
                            question = item.toDomainQuestion(),
                            disciplineName = item.disciplineName
                        )
                    }
                    _uiState.value = _uiState.value.copy(
                        contestTitle = lookup.document.contestTitle,
                        questions = unifiedFromDoc,
                        isLoading = false,
                        currentQuestionStartTime = System.currentTimeMillis()
                    )
                    return@launch
                }
                else -> {
                    // Fallback para o caminho relacional legado caso a camada de cache não esteja ativa (ex: mocks)
                }
            }

            // Reseta respostas anteriores para iniciar simulado novo limpo (Caminho Relacional Legado)
            questionRepository.resetQuestionsForContest(contestId)

            // Carrega Concurso
            val contestResult = contestRepository.getById(contestId)
            val title = when (contestResult) {
                is Result.Success -> contestResult.data.title
                else -> "Simulado do Edital"
            }
            _uiState.value = _uiState.value.copy(contestTitle = title)

            try {
                // Carrega todas as disciplinas do concurso
                val disciplines = disciplineRepository.getByContest(contestId).first()
                    .filter { it.isActive }

                val unifiedQuestions = mutableListOf<QuestionWithDiscipline>()

                // Carrega as questões de cada disciplina
                disciplines.forEach { discipline ->
                    val questions = questionRepository.getQuestionsByDiscipline(discipline.id).first()
                    questions.forEach { question ->
                        unifiedQuestions.add(QuestionWithDiscipline(question, discipline.name))
                    }
                }

                _uiState.value = _uiState.value.copy(
                    questions = unifiedQuestions.sortedBy { it.question.createdAt },
                    isLoading = false,
                    currentQuestionStartTime = System.currentTimeMillis()
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Erro ao carregar simulado: ${e.localizedMessage}",
                    isLoading = false
                )
            }
        }
    }

    private fun updateTimeSpentForCurrentQuestion() {
        val currentIndex = _uiState.value.currentIndex
        val item = _uiState.value.questions.getOrNull(currentIndex) ?: return
        val question = item.question
        val hasSubmitted = _uiState.value.submittedAnswers.contains(currentIndex)
        if (hasSubmitted) return

        // [ITENS 1.1/1.2 — DUPLICAÇÃO REMOVIDA] Cálculo extraído para
        // elapsedSecondsFrom() e accumulateQuestionTime() em QuestionResolveTimeUtils.kt.
        val elapsed = elapsedSecondsFrom(_uiState.value.currentQuestionStartTime)
        if (elapsed == 0) return

        _uiState.value = _uiState.value.copy(
            questionTimes = accumulateQuestionTime(_uiState.value.questionTimes, question.id, elapsed),
            currentQuestionStartTime = System.currentTimeMillis()
        )
    }

    private fun saveAttempt() {
        val score = _uiState.value.score
        val totalQuestions = _uiState.value.questions.size
        
        // Mapeia questionId -> chosenOptionIndex
        val answersMap = _uiState.value.selectedAnswers.mapKeys { (index, _) ->
            _uiState.value.questions.getOrNull(index)?.question?.id ?: 0L
        }
        // [ITEM 1.8 — FIX] Usa instância gson da classe em vez de nova instância inline.
        val answersJsonString = gson.toJson(answersMap)
        val timesJsonString = gson.toJson(_uiState.value.questionTimes)

        viewModelScope.launch {
            val attempt = com.rememberflash.app.domain.model.MockExamAttempt(
                contestId = contestId,
                score = score,
                totalQuestions = totalQuestions,
                answersJson = answersJsonString,
                timesJson = timesJsonString
            )
            questionRepository.saveMockExamAttempt(attempt)

            // Calcula o desempenho por disciplina neste simulado
            val performance = mutableMapOf<Long, Pair<Int, Int>>() // disciplineId -> (correct, total)
            _uiState.value.questions.forEachIndexed { i, qWithDisp ->
                val q = qWithDisp.question
                val selected = _uiState.value.selectedAnswers[i]
                if (selected != null) {
                    val isCorrect = selected == q.correctIndex
                    val currentStats = performance[q.disciplineId] ?: Pair(0, 0)
                    performance[q.disciplineId] = Pair(
                        currentStats.first + (if (isCorrect) 1 else 0),
                        currentStats.second + 1
                    )
                }
            }

            // Invoca a geração da proposta de cronograma baseado das dificuldades
            val result = proposeScheduleRecalculationUseCase(contestId, performance)
            if (result is Result.Success) {
                val proposal = result.data
                _uiState.value = _uiState.value.copy(
                    difficulties = proposal.difficulties,
                    comparisonList = proposal.comparisonList,
                    proposedSchedule = proposal.proposedSchedule,
                    showRecalculationProposal = proposal.difficulties.isNotEmpty()
                )
            }
        }
    }

    fun selectOption(optionIndex: Int) {
        val currentIndex = _uiState.value.currentIndex
        val hasSubmitted = _uiState.value.submittedAnswers.contains(currentIndex)
        if (hasSubmitted) return

        val updatedMap = _uiState.value.selectedAnswers.toMutableMap()
        updatedMap[currentIndex] = optionIndex
        _uiState.value = _uiState.value.copy(selectedAnswers = updatedMap)
    }

    fun submitAnswer() {
        val currentIndex = _uiState.value.currentIndex
        val selectedIndex = _uiState.value.selectedAnswers[currentIndex] ?: return
        val item = _uiState.value.questions.getOrNull(currentIndex) ?: return
        val question = item.question

        val isCorrect = selectedIndex == question.correctIndex
        val updatedSet = _uiState.value.submittedAnswers.toMutableSet()
        updatedSet.add(currentIndex)

        // Trava o tempo gasto na questão no momento da submissão
        // [ITENS 1.1/1.2 — DUPLICAÇÃO REMOVIDA] Usa elapsedSecondsFrom() e
        // accumulateQuestionTime() de QuestionResolveTimeUtils.kt.
        val elapsedSeconds = elapsedSecondsFrom(_uiState.value.currentQuestionStartTime)
        val updatedTimes = accumulateQuestionTime(_uiState.value.questionTimes, question.id, elapsedSeconds)

        val newScore = if (isCorrect) _uiState.value.score + 1 else _uiState.value.score

        _uiState.value = _uiState.value.copy(
            submittedAnswers = updatedSet,
            score = newScore,
            questionTimes = updatedTimes
        )

        // Persistência local
        viewModelScope.launch {
            questionRepository.answerQuestion(question.id, selectedIndex, isCorrect)
        }
    }

    fun nextQuestion() {
        val nextIndex = _uiState.value.currentIndex + 1
        if (nextIndex < _uiState.value.questions.size) {
            updateTimeSpentForCurrentQuestion()
            _uiState.value = _uiState.value.copy(
                currentIndex = nextIndex,
                currentQuestionStartTime = System.currentTimeMillis()
            )
        } else {
            updateTimeSpentForCurrentQuestion()
            _uiState.value = _uiState.value.copy(isFinished = true)
            saveAttempt()
        }
    }

    fun previousQuestion() {
        val prevIndex = _uiState.value.currentIndex - 1
        if (prevIndex >= 0) {
            updateTimeSpentForCurrentQuestion()
            _uiState.value = _uiState.value.copy(
                currentIndex = prevIndex,
                currentQuestionStartTime = System.currentTimeMillis()
            )
        }
    }

    fun finishPractice() {
        // [BUG #4 — FIX] Mesma correção aplicada em QuestionResolveViewModel:
        // não acumula tempo da questão atual antes de encerrar.
        // - Se submetida: submitAnswer() já travou o tempo.
        // - Se não submetida: não deve entrar nas estatísticas (distorceria a média).
        _uiState.value = _uiState.value.copy(isFinished = true)
        saveAttempt()
    }

    fun acceptProposedSchedule() {
        val schedule = _uiState.value.proposedSchedule ?: return
        val goals = schedule.dailyGoals
        _uiState.value = _uiState.value.copy(isSavingProposal = true)
        viewModelScope.launch {
            val result = acceptProposedScheduleUseCase(schedule, goals)
            _uiState.value = _uiState.value.copy(
                isSavingProposal = false,
                proposalSaveResult = result,
                showRecalculationProposal = false
            )
        }
    }

    fun rejectProposedSchedule() {
        _uiState.value = _uiState.value.copy(
            showRecalculationProposal = false
        )
    }
}
