#!/bin/sh
# cobol-on-java DEMO #009 (BMS + COBOL + H2 TODO list)
# Pass --demo to replay a scripted session instead of typing commands.
set -e
cd "$(dirname "$0")/../.."

echo "=========================================================="
echo " cobol-on-java DEMO #009 (BMS + COBOL + H2 TODO list)"
echo "=========================================================="

command -v java >/dev/null || { echo "[ERROR] java not found (JDK 21+)"; exit 1; }
command -v mvn  >/dev/null || { echo "[ERROR] mvn not found (Maven 3.9+)"; exit 1; }

echo "[1/3] Compiling the COBOL program (TODOAPP.cbl)..."
echo "      -I demo/009 makes COPY TODOSET read TODOSET.bms and build"
echo "      the symbolic map; DFHAID and SQLCA come from the compiler."
mkdir -p demo/009/bin
mvn -q exec:java -pl cobol-compiler \
    "-Dexec.mainClass=dev.cobolonjava.compiler.Main" \
    "-Dexec.args=-d demo/009/bin -I demo/009 demo/009/TODOAPP.cbl"

echo "[2/3] Compiling the terminal (TodoTerminal.java)..."
M2_REPO="${HOME}/.m2/repository"
DEMO_CP="cobol-cics/target/classes:cobol-runtime/target/classes"
DEMO_CP="${DEMO_CP}:cobol-db2/target/classes"
DEMO_CP="${DEMO_CP}:cobol-spring-boot-4-autoconfigure/target/classes"
DEMO_CP="${DEMO_CP}:${M2_REPO}/org/springframework/spring-jdbc/7.0.9/spring-jdbc-7.0.9.jar"
DEMO_CP="${DEMO_CP}:${M2_REPO}/org/springframework/spring-tx/7.0.9/spring-tx-7.0.9.jar"
DEMO_CP="${DEMO_CP}:${M2_REPO}/org/springframework/spring-beans/7.0.9/spring-beans-7.0.9.jar"
DEMO_CP="${DEMO_CP}:${M2_REPO}/org/springframework/spring-core/7.0.9/spring-core-7.0.9.jar"
DEMO_CP="${DEMO_CP}:${M2_REPO}/commons-logging/commons-logging/1.3.6/commons-logging-1.3.6.jar"
DEMO_CP="${DEMO_CP}:${M2_REPO}/com/h2database/h2/2.4.240/h2-2.4.240.jar"
javac -cp "${DEMO_CP}" -d demo/009/bin demo/009/TodoTerminal.java

echo "[3/3] Starting the 3270 style terminal..."
echo "----------------------------------------------------------"
exec java -cp "demo/009/bin:${DEMO_CP}" demo.TodoTerminal "$@"
