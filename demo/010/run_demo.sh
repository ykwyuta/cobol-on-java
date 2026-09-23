#!/bin/sh
# cobol-on-java DEMO #010: build COBOL, JCL and PL/I with Maven (standard layout)
set -e
cd "$(dirname "$0")/../.."

echo "=========================================================="
echo " cobol-on-java DEMO #010 (Maven standard layout)"
echo "=========================================================="

command -v java >/dev/null || { echo "[ERROR] java not found (JDK 21+)"; exit 1; }
command -v mvn  >/dev/null || { echo "[ERROR] mvn not found (Maven 3.9+)"; exit 1; }

echo "[1/4] Installing the processor and cobol-maven-plugin..."
mvn -q install -DskipTests

echo "[2/4] Building demo 010 (COBOL + copybook + JCL + PROCLIB, PL/I + %INCLUDE)..."
mvn -q -f demo/010/pom.xml clean package

echo "[3/4] Running SALESJOB.jcl..."
cd demo/010/sales
mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
mkdir -p target/data
java -cp "target/classes:$(cat target/cp.txt)" dev.cobolonjava.job.Main \
    -d target/classes -w target/work -b target/data -p src/main/proclib \
    src/main/jcl/SALESJOB.jcl

echo "[4/4] Running the PL/I program HELLO..."
cd ../greet
mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
java -cp "target/classes:$(cat target/cp.txt)" cobol.generated.HELLO

echo "----------------------------------------------------------"
echo "[SUCCESS] Demo #010 completed successfully."
