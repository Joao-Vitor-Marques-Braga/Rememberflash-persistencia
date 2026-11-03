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

---

### Bloco 1 — 00:00 a 02:00 | O Sistema e o Ponto de Dor (Gabriel de Oliveira Silva)
- **O que mostrar na tela:**
  - Repositório do projeto **RememberFlash** aberto no Android Studio / VS Code e painel do **Supabase** mostrando as tabelas relacionais (`contests`, `disciplines`, `topics`, `questions`).
- **Fala técnica sugerida:**
  - *"Olá professor, somos o grupo do projeto RememberFlash (Gabriel, João Vitor e Maria Eduarda). O RememberFlash é uma plataforma Android para preparação de concursos públicos que possui um módulo intensivo de Simulados."*
  - *"Na Semana 1 diagnosticamos nosso Ponto de Dor no modelo puramente relacional (PostgreSQL no Supabase e SQLite/Room): para montar um único simulado de 40 questões distribuídas em 4 disciplinas, a aplicação precisava executar **6 consultas relacionais encadeadas** — primeiro buscando os metadados em `contests`, depois listando as matérias ativas em `disciplines` e disparando $N$ queries separadas na tabela `questions` (uma para cada disciplina), além de fazer o parsing individual de 40 strings JSON (`options_json`) em tempo de leitura."*
  - *"Como um caderno de simulado é um domínio **Read-Heavy** (montado uma vez e lido dezenas de vezes pelo estudante), refazer essas 6 consultas relacionais a cada abertura gerava uma latência média de **609,26 ms** na nuvem e sobrecarga desnecessária no PostgreSQL."*

---

