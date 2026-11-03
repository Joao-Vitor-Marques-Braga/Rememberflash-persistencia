# Ficha Técnica de Medição e Análise de Arquitetura — Projeto N2 (Semana 4)

**Disciplina:** Tecnologias de Persistência / Bancos de Dados Não Relacionais e Cache  
**Projeto:** RememberFlash — Persistência Poliglota (Supabase PostgreSQL + Firebase Cloud Firestore)  
**Integrantes do Grupo:**
- **Gabriel de Oliveira Silva**
- **João Vitor Marques Braga**
- **Maria Eduarda Lira Lemes Braz**

---

## 1. Ficha de Medição (1 Página — 100% Dados Reais em Nuvem)

### 1.1 Condições Controladas do Teste
Todas as medições (Antes e Depois) foram executadas em **código Kotlin real** ([`BenchmarkComparativoN2Test.kt`](file:///d:/Rememberflash-persistencia/app/src/test/java/com/rememberflash/app/BenchmarkComparativoN2Test.kt)) sob condições controladas idênticas, conectando diretamente aos bancos em nuvem reais do projeto:

| Parâmetro de Controle | Especificação Técnica Utilizada no Benchmark |
| :--- | :--- |
| **Fonte da Verdade Relacional (SQL)** | **Supabase PostgreSQL** (`https://tbnziqgjyxqrkadfhixi.supabase.co`) via PostgREST HTTPS |
| **Camada NoSQL / Cache Rápida** | **Firebase Cloud Firestore** (Projeto: `rememberflash-persistencia`, banco `(default)`) |
| **Coleção e Documento NoSQL** | Coleção `mock_exams` $\rightarrow$ Documento `contest_4_exam_full` (Chave: `mock_exam:contest_4_exam_full`) |
| **Ambiente de Execução** | Windows 11 x64, JDK 21 (JBR), Kotlin 2.0 + OkHttp3/Gson, Gradle Test Runner (`testDebugUnitTest`) |
| **Volume de Dados ($V$)** | **1 Simulado Completo** com **40 questões reais**, **4 disciplinas ativas**, **200 alternativas** e gabarito comentado |
| **Amostragem ($N$)** | **$N = 15$ execuções reais em nuvem** por cenário (mais 5 ciclos completos de Cold Start / Cache Miss) |

---

### 1.2 Tabela Consolidada: Antes (Baseline SQL) vs. Depois (Firebase Cloud Firestore)

| Operação Medida (Nuvem Real) | Média (ms) | Mediana / P50 (ms) | P95 (ms) | Desvio Padrão (ms) | Requisições de Rede | Ganho de Desempenho |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| **1. Baseline Relacional (Supabase SQL)** | **609,26 ms** | **610,43 ms** | **649,76 ms** | 21,84 ms | **6 queries HTTPS** encadeadas (`contests` $\rightarrow$ `disciplines` $\rightarrow$ 4x `questions`) | **1,00x** *(Referência)* |
| **2. 1ª Leitura NoSQL (Cache MISS)** | **1.730,32 ms** | **1.665,05 ms** | **1.741,48 ms** | 84,10 ms | 1 `GET` Firebase (404) + 6 queries Supabase + 1 `PATCH` Firebase | **+184,0%** *(Sobrecarga de carga fria)* |
| **3. Leitura Subsequente Cloud (Cache HIT Remoto)** | **466,69 ms** | **445,95 ms** | **563,30 ms** | 59,22 ms | **1 única leitura `GET`** do documento `contest_4_exam_full` no Firebase | **1,31x mais rápido** (**-23,4%** latência e **-83,3%** queries) |
| **4. Leitura Subsequente Local SDK (Cache HIT L1/L2)** | **0,0230 ms** | **0,0195 ms** | **0,0287 ms** | 0,008 ms | **0 queries de rede** (atendido pela camada persistente local do Firestore) | **26.474,2x mais rápido** (**-99,99%** latência) |

#### Cálculo Formal do Ganho ($\text{Ganho} = \text{Tempo Antes} \div \text{Tempo Depois}$)

1. **Ganho Direto na Nuvem (Supabase 6 Queries Relacionais vs. Firebase Firestore 1 Documento Autocontido):**
   $$\text{Ganho}_{\text{Cloud}} = \frac{\text{Tempo Antes (Baseline Supabase)}}{\text{Tempo Depois (Hit Remoto Firebase)}} = \frac{609,26\text{ ms}}{466,69\text{ ms}} = \mathbf{1,31\times\text{ mais rápido}} \quad (-23,4\%\text{ de latência na nuvem})$$
   *(Na leitura pós-invalidação com conexão aquecida, o Hit remoto no Firebase atingiu **391,54 ms**, representando **1,56x** de ganho direto em rede e redução de **6 queries relacionais para apenas 1 busca por chave**).*

2. **Ganho com Cache Local Persistente do Firestore (L1/L2 Document Store na Sessão do Aluno):**
   $$\text{Ganho}_{\text{SDK Local}} = \frac{\text{Tempo Antes (Baseline Supabase)}}{\text{Tempo Depois (Hit Local L1/L2)}} = \frac{609,26\text{ ms}}{0,0230\text{ ms}} = \mathbf{26.474,2\times\text{ mais rápido}}$$

---

### 1.3 Justificativa Técnica: Por que o Cache Miss é mais lento e como o Hit compensa?
- **Por que o Cache Miss (1.730,32 ms) é mais lento que a leitura original (609,26 ms)?**
  Seguindo o padrão **Cache-Aside**, na primeira leitura (carga fria ou pós-invalidação) a aplicação realiza **três etapas em sequência**:
  1. Consulta o **Firebase Cloud Firestore** buscando o documento `mock_exams/contest_4_exam_full` e recebe `HTTP 404 Not Found` (custo de 1 RTT TLS na nuvem);
  2. Recorre à **Fonte da Verdade Relacional (Supabase PostgreSQL)**, executando as 6 consultas relacionais (`contests`, `disciplines` e `questions` por disciplina) e desserializando as 200 alternativas (`options_json`);
  3. Constrói o documento agregado único ([`MockExamDocument`](file:///d:/Rememberflash-persistencia/app/src/main/java/com/rememberflash/app/domain/model/MockExamDocument.kt)) e executa a gravação (`PATCH`) no **Firebase Cloud Firestore** (~900 ms de serialização e escrita transacional distribuída no Google Cloud).
  Por isso, o **Cache Miss** soma o tempo do banco relacional ao tempo de escrita na nuvem do Firebase (+184,0% de sobrecarga inicial).
- **Como o Cache Hit compensa essa sobrecarga?**
  Em um simulado de concurso, o padrão de acesso é **Read-Heavy (Leitura Intensiva)**: um edital/caderno de prova é montado uma única vez e lido dezenas ou centenas de vezes pelos estudantes. A partir da 2ª leitura (**Cache Hit**):
  - **Na nuvem pura**, 6 queries relacionais no PostgreSQL são substituídas por **1 única busca direta por ID (`O(1)`)** no Firebase Cloud Firestore (**466,69 ms** de média / **391,54 ms** aquecido, economizando **83,3% das requisições HTTP** ao banco relacional).
  - **No dispositivo (L1/L2 Cache do Firestore)**, as leituras subsequentes durante a navegação no simulado caem de **609,26 ms** para **0,0230 ms** (**26.474x mais rápido**), eliminando completamente o gargalo de rede e banco relacional após o *Warm-up*.

---

## 2. Validação ao Vivo de Invalidação na Escrita (Write Invalidation)

Para garantir que o sistema **nunca exiba dados defasados (*stale data*)**, implementamos a **Invalidação na Escrita (`DEL`)** associada ao **TTL de 24 horas** (`86.400.000 ms`):

1. **Evento de Escrita no Supabase:** O teste atualizou ao vivo o enunciado da questão `#1` no **Supabase PostgreSQL** (`PATCH /rest/v1/questions?id=eq.1`).
2. **Invalidação Imediata no Firebase:** O serviço disparou `invalidateContestExam(4L)`, executando um `DELETE` real no **Firebase Cloud Firestore** (`DELETE .../documents/mock_exams/contest_4_exam_full`) concluído em **241,54 ms**.
3. **Reconstrução Automática (Cache Miss):** A leitura imediatamente seguinte retornou status **`MISS`** (**1.745,61 ms**), buscou a questão atualizada no Supabase e gravou a nova versão do documento no Firebase.
4. **Leitura Subsequente Atualizada (Cache Hit):** A leitura seguinte retornou status **`HIT`** (**391,54 ms**) diretamente do Firebase Cloud Firestore já contendo o novo enunciado atualizado, comprovando **zero defasagem de dados**.

---

## 3. Análise de Trade-offs e Arquitetura

### 3.1 Durabilidade (O que acontece se o serviço/app reiniciar imediatamente?)
- **Persistência Não-Volátil na Nuvem e em Disco:** Diferente de um cache puramente em memória RAM volátil, os documentos da coleção `mock_exams` são persistidos no **Firebase Cloud Firestore** (replicação multi-zona do Google Cloud) e também espelhados na camada local L2 em disco (`rememberflash_firestore/mock_exams/contest_4_exam_full.json`).
- **Comportamento no Reinício Imediato:** Se o aplicativo Android for fechado abruptamente (*force stop*) ou o celular reiniciar imediatamente após o *Cache Miss*, apenas a memória RAM volátil (L1) é limpa. Na reabertura do app, o documento **permanece 100% íntegro no Firebase Cloud Firestore e no armazenamento persistente L2**, sem necessidade de reexecutar as 6 consultas relacionais no Supabase (validado pelo teste automatizado `durable document store should survive volatile memory restart` em [`MockExamFirestoreCacheTest.kt`](file:///d:/Rememberflash-persistencia/app/src/test/java/com/rememberflash/app/MockExamFirestoreCacheTest.kt)).

### 3.2 Custo e Consumo (NoSQL vs. Queries Relacionais no Supabase)
- **Redução de Carga no PostgreSQL (Supabase):** No modelo relacional original, cada abertura de simulado de 4 disciplinas dispara **6 requisições HTTP PostgREST** ao Supabase e exige varredura de índices estrangeiros + parsing de 40 colunas texto `options_json`. Com 1.000 alunos abrindo o simulado 5 vezes na semana, seriam **30.000 queries SQL** no Supabase.
- **Eficiência no Firebase Firestore:** No modelo de **Documento Único Autocontido (`contest_4_exam_full`)**, o Firebase cobra por **documento lido** (e não por questão interna). Assim, carregar um simulado inteiro de 40 questões consome **apenas 1 leitura de documento (1 Document Read)** no Firestore (dentro da cota gratuita *Spark Plan* de 50.000 leituras/dia), reduzindo em **83,3%** o número de chamadas de rede na nuvem e em **100%** durante hits locais na sessão.

### 3.3 Limites Operacionais (Quando esta abordagem NÃO ajudaria?)
A camada de documentos desnormalizados com Cache-Aside no Firebase Firestore **NÃO** é indicada nos seguintes cenários:
1. **Alta Mutabilidade em Tempo Real (*Write-Heavy Workloads*):** Se professores ou processos de IA alterassem questões do mesmo concurso a cada segundo, a invalidação constante (`DEL` + reconstrução de 1.730 ms no *Cache Miss*) geraria *cache thrashing* (taxa de *Hit* próxima de 0%), tornando o sistema mais lento que o banco relacional puro.
2. **Consistência Transacional Estrita ACID Cross-Table:** Operações financeiras, controle de créditos/tokens de IA do usuário ou ranking competitivo global com atualização concorrente exigem transações ACID relacionais no PostgreSQL; documentos desnormalizados sofreriam conflitos de contenção (limite de 1 escrita/segundo por documento no Firestore).
3. **Consultas Analíticas Ad-Hoc Não Indexadas (*OLAP*):** Buscas arbitrárias cruzando múltiplos simulados (ex: *"listar todas as questões que contenham a palavra 'improbidade' erradas por mais de 70% dos alunos no último mês"*) não se beneficiam de um documento agregado por concurso, pois exigiriam ler e varrer documentos inteiros na aplicação em vez de usar índices invertidos ou `GROUP BY` no SQL.
4. **Limite Físico de 1 MiB por Documento no Firestore:** O Cloud Firestore impõe o limite rígido de **1 MiB (1.048.576 bytes)** por documento. Para simulados padrão de concursos (40 a 120 questões de texto, ocupando ~35 KB a ~120 KB), o documento único é ideal; porém, caso um banco de questões único ultrapassasse 2.000 questões com imagens em Base64 embutidas, o documento excederia 1 MiB e precisaria ser particionado em subcoleções (`mock_exams/{id}/batches/{batch_id}`).
