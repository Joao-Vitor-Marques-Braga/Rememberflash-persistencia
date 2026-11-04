# RememberFlash — Persistência Poliglota (Relacional SQL Supabase + NoSQL Firebase Firestore / Cache-Aside)

**Universidade de Rio Verde (UniRV) — Faculdade de Engenharia de Software**  
**Disciplina:** ESW441 — Desenvolvimento de Software para persistencia · **Turma:** A  
**Docente:** Prof. Me. Gustavo Martins Lima  

### Integrantes do Trio
* **Gabriel de Oliveira Silva**
* **João Vitor Marques Braga**
* **Maria Eduarda Lira Lemes Braz**

---

## 1. Visão Geral da Arquitetura Implementada (100% Nuvem Real)

O **RememberFlash** é um aplicativo móvel Android (Kotlin / Jetpack Compose / Hilt) voltado à preparação para concursos públicos com resolução de simulados, flashcards e cronogramas de estudo.

Para eliminar o gargalo de montagem e recuperação repetida de simulados completos (**40 questões em 4 disciplinas**, que originalmente exigia **6 consultas encadeadas** nas tabelas relacionais do **Supabase PostgreSQL** `contests` $\rightarrow$ `disciplines` $\rightarrow$ `questions` e 40 operações de *parsing* de arrays JSON), foi implementada uma **Camada Não Relacional Baseada em Documentos (Firebase Cloud Firestore)** operando sob o padrão **Cache-Aside**:

* **Fonte da Verdade Relacional (SQL):** **Supabase PostgreSQL** (`https://tbnziqgjyxqrkadfhixi.supabase.co`)
* **Camada Não Relacional / Cache:** **Firebase Cloud Firestore** (Projeto: `rememberflash-persistencia`)
* **Coleção NoSQL:** `mock_exams`
* **Chave Primária $O(1)$ do Documento:** `contest_{contest_id}_exam_full` (ex.: `contest_4_exam_full`)
* **Chave de Cache:** `mock_exam:contest_{contest_id}_exam_full`
* **Invalidação:** **Invalidação Imediata na Escrita** (`DEL mock_exams/contest_{id}_exam_full` disparado automaticamente ao criar, editar ou limpar questões/tentativas na Fonte da Verdade) + **TTL de 24 horas (86.400 s)** como rede de segurança.

---

## 2. Estrutura dos Principais Arquivos da Entrega N2

