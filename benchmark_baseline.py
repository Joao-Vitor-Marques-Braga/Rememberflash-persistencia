#!/usr/bin/env python3
"""
=============================================================================
PROJETO N2 — SEMANA 1: DIAGNÓSTICO E BASELINE
DISCIPLINA: ESW441 · Turma A · Prof. Gustavo Martins Lima (UniRV)
SISTEMA: RememberFlash (TFC)
FUNCIONALIDADE: Persistência, recuperação e refação de Provas e Questões de Treino
=============================================================================
Script automatizado para medição do baseline ("antes") da operação que dói:
'Montar e recuperar prova simulada completa com questões, gabaritos e histórico para refação'
=============================================================================
"""

import sqlite3
import json
import time
import os
import platform
import statistics

DB_FILE = "rememberflash_baseline.db"

if os.path.exists(DB_FILE):
    os.remove(DB_FILE)

conn = sqlite3.connect(DB_FILE)
cursor = conn.cursor()

# 1. Criação do Schema idêntico ao Room (SQLite) do RememberFlash
cursor.executescript("""
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS contests (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    user_id TEXT NOT NULL,
    title TEXT NOT NULL,
    description TEXT NOT NULL,
    organizer_name TEXT NOT NULL,
    question_type TEXT NOT NULL,
    syllabus_pdf_uri TEXT,
    exam_date_str TEXT,
    exam_location TEXT,
    allowed_pen TEXT,
    allowed_items TEXT,
    prohibited_items TEXT,
    ai_difficulty TEXT NOT NULL,
    ai_rigor TEXT NOT NULL,
    ai_tone TEXT NOT NULL,
    is_active INTEGER NOT NULL DEFAULT 1,
    is_synced INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS disciplines (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    contest_id INTEGER NOT NULL,
    name TEXT NOT NULL,
    weight REAL NOT NULL,
    total_topics INTEGER NOT NULL,
    is_active INTEGER NOT NULL DEFAULT 1,
    is_synced INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY(contest_id) REFERENCES contests(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS index_disciplines_contest_id ON disciplines(contest_id);

CREATE TABLE IF NOT EXISTS topics (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    discipline_id INTEGER NOT NULL,
    contest_id INTEGER NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    is_completed INTEGER NOT NULL DEFAULT 0,
    order_index INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    is_synced INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY(discipline_id) REFERENCES disciplines(id) ON DELETE CASCADE,
    FOREIGN KEY(contest_id) REFERENCES contests(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS index_topics_discipline_id ON topics(discipline_id);
CREATE INDEX IF NOT EXISTS index_topics_contest_id ON topics(contest_id);

CREATE TABLE IF NOT EXISTS questions (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    discipline_id INTEGER NOT NULL,
    topic_id INTEGER,
    statement TEXT NOT NULL,
    options_json TEXT NOT NULL,
    correct_index INTEGER NOT NULL,
    explanation TEXT,
    source TEXT NOT NULL,
    chosen_option INTEGER,
    is_correct INTEGER,
    answered_at INTEGER,
    tokens_spent INTEGER NOT NULL,
    is_synced INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    FOREIGN KEY(discipline_id) REFERENCES disciplines(id) ON DELETE CASCADE,
    FOREIGN KEY(topic_id) REFERENCES topics(id) ON DELETE SET NULL
);
CREATE INDEX IF NOT EXISTS index_questions_discipline_id ON questions(discipline_id);
CREATE INDEX IF NOT EXISTS index_questions_topic_id ON questions(topic_id);

CREATE TABLE IF NOT EXISTS mock_exam_attempts (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    contest_id INTEGER,
    discipline_id INTEGER,
    score INTEGER NOT NULL,
    total_questions INTEGER NOT NULL,
    answers_json TEXT NOT NULL,
    times_json TEXT,
    created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS index_attempts_contest_id ON mock_exam_attempts(contest_id);
""")

# 2. Carga de Dados Realistas (Simulação do Banco em Produção)
now = int(time.time() * 1000)

# Inserção de 5 Concursos Públicos
for c in range(1, 6):
    cursor.execute("""
        INSERT INTO contests (user_id, title, description, organizer_name, question_type, ai_difficulty, ai_rigor, ai_tone, is_active, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
    """, (f"usr_rf_{c}", f"Concurso Público Nacional Unificado #{c}", "Auditor Fiscal e Analista Tributário", "FGV", "Múltipla Escolha", "Difícil", "Rigoroso", "Formal", now))

target_contest_id = 1

