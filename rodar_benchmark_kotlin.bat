@echo off
chcp 65001 >nul
echo Executando Benchmark Comparativo N2 em KOTLIN (QuestionRepositoryImpl + MockExamCacheService + FirestoreMockExamDataSource)...
call .\gradlew.bat testDebugUnitTest --tests "com.rememberflash.app.BenchmarkComparativoN2Test" --rerun-tasks