| Arquivo / Caminho | Descrição Técnica |
| :--- | :--- |
| [`BENCHMARK_REPORT.md`](file:///d:/Rememberflash-persistencia/BENCHMARK_REPORT.md) | **Ficha de Medição Oficial (1 Página)**, Tabela Antes/Depois (MISS vs. HIT na nuvem real), Análise de Durabilidade, Trade-offs e Limites. |
| [`RELATORIO_N2_SEMANA4_MEDICAO_E_ENTREGA_FINAL.md`](file:///d:/Rememberflash-persistencia/RELATORIO_N2_SEMANA4_MEDICAO_E_ENTREGA_FINAL.md) | Relatório completo da Semana 4 com **Roteiro Técnico de 12 minutos** para o vídeo demonstrativo dividido entre os 3 integrantes. |
| [`BenchmarkComparativoN2Test.kt`](file:///d:/Rememberflash-persistencia/app/src/test/java/com/rememberflash/app/BenchmarkComparativoN2Test.kt) | **Benchmark Automatizado em Kotlin (100% Nuvem Real)** conectando ao Supabase PostgreSQL e ao Firebase Cloud Firestore, medindo Baseline SQL, Cache MISS, Cache HIT e Invalidação na Escrita ao vivo. |
| [`firestore.rules`](file:///d:/Rememberflash-persistencia/firestore.rules) & [`firebase.json`](file:///d:/Rememberflash-persistencia/firebase.json) | Regras de segurança, validação de schema do documento desnormalizado e índices do Firebase Cloud Firestore. |
| [`MockExamDocument.kt`](file:///d:/Rememberflash-persistencia/app/src/main/java/com/rememberflash/app/domain/model/MockExamDocument.kt) | Modelo de domínio do documento único autocontido (`MockExamDocument`, `MockExamQuestionItem`, `CacheLookupStatus`). |
| [`FirestoreMockExamDataSource.kt`](file:///d:/Rememberflash-persistencia/app/src/main/java/com/rememberflash/app/data/remote/firebase/FirestoreMockExamDataSource.kt) | Implementação da camada NoSQL/Cache conectada à API REST v1 do **Firebase Cloud Firestore** + cache L1/L2 local. |
| [`MockExamCacheService.kt`](file:///d:/Rememberflash-persistencia/app/src/main/java/com/rememberflash/app/data/remote/firebase/MockExamCacheService.kt) | Serviço de orquestração **Cache-Aside** (isolamento de HIT, MISS, Baseline SQL e Invalidação `DEL`). |
| [`QuestionRepositoryImpl.kt`](file:///d:/Rememberflash-persistencia/app/src/main/java/com/rememberflash/app/data/repository/QuestionRepositoryImpl.kt) | Repositório integrado disparando invalidação automática na escrita (`saveQuestions`, `clearQuestionsByDiscipline`, `saveMockExamAttempt`). |
| [`QuestionResolveContestViewModel.kt`](file:///d:/Rememberflash-persistencia/app/src/main/java/com/rememberflash/app/presentation/question/resolve/QuestionResolveContestViewModel.kt) | ViewModel de resolução de simulado do concurso integrada à leitura rápida `getMockExamCacheAside(contestId)`. |
| [`MockExamFirestoreCacheTest.kt`](file:///d:/Rememberflash-persistencia/app/src/test/java/com/rememberflash/app/MockExamFirestoreCacheTest.kt) | Suíte de testes unitários JUnit validando Cache MISS, Cache HIT, Invalidação na Escrita, Durabilidade pós-reinício e expiração de TTL. |

---

## 3. Como Executar o Benchmark Real em Kotlin (Supabase + Firebase)

### 3.1. Pré-requisitos
* **JDK 21** (já configurado em `gradle.properties` apontando para o JBR oficial do Android Studio) e **Android SDK**.
* Arquivo `local.properties` configurado na raiz com `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `SUPABASE_SERVICE_ROLE_KEY`, `FIREBASE_PROJECT_ID` e `FIREBASE_API_KEY`.

### 3.2. Comando para Executar no Terminal (PowerShell / CMD)

Para rodar a medição comparativa em **Kotlin** conectando ao **Supabase** e ao **Firebase Cloud Firestore** reais:
```powershell
.\rodar_benchmark_kotlin.bat
```
Ou diretamente via Gradle:
```powershell
.\gradlew.bat testDebugUnitTest --tests com.rememberflash.app.BenchmarkComparativoN2Test --rerun-tasks
```

Para rodar também a suíte de testes unitários de verificação da arquitetura:
```powershell
.\gradlew.bat testDebugUnitTest --tests com.rememberflash.app.MockExamFirestoreCacheTest --rerun-tasks
```

---

## 4. Resumo dos Resultados Reais Medidos na Nuvem ($N = 15$, Simulado de 40 Questões / 4 Disciplinas)

| Operação Avaliada (100% Nuvem Real) | Média (ms) | Mediana (ms) | P95 (ms) | Requisições na Nuvem | Ganho ($\text{Antes} \div \text{Depois}$) |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **1. Baseline SQL (Supabase PostgreSQL)** | `609,26 ms` | `610,43 ms` | `649,76 ms` | 6 queries HTTP (`contests` + `disciplines` + 4x `questions`) | **1,00x** *(Ref)* |
| **2. Cache MISS (1ª Leitura: Supabase $\rightarrow$ Firebase)** | `1.730,32 ms` | `1.665,05 ms` | `1.741,48 ms` | 1 `GET` Firebase (404) + 6 queries Supabase + 1 `PATCH` Firebase | **+184,0%** *(População)* |
| **3. Cache HIT Cloud (Firebase Firestore 1 Doc)** | `466,69 ms` | `445,95 ms` | `563,30 ms` | **1 única leitura `GET`** em `mock_exams/contest_4_exam_full` | **1,31x** (**-23,4%** latência / **-83,3%** queries) |
| **4. Cache HIT Local (Firestore SDK L1/L2 Cache)** | `0,0230 ms` | `0,0195 ms` | `0,0287 ms` | **0 queries de rede** (memória/disco local do SDK) | **26.474,2x** (**-99,99%** latência) |
