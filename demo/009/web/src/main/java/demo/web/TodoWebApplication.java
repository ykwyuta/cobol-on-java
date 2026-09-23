package demo.web;

import dev.cobolonjava.cics.CicsEnvironment;
import dev.cobolonjava.cics.CicsTaskProgramPort;
import dev.cobolonjava.cics.CicsTransactionDefinition;
import dev.cobolonjava.cics.CicsTransactionRegistry;
import dev.cobolonjava.cics.CobolCicsTaskProgram;
import dev.cobolonjava.cics.TransId;
import dev.cobolonjava.cics.bms.BmsMapsetCatalog;
import dev.cobolonjava.cics.bms.BmsParser;
import dev.cobolonjava.runtime.interop.CobolRuntime;
import dev.cobolonjava.runtime.interop.ProgramCatalog;
import dev.cobolonjava.runtime.interop.ProgramId;
import dev.cobolonjava.runtime.program.CobolProgram;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * デモ #009 の Todo アプリを、ブラウザの 3270 端末で動かす。
 *
 * <p>端末版 ({@code TodoTerminal}) と同じ {@code TODOAPP.cbl} と {@code TODOSET.bms} を、手を加えずに使う。
 * ここに置くのは transaction の登録と program の実行の 2 つの bean だけである。残りは処理系の自動構成が作る。
 *
 * <ul>
 *   <li>画面の描画と入力の受け取り: {@code cobol-spring-boot-4-bms-thymeleaf} ({@code /cics/TODO})</li>
 *   <li>疑似会話と Db2 の UOW: {@code cobol.cics.conversation.consistency=strict}。会話と業務の SQL が
 *       同じ H2 の同じ UOW で確定する (設計 77 §4.6)。端末版が手で書いた task 境界に当たる</li>
 *   <li>ログイン: {@code cobol.cics.security.mode=demo} (設計 84)。ブラウザの入口は認証と CSRF が無ければ開かない</li>
 * </ul>
 */
@SpringBootApplication
public class TodoWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(TodoWebApplication.class, args);
    }

    /** TODO transaction。端末版と同じ定義である。 */
    @Bean
    CicsTransactionRegistry todoTransactions() {
        return new CicsTransactionRegistry(List.of(new CicsTransactionDefinition(
                TransId.of("TODO"), ProgramId.of("TODOAPP"), Duration.ofSeconds(30), 32, 0, 0, 0, true)));
    }

    /**
     * 翻訳した TODOAPP を CICS の task として動かす。
     *
     * <p>BMS の原文は classpath に載せたものを実行時に読む。翻訳時に記号マップを作ったのと同じ原文なので、
     * 写し句と mapset がずれない。
     */
    @Bean
    CicsTaskProgramPort todoProgram() throws IOException {
        String source;
        try (InputStream in = TodoWebApplication.class.getResourceAsStream("/TODOSET.bms")) {
            if (in == null) {
                throw new IllegalStateException("TODOSET.bms is not on the classpath");
            }
            source = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
        CicsEnvironment environment = CicsEnvironment.unconfigured()
                .withMapsets(BmsMapsetCatalog.of(List.of(BmsParser.parse(source))));
        ProgramCatalog catalog = ProgramCatalog.builder()
                .revision("demo-009-web-r1")
                .cobolProgram("TODOAPP", () -> instantiate("cobol.generated.TODOAPP"))
                .build();
        return new CobolCicsTaskProgram(CobolRuntime.builder(catalog)
                .classLoader(TodoWebApplication.class.getClassLoader()).build(), 8, environment);
    }

    private static CobolProgram instantiate(String className) {
        try {
            return (CobolProgram) Class.forName(className).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("cannot load " + className
                    + " -- compile TODOAPP.cbl first (demo/009/run_web)", failure);
        }
    }

    /** 最初の画面。開始の画面へ送る。 */
    @Controller
    static class Home {

        @GetMapping("/")
        String home() {
            return "redirect:/cics/TODO";
        }
    }
}