# Inserção de 10 disciplinas por concurso (50 disciplinas no total)
for c in range(1, 6):
    for d in range(1, 11):
        cursor.execute("""
            INSERT INTO disciplines (contest_id, name, weight, total_topics, is_active)
            VALUES (?, ?, ?, ?, 1)
        """, (c, f"Disciplina {d} do Concurso {c}", 2.0, 5))

# Opções de exemplo com texto realista de concurso público
options_payload = json.dumps([
    "A) A competência tributária é indelegável, salvo atribuição das funções de arrecadar ou fiscalizar tributos.",
    "B) É permitida a concessão de anistia fiscal por decreto autônomo do Chefe do Poder Executivo estadual.",
    "C) A imunidade recíproca impede a tributação de taxas municipais sobre autarquias federais executoras de serviços.",
    "D) O princípio da anterioridade nonagesimal não se aplica às alíquotas do Imposto sobre Produtos Industrializados (IPI).",
    "E) A decadência tributária pode ter seu prazo interrompido por qualquer notificação extrajudicial do contribuinte."
])

# Inserindo 1.000 questões (20 questões por disciplina = 200 questões no concurso alvo)
for disc_id in range(1, 51):
    for q in range(1, 21):
        cursor.execute("""
            INSERT INTO questions (discipline_id, statement, options_json, correct_index, explanation, source, tokens_spent, created_at)
            VALUES (?, ?, ?, ?, ?, 'GEMINI_AI', 420, ?)
        """, (
            disc_id,
            f"Questão {q} (Disciplina {disc_id}): No tocante ao Direito Constitucional e Tributário, avalie as assertivas à luz da jurisprudência consolidada dos Tribunais Superiores.",
            options_payload,
            q % 5,
            "Justificativa da banca: Conforme art. 150 da CF/88 e entendimento pacificado da Corte Constitucional.",
            now + q
        ))

# Inserindo histórico de 50 tentativas de simulado
for att in range(1, 51):
    answers_dict = {str(qid): (qid % 5) for qid in range(1, 51)}
    times_dict = {str(qid): 52 for qid in range(1, 51)}
    cursor.execute("""
        INSERT INTO mock_exam_attempts (contest_id, score, total_questions, answers_json, times_json, created_at)
        VALUES (?, ?, 50, ?, ?, ?)
    """, (
        (att % 5) + 1,
        38 + (att % 12),
        json.dumps(answers_dict),
        json.dumps(times_dict),
        now - (att * 3600000)
    ))

conn.commit()

# Métricas de Volume dos Dados
db_size_bytes = os.path.getsize(DB_FILE)
db_size_kb = db_size_bytes / 1024
cursor.execute("SELECT COUNT(*) FROM questions")
total_questions = cursor.fetchone()[0]
cursor.execute("SELECT COUNT(*) FROM mock_exam_attempts")
total_attempts = cursor.fetchone()[0]
cursor.execute("SELECT COUNT(*) FROM disciplines WHERE contest_id = ?", (target_contest_id,))
disciplines_target = cursor.fetchone()[0]
cursor.execute("""
    SELECT COUNT(*) FROM questions 
    WHERE discipline_id IN (SELECT id FROM disciplines WHERE contest_id = ?)
""", (target_contest_id,))
questions_target = cursor.fetchone()[0]

print("=" * 70)
print("RELATÓRIO DE CONFIGURAÇÃO DO AMBIENTE E DADOS")
print("=" * 70)
print(f"Banco de Dados (SQLite): {db_size_kb:.2f} KB ({db_size_bytes:,} bytes)")
print(f"Total de Questões no Banco: {total_questions}")
print(f"Total de Tentativas no Histórico: {total_attempts}")
print(f"Concurso Alvo ID {target_contest_id}: {disciplines_target} disciplinas | {questions_target} questões")
print("=" * 70)

