#!/bin/sh
# cobol-on-java DEMO #009 WEB (Spring Boot + Thymeleaf)
# Same TODOAPP.cbl and TODOSET.bms as run_demo.sh, shown in a browser.
set -e
cd "$(dirname "$0")/../.."

echo "=========================================================="
echo " cobol-on-java DEMO #009 WEB (Spring Boot + Thymeleaf)"
echo "=========================================================="

command -v java >/dev/null || { echo "[ERROR] java not found (JDK 21+)"; exit 1; }
command -v mvn  >/dev/null || { echo "[ERROR] mvn not found (Maven 3.9+)"; exit 1; }

echo "[1/2] Compiling the COBOL program (TODOAPP.cbl)..."
mkdir -p demo/009/bin
mvn -q exec:java -pl cobol-compiler \
    "-Dexec.mainClass=dev.cobolonjava.compiler.Main" \
    "-Dexec.args=-d demo/009/bin -I demo/009 demo/009/TODOAPP.cbl"

echo "[2/2] Starting Spring Boot..."
echo "----------------------------------------------------------"
echo " Open   http://localhost:8080/"
echo " Login  demo / demo"
echo " Stop   Ctrl+C"
echo "----------------------------------------------------------"
exec mvn -q -f demo/009/web/pom.xml spring-boot:run
