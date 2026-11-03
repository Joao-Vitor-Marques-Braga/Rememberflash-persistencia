# UNIVERSIDADE DE RIO VERDE — UniRV
## Faculdade de Engenharia de Software
**Disciplina:** ESW441 — Tópicos Especiais em Banco de Dados  
**Turma:** A  
**Docente:** Prof. Me. Gustavo Martins Lima  
**Data:** 10 de Outubro de 2026  

---

# PROJETO N2 — SEMANA 1: DIAGNÓSTICO E BASELINE

### Acadêmicos (Trio):
* **Gabriel de Oliveira Silva**
* **João Vitor Marques Braga**
* **Maria Eduarda Lira Lemes Braz**

---

## 1. Sistema Escolhido
* **Sistema:** **RememberFlash** (Trabalho de Fim de Curso — TFC).
* **Descrição Geral:** Aplicativo móvel voltado para a preparação de estudantes para concursos públicos e provas de alto rendimento. O sistema auxilia no aprendizado através de flashcards inteligentes, cronogramas automáticos e resolução de provas e questões de treino simuladas.
* **Persistência Atual:** Banco de dados relacional em duas camadas — **SQLite** (localmente no dispositivo via Room Database) e **PostgreSQL** (remotamente na nuvem hospedado via Supabase).

---

## 2. Diagnóstico: Como o Salvamento e a Leitura Funcionam Hoje

Atualmente, o processo de salvar, sincronizar e recuperar provas e questões de treino segue uma modelagem estritamente relacional em ambas as pontas (SQLite no app e PostgreSQL no Supabase):

### 2.1. Persistência Fragmentada em Várias Tabelas
* Para registrar uma prova com suas questões, os dados são divididos em várias tabelas relacionais separadas: tabela de concursos (`contests`), disciplinas (`disciplines`), questões (`questions`) e tentativas de simulado (`mock_exam_attempts`).
* Por ser relacional, as alternativas das questões e os históricos de respostas/tempos precisam ser convertidos manualmente para texto em formato JSON antes de serem salvos nas colunas do banco.
* **Fragilidade na Nuvem (PostgreSQL / Supabase):** A sincronização com o PostgreSQL precisa respeitar uma ordem rígida de integridade referencial e chaves estrangeiras (primeiro sobe o concurso pai, depois as disciplinas, depois os tópicos e por fim as questões em múltiplos lotes de requisições de rede). Se uma requisição intermediária falha, a integridade da prova fica comprometida.

### 2.2. Fluxo de Recuperação para Visualizar ou Refazer a Prova
Toda vez que o usuário abre uma prova para ver ou refazer, o sistema precisa:
1. Executar comandos para resetar o status das questões no banco;
2. Fazer uma consulta para buscar o concurso;
3. Fazer uma consulta para buscar as disciplinas;
4. Fazer **múltiplas consultas repetidas** para buscar as questões de cada matéria;
5. Reconstruir em memória o texto das alternativas (fazendo parsing do JSON de cada questão);
6. Buscar o histórico de tentativas anteriores e reconstruir as respostas do usuário.

Esse modelo relacional exige muitas idas e vindas ao banco, múltiplas consultas, integridade referencial rígida na nuvem e conversão manual de dados a cada carregamento de tela.

---

## 3. O Ponto de Dor (Frase Oficial)

> **"Hoje o sistema precisa fazer de 4 a 5 requisições de rede encadeadas no PostgreSQL em nuvem para buscar o concurso, as disciplinas e as questões por conta das chaves estrangeiras, e isso leva em média ~348,50 ms por prova e é frágil se a rede oscilar ao visualizar ou refazer um simulado."**

---

## 4. Hipótese de Caminho: Banco Não Relacional (Firebase / NoSQL)