### Bloco 2 — 02:00 a 05:00 | Modelagem e Arquitetura (Gabriel de Oliveira Silva)
- **O que mostrar na tela:**
  - Arquivo [`MockExamDocument.kt`](file:///d:/Rememberflash-persistencia/app/src/main/java/com/rememberflash/app/domain/model/MockExamDocument.kt) e [`FirestoreMockExamDataSource.kt`](file:///d:/Rememberflash-persistencia/app/src/main/java/com/rememberflash/app/data/remote/firebase/FirestoreMockExamDataSource.kt).
  - Diagrama da arquitetura Poliglota (Supabase PostgreSQL como Fonte da Verdade $\rightarrow$ Firebase Cloud Firestore como camada de Documentos / Cache-Aside).
- **Fala técnica sugerida:**
  - *"Para resolver esse gargalo sem abrir mão da integridade relacional, mantivemos o **Supabase PostgreSQL** como nossa **Fonte da Verdade (System of Record)** e introduzimos o **Firebase Cloud Firestore** como camada Não Relacional Baseada em Documentos operando sob o padrão **Cache-Aside**."*
  - *"Aplicamos a Regra de Ouro de bancos de documentos: **'O que muda junto e é consultado junto, fica junto no mesmo documento'**. Criamos a coleção `mock_exams`, cuja chave determinística $O(1)$ é `contest_{id}_exam_full` (no nosso banco, `contest_4_exam_full`)."*
  - *"Dentro desse único documento JSON autocontido embutimos os metadados da prova, todas as 40 questões já desnormalizadas com o nome da disciplina e os arrays de alternativas pré-estruturados, o controle de versão e o campo `ttl_expires_at` configurado para **24 horas (86.400 segundos)**."*
  - *"Para a consistência, combinamos duas políticas: **Invalidação na Escrita (`DEL`)** — sempre que uma questão é criada, editada ou removida no banco relacional, o repositório dispara um `DELETE` imediato no documento do Firebase Firestore — e o **TTL de 24h** como rede de segurança."*

---

### Bloco 3 — 05:00 a 09:00 | Demonstração ao Vivo (João Vitor Marques Braga)
- **O que mostrar na tela:**
  1. Abrir o navegador no **Console do Supabase** (mostrando a tabela `questions` com as 40 questões reais do concurso) e no **Console do Firebase Firestore** (`rememberflash-persistencia` $\rightarrow$ coleção `mock_exams` $\rightarrow$ documento `contest_4_exam_full`).
  2. Abrir o terminal na raiz do projeto `d:\Rememberflash-persistencia` e executar o comando em Kotlin:
     ```powershell
     .\rodar_benchmark_kotlin.bat
     ```
     *(ou `.\gradlew.bat testDebugUnitTest --tests com.rememberflash.app.BenchmarkComparativoN2Test --rerun-tasks`)*.
- **Fala técnica sugerida:**
  - *"Agora vou demonstrar a execução real ao vivo conectando diretamente na nossa instância do Supabase e no nosso projeto `rememberflash-persistencia` no Firebase Cloud Firestore, usando 100% código Kotlin."*
  - *"Ao rodar o comando, acompanhem as 4 etapas que acontecem em tempo real na nuvem:"*
  - *"Primeiro, o teste executa o **Baseline SQL** no Supabase: são 6 queries HTTP PostgREST por leitura para montar as 40 questões das 4 disciplinas, levando em média **609,26 ms**."*
  - *"Segundo, limpamos o Firebase para forçar a **1ª Leitura (Cache MISS)**: o `MockExamCacheService` consulta o Firebase Firestore, recebe 404 (Miss), busca as 40 questões no Supabase, monta o `MockExamDocument` e grava via `PATCH` na coleção `mock_exams/contest_4_exam_full` do Firebase, levando **1.730,32 ms**."*
  - *"Terceiro, executamos as **Leituras Subsequentes (Cache HIT)**: agora a aplicação busca diretamente o documento único `contest_4_exam_full` no Firebase em **apenas 1 requisição**, caindo para **466,69 ms** na nuvem (e **391,54 ms** na conexão aquecida) e **0,0230 ms** quando atendido pelo cache local L1/L2 do Firestore!"*
  - *"Quarto, provamos ao vivo a **Invalidação na Escrita**: o código faz um `UPDATE` real na questão #1 lá no Supabase, dispara imediatamente o `DELETE` do documento `contest_4_exam_full` no Firebase Firestore (que executa em **241,54 ms**), confirma que a próxima leitura dá **CACHE MISS** (**1.745,61 ms**) reconstruindo o documento com o enunciado atualizado, e a leitura seguinte já retorna **CACHE HIT** (**391,54 ms**) com o dado novo, sem jamais exibir dado defasado! E olhando aqui no navegador do Firebase Console, vemos o documento `contest_4_exam_full` populado com todos os campos e questões."*

---

### Bloco 4 — 09:00 a 11:00 | Apresentação dos Números e Tabela (Maria Eduarda Lira Lemes Braz)
- **O que mostrar na tela:**
  - Abrir o arquivo [`BENCHMARK_REPORT.md`](file:///d:/Rememberflash-persistencia/BENCHMARK_REPORT.md) exibindo a **Ficha de Medição** e as fórmulas de ganho.
- **Fala técnica sugerida:**
  - *"Analisando nossa Ficha de Medição consolidada sob as mesmas condições ($N = 15$ amostras em nuvem para um simulado de 40 questões e 4 disciplinas):"*
  - *"No **Baseline Relacional (Supabase)**, a média foi de **609,26 ms** (mediana de 610,43 ms e P95 de 649,76 ms), exigindo 6 requisições HTTP encadeadas por abertura de prova."*
  - *"No **Cache Miss (1ª leitura)**, o tempo subiu para **1.730,32 ms** (+184% em relação ao Baseline). Tecnicamente, essa sobrecarga ocorre porque o Cache Miss acumula três operações de rede: a verificação inicial no Firebase (404), as 6 consultas no Supabase e a escrita distribuída (`PATCH`) do documento de 40 questões no Cloud Firestore."*
  - *"Em compensação, a partir da 2ª leitura (**Cache Hit**), temos dois níveis de ganho comprovados pela fórmula $\text{Ganho} = \text{Tempo Antes} \div \text{Tempo Depois}$:"*
  - *"1) **Na nuvem pura (Cloud Hit)**, reduzimos 6 queries SQL para 1 única leitura de documento no Firebase: $609,26\text{ ms} \div 466,69\text{ ms} = \mathbf{1,31\times\text{ mais rápido}}$ na média (chegando a **1,56x** no Hit de 391,54 ms) e economizando **83,3% das requisições HTTP**."*
  - *"2) **Com o cache local persistente L1/L2 do Firestore ativado no app**, as leituras subsequentes caem de **609,26 ms** para **0,0230 ms**, entregando um ganho de **26.474 vezes** ($609,26 \div 0,0230$) e eliminando 100% do tráfego repetido de rede durante a resolução da prova."*

---

### Bloco 5 — 11:00 a 12:00 | Trade-offs, Limites e Fechamento (Maria Eduarda Lira Lemes Braz)
- **O que mostrar na tela:**
  - Seção 3 do [`BENCHMARK_REPORT.md`](file:///d:/Rememberflash-persistencia/BENCHMARK_REPORT.md) (Durabilidade, Custos e Limites Operacionais).
- **Fala técnica sugerida:**
  - *"Para encerrar, destacamos os três trade-offs arquiteturais da nossa solução:"*
  - *"Sobre **Durabilidade**: diferente de um cache volátil apenas em RAM, o documento `contest_4_exam_full` fica persistido de forma durável no **Firebase Cloud Firestore** e na camada local L2 em disco. Se o aplicativo Android reiniciar imediatamente, os dados não são perdidos e o simulado continua disponível sem precisar reconstruir a partir do Supabase."*
  - *"Sobre **Custo e Consumo**: o Firebase Firestore cobra por documento lido e não pela quantidade de questões dentro dele. Ao agrupar as 40 questões em um único documento, cada abertura de simulado consome **apenas 1 leitura de documento**, aliviando 83,3% das queries no PostgreSQL."*
  - *"Sobre os **Limites Operacionais (quando NÃO usar)**: essa abordagem de documento desnormalizado não ajudaria em cenários de altíssima mutabilidade por segundo (*write-heavy*, pois causaria invalidação e reconstrução constantes), nem em operações que exigem transações ACID multi-tabela, buscas analíticas *ad-hoc* não indexadas, ou documentos que ultrapassem o limite físico de **1 MiB por documento** imposto pelo Cloud Firestore."*
  - *"Com isso concluímos nossa entrega final do Projeto N2 com persistência poliglota real entre Supabase e Firebase. Muito obrigada!"*