# 3. A OPERAÇÃO QUE DÓI:
# Fluxo real executado no ViewModel do RememberFlash para refazer simulado:
# - Subquery de UPDATE de reset de respostas
# - Consulta do concurso
# - Consulta de todas as disciplinas
# - Consulta e unificação das questões por disciplina
# - JSON decode de opções de cada questão
# - Consulta da última tentativa e JSON decode do gabarito/tempos
def operacaoQueDoi(contest_id):
    # Passo 1: Reset relacional com subquery
    cursor.execute("""
        UPDATE questions SET chosen_option = NULL, is_correct = NULL, answered_at = NULL 
        WHERE discipline_id IN (SELECT id FROM disciplines WHERE contest_id = ?)
    """, (contest_id,))
    
    # Passo 2: Busca concurso
    cursor.execute("SELECT id, title, question_type, ai_difficulty FROM contests WHERE id = ?", (contest_id,))
    contest = cursor.fetchone()
    
    # Passo 3: Busca disciplinas do concurso
    cursor.execute("SELECT id, name, weight FROM disciplines WHERE contest_id = ? AND is_active = 1", (contest_id,))
    disciplines = cursor.fetchall()
    
    # Passo 4: Busca de questões e parsing individual de alternativas
    exam_questions = []
    for disc in disciplines:
        disc_id, disc_name, disc_weight = disc
        cursor.execute("""
            SELECT id, statement, options_json, correct_index, explanation, created_at 
            FROM questions 
            WHERE discipline_id = ? 
            ORDER BY created_at DESC
        """, (disc_id,))
        rows = cursor.fetchall()
        for r in rows:
            parsed_options = json.loads(r[2])
            exam_questions.append({
                "id": r[0],
                "discipline": disc_name,
                "statement": r[1],
                "options": parsed_options,
                "correct_index": r[3],
                "explanation": r[4],
                "created_at": r[5]
            })
            
    # Passo 5: Busca da última tentativa e parsing de histórico para refação
    cursor.execute("""
        SELECT id, score, total_questions, answers_json, times_json 
        FROM mock_exam_attempts 
        WHERE contest_id = ? 
        ORDER BY created_at DESC 
        LIMIT 1
    """, (contest_id,))
    attempt = cursor.fetchone()
    if attempt:
        answers = json.loads(attempt[3])
        times = json.loads(attempt[4])
        
    return exam_questions

# 4. EXECUÇÃO DO BENCHMARK (N = 1.000 repetições)
N = 1000
print(f"\nIniciando execução cronometrada da 'operação que dói' com N = {N} repetições...")

tempos_individuais_ms = []

inicio_global = time.perf_counter()
for i in range(N):
    t_i = time.perf_counter()
    operacaoQueDoi(target_contest_id)
    t_f = time.perf_counter()
    tempos_individuais_ms.append((t_f - t_i) * 1000)
fim_global = time.perf_counter()

tempo_total_segundos = fim_global - inicio_global
tempo_total_ms = tempo_total_segundos * 1000
media_ms = tempo_total_ms / N
mediana_ms = statistics.median(tempos_individuais_ms)
desvio_padrao_ms = statistics.stdev(tempos_individuais_ms)
min_ms = min(tempos_individuais_ms)
max_ms = max(tempos_individuais_ms)

# Hardware specs
uname = platform.uname()
try:
    with open("/proc/cpuinfo") as f:
        cpu_info = [line.split(":")[1].strip() for line in f if "model name" in line]
        cpu_name = cpu_info[0] if cpu_info else uname.processor
except Exception:
    cpu_name = uname.processor

try:
    with open("/proc/meminfo") as f:
        mem_info = [line.split(":")[1].strip() for line in f if "MemTotal" in line]
        mem_total = mem_info[0] if mem_info else "Desconhecido"
except Exception:
    mem_total = "Desconhecido"

print("\n" + "=" * 70)
print("RESULTADOS DO BASELINE (PROVANDO COM NÚMEROS)")
print("=" * 70)
print(f"Operação Avaliada       : Montar e carregar prova/simulado completo para refazer")
print(f"Número de Repetições (N): {N:,}")
print(f"Tempo Total Cronometrado: {tempo_total_ms:.2f} ms ({tempo_total_segundos:.4f} s)")
print(f"Tempo Médio ('Antes')   : {media_ms:.4f} ms por operação")
print(f"Mediana                 : {mediana_ms:.4f} ms")
print(f"Mínimo / Máximo         : {min_ms:.4f} ms / {max_ms:.4f} ms")
print(f"Desvio Padrão           : {desvio_padrao_ms:.4f} ms")
print("-" * 70)
print("ESPECIFICAÇÕES DA MÁQUINA DE TESTES")
print("-" * 70)
print(f"Processador (CPU)       : {cpu_name}")
print(f"Memória RAM Total       : {mem_total}")
print(f"Sistema Operacional     : {uname.system} {uname.release} ({uname.machine})")
print(f"Ambiente de Execução    : Python {platform.python_version()} | SQLite {sqlite3.sqlite_version}")
print("=" * 70)

conn.close()
if os.path.exists(DB_FILE):
    os.remove(DB_FILE)
