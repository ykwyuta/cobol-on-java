package dev.cobolonjava.cics.tn3270;

import dev.cobolonjava.cics.CicsEnvironment;
import dev.cobolonjava.cics.CicsTaskCoordinator;
import dev.cobolonjava.cics.CicsTaskPolicy;
import dev.cobolonjava.cics.CicsTerminalRegistryPort;
import dev.cobolonjava.cics.CicsTransactionDefinition;
import dev.cobolonjava.cics.CicsTransactionRegistry;
import dev.cobolonjava.cics.CobolCicsTaskProgram;
import dev.cobolonjava.cics.ConversationId;
import dev.cobolonjava.cics.InMemoryConversationStore;
import dev.cobolonjava.cics.NonRecoverableTaskBoundaryFactory;
import dev.cobolonjava.cics.TransId;
import dev.cobolonjava.cics.bms.BmsMapsetCatalog;
import dev.cobolonjava.cics.bms.BmsModel;
import dev.cobolonjava.cics.bms.BmsParser;
import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.interop.CobolRuntime;
import dev.cobolonjava.runtime.interop.ProgramCatalog;
import dev.cobolonjava.runtime.interop.ProgramId;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

/**
 * 翻訳した CICS の COBOL を TN3270 で受ける領域を、コマンド行から 1 つ起こす (設計 86 §5)。Spring を使わない。
 *
 * <pre>
 * java -cp &lt;生成した class&gt;:cobol-cics.jar:cobol-runtime.jar dev.cobolonjava.cics.tn3270.Tn3270Main \
 *     --bms GRTSET.bms --transaction GRT1=GREET --port 3270
 * </pre>
 *
 * <p>program は生成した class の名前の規則で classpath から引く。会話、端末、資源はすべて 1 つの JVM の中に持ち、
 * 回復可能な資源 (Db2 の UOW など) は持たない。sign on (CESN) は無いので、既定では loopback だけで受ける。
 */
public final class Tn3270Main {

    private Tn3270Main() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        String host = "127.0.0.1";
        int port = 3270;
        String applid = "COBOLJVM";
        Optional<String> user = Optional.empty();
        CodePage codePage = CodePages.DEFAULT;
        List<BmsModel.Mapset> mapsets = new ArrayList<>();
        List<CicsTransactionDefinition> transactions = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = value(args, ++i);
                case "--port" -> port = Integer.parseInt(value(args, ++i));
                case "--applid" -> applid = value(args, ++i);
                case "--user" -> user = Optional.of(value(args, ++i));
                case "--codepage" -> codePage = CodePages.forName(value(args, ++i));
                case "--bms" -> mapsets.add(BmsParser.parse(
                        Files.readString(Path.of(value(args, ++i)), StandardCharsets.ISO_8859_1)));
                case "--transaction" -> transactions.add(transaction(value(args, ++i)));
                default -> {
                    System.err.println("unknown option: " + args[i]);
                    usage();
                    System.exit(2);
                    return;
                }
            }
        }
        if (transactions.isEmpty()) {
            usage();
            System.exit(2);
            return;
        }
        CicsEnvironment environment = CicsEnvironment.withApplid(applid)
                .withMapsets(BmsMapsetCatalog.of(mapsets));
        ProgramCatalog catalog = ProgramCatalog.builder().revision("tn3270-main")
                .legacyClassNameFallback().build();
        CobolRuntime runtime = CobolRuntime.builder(catalog).defaultCodePage(codePage)
                .classLoader(Tn3270Main.class.getClassLoader()).build();
        InMemoryConversationStore conversations = new InMemoryConversationStore();
        CicsTaskPolicy policy = new CicsTaskPolicy(Duration.ofHours(8), Duration.ofMinutes(5));
        CicsTaskCoordinator coordinator = new CicsTaskCoordinator(new CicsTransactionRegistry(transactions),
                conversations, new NonRecoverableTaskBoundaryFactory(conversations),
                new CobolCicsTaskProgram(runtime, 16, environment), policy, ConversationId::create,
                Clock.systemUTC());
        Tn3270Server server = new Tn3270Server(coordinator, conversations, CicsTerminalRegistryPort.inMemory(),
                codePage, Tn3270Server.Options.defaults().withApplid(applid).withDefaultUserId(user)
                        .withLeaseDuration(policy.leaseDuration()), Clock.systemUTC());
        server.start(new InetSocketAddress(host, port));
        System.out.println(applid + " is accepting 3270 terminals on " + host + ":" + server.port());
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.close();
            } catch (IOException ignored) {
                // 止めるところなので続ける
            }
            stopped.countDown();
        }));
        stopped.await();
    }

    /** {@code TRAN=PROGRAM}。COMMAREA は 32763 byte まで (CICS の COMMAREA の上限)。 */
    private static CicsTransactionDefinition transaction(String spec) {
        int equals = spec.indexOf('=');
        if (equals <= 0) {
            throw new IllegalArgumentException("--transaction takes TRAN=PROGRAM: " + spec);
        }
        return new CicsTransactionDefinition(TransId.of(spec.substring(0, equals)),
                ProgramId.of(spec.substring(equals + 1)), Duration.ofMinutes(1), 32763, 0, 0, 0, true);
    }

    private static String value(String[] args, int index) {
        if (index >= args.length) {
            throw new IllegalArgumentException(args[index - 1] + " needs a value");
        }
        return args[index];
    }

    private static void usage() {
        System.err.println("usage: Tn3270Main --transaction TRAN=PROGRAM [--transaction ...] [--bms MAPSET.bms ...]");
        System.err.println("                  [--port 3270] [--host 127.0.0.1] [--applid COBOLJVM]");
        System.err.println("                  [--user USERID] [--codepage IBM-1047]");
    }
}
