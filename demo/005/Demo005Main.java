package demo;

import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.interop.CobolCallResult;
import dev.cobolonjava.runtime.interop.CobolRuntime;
import dev.cobolonjava.runtime.interop.CobolSession;
import dev.cobolonjava.runtime.interop.ProgramCatalog;
import dev.cobolonjava.runtime.storage.Storage;
import java.nio.charset.StandardCharsets;

/**
 * Java 側メインアプリケーション。
 * Java から COBOL プログラム (ORDER-PROCESS) を呼び出し、
 * かつ COBOL 内からの CALL (FXSERVICE) を Java 実装でハンドリングするデモ。
 */
public class Demo005Main {

    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println(" [Java] cobol-on-java DEMO #005 (Java <-> COBOL)  ");
        System.out.println("==================================================");

        // 1. ProgramCatalog の構築
        // COBOL プログラムと、COBOL から呼び出される Java サービス (FXSERVICE) を登録
        ProgramCatalog catalog = ProgramCatalog.builder()
                .revision("demo-005-r1")
                // COBOL 側: cobol.generated.ORDER_PROCESS を登録
                .cobolProgram("ORDER-PROCESS", () -> {
                    try {
                        Class<?> clazz = Class.forName("cobol.generated.ORDER_PROCESS");
                        return (dev.cobolonjava.runtime.program.CobolProgram)
                                clazz.getDeclaredConstructor().newInstance();
                    } catch (ReflectiveOperationException e) {
                        throw new RuntimeException("Failed to load ORDER_PROCESS", e);
                    }
                })
                // Java サービス: 為替レート照会 (1 USD = 150.00 JPY)
                .javaProgram("FXSERVICE", () -> (context, arguments) -> {
                    // arguments[0]: WS-CURRENCY (PIC X(3))
                    // arguments[1]: WS-EXCHANGE-RATE (PIC 9(3)V99 -> 5 bytes zoned, e.g. "15000")
                    String currency = CodePages.IBM_1047.decode(arguments.get(0).toByteArray());
                    System.out.println("  [Java Callback] FXSERVICE invoked for currency: '" + currency + "'");

                    // 150.00 (ゾーン10進数: '1','5','0','0','0') を EBCDIC でセット
                    byte[] rateBytes = CodePages.IBM_1047.encode("15000");
                    arguments.get(1).setBytes(rateBytes);
                    System.out.println("  [Java Callback] Returning rate: 150.00 to COBOL");
                })
                .build();

        CobolRuntime runtime = CobolRuntime.builder(catalog).build();

        // 2. 引数ストレージの準備 (LINKAGE SECTION の各 01 項目)
        // 01 LNK-ORDER-ID   PIC X(6)     -> 6 bytes
        // 01 LNK-AMOUNT-JPY PIC 9(6)     -> 6 bytes (例: 030000 -> 30,000円)
        // 01 LNK-AMOUNT-USD PIC 9(6)V99  -> 8 bytes (計算結果受取用)
        // 01 LNK-STATUS     PIC X(2)     -> 2 bytes (結果ステータス受取用)
        Storage orderIdStorage = Storage.copyOf(CodePages.IBM_1047.encode("ORD100"));
        Storage amountJpyStorage = Storage.copyOf(CodePages.IBM_1047.encode("030000"));
        Storage amountUsdStorage = Storage.allocate(8);
        Storage statusStorage = Storage.allocate(2);

        System.out.println("[Java] Calling COBOL 'ORDER-PROCESS' from Java...");
        System.out.println("[Java] Input Order ID  : ORD100");
        System.out.println("[Java] Input Amount JPY: 30,000");

        // 3. CobolSession を通じた COBOL プログラムの呼び出し
        try (CobolSession session = runtime.openSession()) {
            CobolCallResult result = session.call(
                    "ORDER-PROCESS",
                    orderIdStorage.whole(),
                    amountJpyStorage.whole(),
                    amountUsdStorage.whole(),
                    statusStorage.whole()
            );

            System.out.println("[Java] COBOL Execution Finished. Return Code: " + result.returnCode());
        }

        // 4. Java 側で結果の確認（Storage はゼロコピーで COBOL 側に変更されている）
        String returnStatus = CodePages.IBM_1047.decode(statusStorage.array());
        String usdRaw = CodePages.IBM_1047.decode(amountUsdStorage.array());
        // usdRaw は "00020000" (200.00 ドル)
        String usdFormatted = usdRaw.substring(0, 6) + "." + usdRaw.substring(6);

        System.out.println("==================================================");
        System.out.println("[Java] Final Result in Java Application:");
        System.out.println("  - Status     : " + returnStatus);
        System.out.println("  - Amount USD : " + usdFormatted + " USD");
        System.out.println("==================================================");
    }
}
