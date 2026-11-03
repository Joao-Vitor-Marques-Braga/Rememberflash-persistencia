# Projeto N2 — Semana 4 (27/10): Medição e Entrega Final

**Disciplina:** Tecnologias de Persistência — Bancos Não Relacionais e Cache  
**Aplicação Alvo (TFC):** RememberFlash (Android / Kotlin + Jetpack Compose)  
**Arquitetura Poliglota Real:** Supabase PostgreSQL (Fonte da Verdade SQL) + Firebase Cloud Firestore (Camada NoSQL/Cache de Documentos)  
**Integrantes do Grupo:**
- **Gabriel de Oliveira Silva**
- **João Vitor Marques Braga**
- **Maria Eduarda Lira Lemes Braz**

---

## 1. Resumo Executivo da Entrega Final

Este documento consolida a entrega final do **Projeto N2 (Semana 4)** do aplicativo **RememberFlash**, cumprindo todos os requisitos de engenharia e avaliação prática em **100% código Kotlin** conectado a bancos em nuvem reais:

1. **Isolamento do Domínio de Questões e Simulados:** As consultas de montagem de simulado foram isoladas no [`MockExamCacheService`](file:///d:/Rememberflash-persistencia/app/src/main/java/com/rememberflash/app/data/remote/firebase/MockExamCacheService.kt) e integradas ao [`QuestionRepositoryImpl`](file:///d:/Rememberflash-persistencia/app/src/main/java/com/rememberflash/app/data/repository/QuestionRepositoryImpl.kt) e ao [`QuestionResolveContestViewModel`](file:///d:/Rememberflash-persistencia/app/src/main/java/com/rememberflash/app/presentation/question/resolve/QuestionResolveContestViewModel.kt).
2. **Camada NoSQL Baseada em Documentos (Firebase Cloud Firestore):** Implementada no [`FirestoreMockExamDataSource`](file:///d:/Rememberflash-persistencia/app/src/main/java/com/rememberflash/app/data/remote/firebase/FirestoreMockExamDataSource.kt) conectando diretamente ao projeto Firebase `rememberflash-persistencia` na coleção `mock_exams` (documento `contest_4_exam_full`), aplicando a Regra de Ouro de modelagem de documentos (*"O que muda junto e é consultado junto, fica junto no mesmo documento"*).
3. **Padrão Cache-Aside + Invalidação na Escrita (`DEL`) + TTL de 24h:** A primeira leitura (**Cache Miss**) consulta o banco relacional PostgreSQL no **Supabase** (`https://tbnziqgjyxqrkadfhixi.supabase.co`), monta o documento desnormalizado e o grava no **Firebase Cloud Firestore**; leituras subsequentes (**Cache Hit**) buscam o documento único diretamente no Firebase (1 única requisição em vez de 6 queries relacionais) e na camada L1/L2 local do SDK. Quando qualquer questão é inserida, editada ou removida na Fonte da Verdade (SQL), o sistema executa imediatamente `DEL mock_exams/contest_{id}_exam_full` no Firebase Firestore, impedindo a exibição de dados defasados.

---

## 2. Ficha de Medição Oficial (Antes vs. Depois — Dados Reais em Nuvem)

### 2.1 Condições Controladas do Experimento
- **Comando Executado:** `.\gradlew.bat testDebugUnitTest --tests com.rememberflash.app.BenchmarkComparativoN2Test --rerun-tasks` (ou `.\rodar_benchmark_kotlin.bat`)
- **Fonte da Verdade Relacional (Antes / Baseline SQL):** **Supabase PostgreSQL** (`https://tbnziqgjyxqrkadfhixi.supabase.co`)
- **Camada Não Relacional / Cache (Depois / NoSQL):** **Firebase Cloud Firestore** (`rememberflash-persistencia` $\rightarrow$ `mock_exams/contest_4_exam_full`)
- **Dataset Avaliado ($V$):** Simulado completo com **40 questões reais**, **4 disciplinas ativas** (*Direito Constitucional, Direito Administrativo, Direito Tributário e Financeiro, Engenharia de Dados e Bancos NoSQL*) e **200 alternativas** (`options_json`).
- **Amostragem ($N$):** **$N = 15$ execuções reais na nuvem** por cenário + **5 execuções completas de Cold Start (Cache Miss)** + ciclo real de **Invalidação na Escrita (`UPDATE` no Supabase + `DELETE` no Firebase)**.

### 2.2 Tabela Comparativa de Desempenho

| Operação Medida (100% Nuvem Real) | Média (ms) | Mediana (ms) | P95 (ms) | Desvio Padrão | Queries na Nuvem | Ganho ($\text{Antes} \div \text{Depois}$) |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| **1. Baseline SQL (Supabase PostgreSQL)** | **609,26 ms** | **610,43 ms** | **649,76 ms** | 21,84 ms | **6 queries HTTP** (`contests` + `disciplines` + 4x `questions`) | **1,00x** *(Referência)* |
| **2. Cache MISS (1ª Leitura: Supabase $\rightarrow$ Firebase)** | **1.730,32 ms** | **1.665,05 ms** | **1.741,48 ms** | 84,10 ms | 1 `GET` Firebase (404) + 6 queries Supabase + 1 `PATCH` Firebase | **+184,0%** *(Custo de população)* |
| **3. Cache HIT Cloud (Firebase Firestore 1 Doc)** | **466,69 ms** | **445,95 ms** | **563,30 ms** | 59,22 ms | **1 única leitura `GET`** em `mock_exams/contest_4_exam_full` | **1,31x mais rápido** (**-23,4%** latência / **-83,3%** queries) |
| **4. Cache HIT Local (Firestore SDK L1/L2 Cache)** | **0,0230 ms** | **0,0195 ms** | **0,0287 ms** | 0,008 ms | **0 queries de rede** (leitura direta do documento em cache local) | **26.474,2x mais rápido** (**-99,99%** latência) |

### 2.3 Equações de Ganho Obtido
$$\text{Ganho}_{\text{Cloud (1 Documento vs. 6 Queries SQL)}} = \frac{609,26\text{ ms}}{466,69\text{ ms}} = \mathbf{1,31\times\text{ mais rápido}} \quad (\text{e }\mathbf{1,56\times}\text{ no Hit pós-invalidação de }391,54\text{ ms})$$

$$\text{Ganho}_{\text{SDK Local (Sessão Aquecida L1/L2)}} = \frac{609,26\text{ ms}}{0,0230\text{ ms}} = \mathbf{26.474,2\times\text{ mais rápido}}$$

$$\text{Redução de Requisições de Rede no Banco Relacional} = \frac{6 - 1}{6} = \mathbf{83,3\%\text{ menos chamadas HTTP na nuvem (e }100\%\text{ no Hit L1/L2)}}$$

---

## 3. Roteiro Técnico Passo a Passo para Gravação do Vídeo (~12 Minutos)

> [!TIP]
> **Divisão Sugerida de Tempo entre os 3 Integrantes (Total: ~12 minutos):**
> - **Parte 1 (00:00 – 04:00) — Gabriel de Oliveira Silva:** O Sistema, o Ponto de Dor no Relacional (Supabase) e a Modelagem do Documento Único no Firebase Firestore.
> - **Parte 2 (04:00 – 08:30) — João Vitor Marques Braga:** Demonstração ao Vivo (Execução do código Kotlin conectando no Supabase e no Firebase, mostrando o Console do Firebase com o documento `contest_4_exam_full`, Cache Miss, Cache Hit e Invalidação na Escrita).
> - **Parte 3 (08:30 – 12:00) — Maria Eduarda Lira Lemes Braz:** Apresentação da Ficha de Medição, Cálculo do Ganho, Durabilidade, Custos e Limites Operacionais.
