#!/bin/sh
# cobol-on-java DEMO #011 (a 3270 terminal over TN3270, without Spring)
# Pass --s3270 to replay a scripted session through s3270 instead of
# waiting for an emulator (x3270 / c3270 / wc3270) on localhost:3270.
set -e
cd "$(dirname "$0")/../.."

port=${COJ_TN3270_PORT:-3270}

echo "=========================================================="
echo " cobol-on-java DEMO #011 (3270 terminal over TN3270)"
echo "=========================================================="

command -v java >/dev/null || { echo "[ERROR] java not found (JDK 21+)"; exit 1; }
command -v mvn  >/dev/null || { echo "[ERROR] mvn not found (Maven 3.9+)"; exit 1; }

echo "[1/3] Building the processor (cobol-runtime, cobol-cics, cobol-compiler)..."
mvn -q install -pl cobol-compiler -am -DskipTests

echo "[2/3] Compiling the COBOL program (GREET.cbl)..."
echo "      -I demo/011 makes COPY GRTSET read GRTSET.bms."
rm -rf demo/011/bin
mvn -q exec:java -pl cobol-compiler \
    "-Dexec.mainClass=dev.cobolonjava.compiler.Main" \
    "-Dexec.args=-d demo/011/bin -I demo/011 demo/011/GREET.cbl"

# The region needs only the runtime and CICS; no Spring, no servlet.
RUN_CP="demo/011/bin:cobol-cics/target/classes:cobol-runtime/target/classes"

echo "[3/3] Starting the region (APPLID COBOLJVM) on localhost:$port..."
if [ "${1:-}" != "--s3270" ]; then
    echo "      Connect a 3270 emulator, e.g. 'c3270 localhost:$port',"
    echo "      clear the screen, type GRT1 and press Enter. Ctrl-C stops."
    echo "----------------------------------------------------------"
    exec java -cp "$RUN_CP" dev.cobolonjava.cics.tn3270.Tn3270Main \
        --bms demo/011/GRTSET.bms --transaction GRT1=GREET --port "$port"
fi

command -v s3270 >/dev/null || { echo "[ERROR] s3270 not found (apt-get install s3270)"; exit 1; }
java -cp "$RUN_CP" dev.cobolonjava.cics.tn3270.Tn3270Main \
    --bms demo/011/GRTSET.bms --transaction GRT1=GREET --port "$port" &
region=$!
trap 'kill $region 2>/dev/null || true' EXIT
sleep 2
echo "----------------------------------------------------------"
s3270 -model 3279-2-E <<EOS | sed -n 's/^data: //p'
Connect(COJLU001@localhost:$port)
Wait(10,Unlock)
String("GRT1")
Enter()
Wait(10,Output)
Ascii(0,0,8,80)
String("WORLD")
Enter()
Wait(10,Output)
Ascii(0,0,8,80)
PF(3)
Wait(10,Output)
Ascii(0,0,1,80)
Quit()
EOS
echo "----------------------------------------------------------"
echo "[SUCCESS] Demo #011 completed successfully."