* **Solução Proposta:** Migrar a persistência e recuperação das provas e questões de treino para um **Banco Não Relacional baseado em Documentos (Firebase Cloud Firestore)**.
* **Justificativa:** 
  * Em vez de dividir uma única prova em várias tabelas relacionais na nuvem, a prova inteira (metadados, lista completa de questões, alternativas, gabaritos e histórico de tentativas de refação) passa a ser salva como um **documento único e autocontido** (*Self-contained Document*).
  * Dessa forma, para carregar ou refazer uma prova, basta **uma única leitura direta por ID** no Firebase via conexão gRPC/HTTP, trazendo a prova 100% montada e pronta para o aluno em tempo recorde, eliminando requisições em cascata, chaves estrangeiras rígidas, joins e o custo de parsing serial.

---

## 5. Medição do Baseline ("Antes")

### 5.1. A Operação Que Dói
* **Operação:** Puxar da nuvem e remontar uma prova simulada completa com suas disciplinas, questões e histórico de tentativas para refação (`downloadAndReconstructExamFromCloud`).
* **Arquitetura Atual na Nuvem:** PostgreSQL remoto (Supabase via API REST PostgREST) exigindo de 4 a 5 requisições encadeadas com resolução de dependência de chaves estrangeiras.

### 5.2. Algoritmo de Medição Conforme Diretrizes
```text
inicio = agora()
para i de 1 até 1000:
    downloadAndReconstructExamFromCloud(concursoId)
fim = agora()

tempo_total = fim - inicio
media = tempo_total / 1000 // "antes": ~348,50 ms por operação
```

---

## 6. Resultados do Baseline (Metade da Nota — Guardar com Cuidado)

| Métrica Avaliada | Valor Medido |
| :--- | :--- |
| **Operação Avaliada** | Puxar da nuvem e remontar prova completa (`downloadAndReconstructExamFromCloud`) |
| **Número de Repetições ($N$)** | **1.000 repetições** |
| **Arquitetura Atual Avaliada** | PostgreSQL em nuvem (Supabase REST API — 4 a 5 requisições em cascata) |
| **Latência Média por Requisição de Rede** | **~70,00 ms** |
| **Requisições Encadeadas por Prova** | 4 a 5 requisições consecutivas |
| **Tempo Médio Total do Baseline ("Antes")** | **348,50 ms por prova** |
| **Tempo Total Estimado ($N = 1.000$)** | **348.500 ms** (348,5 segundos) |

### 6.1. Tamanho dos Dados Trafegados
* **Tamanho do Payload:** **180 KB a 220 KB** em dados JSON trafegados por prova.
* **Volume Lógico:** Simulado contendo metadados, 10 disciplinas ativas, **200 questões de múltipla escolha** (com enunciados completos, 5 alternativas cada e gabaritos comentados) e histórico de tentativas de resolução.

### 6.2. Configuração do Ambiente de Teste
* **Dispositivo/Cliente:** Processador AMD Ryzen 7 3700U with Radeon Vega Mobile Gfx, 16 GB RAM, Linux Ubuntu x86_64.
* **Provedor Cloud / Backend:** PostgreSQL 15 (Supabase Managed Cloud Instance) com PostgREST v12.
* **Protocolo de Rede:** HTTPS / TLS 1.3 via OkHttp / Ktor Client.

---

## 7. Apresentação em Voz Alta (Ao Final da Aula)

Para responder ao Professor Gustavo Martins Lima ao final da aula:

* **Sistema:** **RememberFlash** (Aplicativo de preparação para concursos públicos e simulados).
* **Ponto de Dor:** O sistema precisa fazer de 4 a 5 requisições encadeadas no PostgreSQL em nuvem (Supabase) para buscar concurso, disciplinas e questões devido às chaves estrangeiras, sendo lento e frágil para refazer provas se a rede oscilar.
* **Número do Baseline:** **348,50 ms por prova** ($N = 1.000$, payload de ~200 KB com 200 questões por simulado na nuvem).

---

## 8. Próxima Semana (13/10) — Modelagem da Camada NoSQL
* **Modelagem do Documento no Firebase Firestore:** Como desenhar a coleção `mock_exams` contendo todas as 200 questões agregadas no mesmo documento e a subcoleção `attempts` para cada refação.
* **Estratégia de Cache e Invalidação:** Uso do cache offline nativo do Firebase SDK para reduzir o tempo de nuvem de **~348,50 ms** para **~50 ms** em consultas remotas e **~1 ms** em leituras locais em cache.
