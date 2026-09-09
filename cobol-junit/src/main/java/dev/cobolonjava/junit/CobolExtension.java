package dev.cobolonjava.junit;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.interop.CobolCallResult;
import dev.cobolonjava.runtime.interop.CobolRuntime;
import dev.cobolonjava.runtime.interop.CobolSession;
import dev.cobolonjava.runtime.interop.DeployCatalogManifest;
import dev.cobolonjava.runtime.interop.GeneratedProgramArtifact;
import dev.cobolonjava.runtime.interop.JavaCallable;
import dev.cobolonjava.runtime.interop.ProgramCatalog;
import dev.cobolonjava.runtime.interop.ProgramSignature;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.procedure.ProcedureDecision;
import dev.cobolonjava.runtime.procedure.ProcedureDescriptor;
import dev.cobolonjava.runtime.procedure.ProcedureHook;
import dev.cobolonjava.runtime.procedure.ProcedureId;
import dev.cobolonjava.runtime.procedure.ProcedureInvocation;
import dev.cobolonjava.runtime.procedure.ProcedureOutcome;
import dev.cobolonjava.runtime.procedure.ProcedureManifest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

/** JUnit 5 のテストメソッドごとに独立した COBOL 実行単位を提供する。 */
public final class CobolExtension implements BeforeEachCallback, AfterEachCallback,
        ParameterResolver, CobolTestContext {

    private final List<SourceSpec> sources;
    private final Map<String, Supplier<? extends CobolProgram>> registeredPrograms;
    private final DeployCatalogManifest deployCatalog;
    private final ClassLoader deployCatalogLoader;
    private final CodePage codePage;
    private volatile CompiledSuite compiled;
    private TestState state;

    private CobolExtension(Builder builder) {
        sources = List.copyOf(builder.sources);
        registeredPrograms = Map.copyOf(builder.programs);
        deployCatalog = builder.deployCatalog;
        deployCatalogLoader = builder.deployCatalogLoader;
        codePage = builder.codePage;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public synchronized void beforeEach(ExtensionContext context) {
        TestInstance.Lifecycle lifecycle = context.getTestInstanceLifecycle()
                .orElse(TestInstance.Lifecycle.PER_METHOD);
        if (lifecycle == TestInstance.Lifecycle.PER_CLASS) {
            throw new IllegalTestStateException(
                    "CobolExtension does not support @TestInstance(PER_CLASS)");
        }
        if (state != null) {
            throw new IllegalTestStateException("a COBOL test session is already active");
        }
        state = new TestState();
        ensureCompiled();
    }

    @Override
    public synchronized void afterEach(ExtensionContext context) throws Exception {
        if (state == null) {
            return;
        }
        Throwable cleanupFailure = null;
        for (CobolProgramMock mock : state.mocks.values()) {
            try {
                mock.verify();
            } catch (Throwable failure) {
                cleanupFailure = combine(cleanupFailure, failure);
            }
        }
        for (CobolSectionMock mock : state.sectionMocks.values()) {
            try {
                mock.verify();
            } catch (Throwable failure) {
                cleanupFailure = combine(cleanupFailure, failure);
            }
        }
        try {
            if (state.session != null) {
                state.session.close();
            }
        } catch (Throwable failure) {
            if (cleanupFailure == null) {
                cleanupFailure = failure;
            } else {
                cleanupFailure.addSuppressed(failure);
            }
        } finally {
            state = null;
        }
        if (cleanupFailure != null) {
            Throwable primary = context.getExecutionException().orElse(null);
            if (primary != null) {
                primary.addSuppressed(cleanupFailure);
                context.publishReportEntry("cobol-cleanup-failure", cleanupFailure.toString());
                return;
            }
            rethrow(cleanupFailure);
        }
    }

    private static Throwable combine(Throwable primary, Throwable additional) {
        if (primary == null) {
            return additional;
        }
        primary.addSuppressed(additional);
        return primary;
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext,
                                     ExtensionContext extensionContext) {
        return parameterContext.getParameter().getType() == CobolTestContext.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext,
                                   ExtensionContext extensionContext) {
        if (!supportsParameter(parameterContext, extensionContext)) {
            throw new ParameterResolutionException("unsupported parameter: "
                    + parameterContext.getParameter());
        }
        return this;
    }

    @Override
    public synchronized CobolProgramFixture program(String name) {
        requireState();
        return new CobolProgramFixture(this, name);
    }

    @Override
    public synchronized CobolProgramMock stubProgram(String name) {
        return registerMock(name, false);
    }

    @Override
    public synchronized CobolProgramMock stubProgram(String name, JavaCallable answer) {
        return registerMock(name, false).thenAnswer(answer);
    }

    @Override
    public synchronized CobolProgramMock expectProgram(String name) {
        return registerMock(name, true);
    }

    @Override
    public synchronized CobolSectionMock mockSection(String program, String section) {
        return registerSection(program, section, false);
    }

    @Override
    public synchronized CobolSectionMock spySection(String program, String section) {
        return registerSection(program, section, true);
    }

    @Override
    public synchronized String output() {
        return requireState().output.toString(StandardCharsets.UTF_8);
    }

    synchronized CobolTestResult invokeSection(
            String program, String section, dev.cobolonjava.runtime.storage.DataView[] arguments) {
        requireState();
        ProcedureId id = ProcedureId.section(program, section);
        ProcedureManifest manifest = compiled.procedureManifests.get(id.programId());
        if (manifest == null) {
            throw new IllegalTestStateException(
                    "direct SECTION invocation requires a generated procedure manifest: "
                            + id.programId().value());
        }
        ProcedureDescriptor descriptor = manifest.procedures().stream()
                .filter(procedure -> procedure.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown SECTION in " + id.programId().value() + ": " + id.name()));
        CobolCallResult result = session().invokeProcedure(descriptor, arguments);
        return CobolTestResult.from(result, output());
    }

    @Override
    public CodePage codePage() {
        return codePage;
    }

    @Override
    public synchronized CobolSession session() {
        TestState current = requireState();
        if (current.session == null) {
            current.frozen = true;
            ProgramCatalog.Builder catalog = ProgramCatalog.builder()
                    .revision(compiled.catalog.revision().value() + ":test")
                    .fallback(compiled.catalog);
            for (CobolProgramMock mock : current.mocks.values()) {
                ProgramSignature signature = compiled.catalog.signature(mock.id());
                if (signature == null) {
                    catalog.javaProgram(mock.id().value(), mock::callable);
                } else {
                    catalog.javaProgram(mock.id().value(), signature, mock::callable);
                }
            }
            current.session = CobolRuntime.builder(catalog.build())
                    .classLoader(compiled.loader)
                    .defaultCodePage(codePage)
                    .procedureHook(current.procedureHook())
                    .build()
                    .openSession(current.output);
        }
        return current.session;
    }

    private CobolProgramMock registerMock(String name, boolean expectation) {
        TestState current = requireState();
        if (current.frozen) {
            throw new IllegalTestStateException(
                    "program overrides cannot change after the first COBOL execution");
        }
        String normalized = dev.cobolonjava.runtime.interop.ProgramId.of(name).value();
        if (current.mocks.containsKey(normalized)) {
            throw new IllegalArgumentException("duplicate program override: " + normalized);
        }
        CobolProgramMock mock = new CobolProgramMock(normalized,
                current.sequence::incrementAndGet, expectation);
        current.mocks.put(normalized, mock);
        return mock;
    }

    private CobolSectionMock registerSection(String program, String section, boolean spy) {
        TestState current = requireState();
        if (current.frozen) {
            throw new IllegalTestStateException(
                    "SECTION overrides cannot change after the first COBOL execution");
        }
        ProcedureId id = ProcedureId.section(program, section);
        if (!compiled.catalog.definitions().containsKey(id.programId())) {
            throw new IllegalArgumentException("unknown test program for SECTION override: "
                    + id.programId().value());
        }
        ProcedureManifest manifest = compiled.procedureManifests.get(id.programId());
        if (manifest != null) {
            boolean declared = manifest.procedures().stream()
                    .anyMatch(procedure -> procedure.id().equals(id)
                            && !procedure.declarative());
            if (!declared) {
                throw new IllegalArgumentException("SECTION is not a mockable normal SECTION in "
                        + manifest.programId().value() + ": " + id.name());
            }
        }
        if (current.sectionMocks.containsKey(id)) {
            throw new IllegalArgumentException("duplicate SECTION override: " + id);
        }
        CobolSectionMock mock = new CobolSectionMock(id, spy,
                current.sequence::incrementAndGet);
        current.sectionMocks.put(id, mock);
        return mock;
    }

    private TestState requireState() {
        if (state == null) {
            throw new IllegalTestStateException(
                    "CobolExtension can only be used while a JUnit test method is active");
        }
        return state;
    }

    private synchronized void ensureCompiled() {
        if (compiled != null) {
            return;
        }
        try {
            GeneratedLoader loader = new GeneratedLoader(deployCatalogLoader == null
                    ? CobolExtension.class.getClassLoader() : deployCatalogLoader);
            List<NamedFactory> programs = new ArrayList<>();
            Map<dev.cobolonjava.runtime.interop.ProgramId, ProcedureManifest> manifests =
                    new LinkedHashMap<>();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (deployCatalog != null) {
                ProgramCatalog deployed = deployCatalog.toProgramCatalog();
                digest.update(deployCatalog.revision().value().getBytes(StandardCharsets.UTF_8));
                for (GeneratedProgramArtifact artifact : deployCatalog.programs()) {
                    CobolProgram generated = deployed.resolve(artifact.programId(), loader);
                    ProcedureManifest manifest = generated.procedureManifest();
                    if (manifest == null || manifests.putIfAbsent(
                            artifact.programId(), manifest) != null) {
                        throw new CobolCompilationException(
                                "missing or duplicate deployed procedure manifest: "
                                        + artifact.programId().value());
                    }
                    programs.add(new NamedFactory(artifact.programId().value(),
                            () -> deployed.resolve(artifact.programId(), loader),
                            artifact.signature()));
                }
            }
            for (SourceSpec source : sources) {
                String text = source.text.get();
                digest.update(source.fileName.getBytes(StandardCharsets.UTF_8));
                digest.update(text.getBytes(StandardCharsets.UTF_8));
                CobolCompiler.Result result = CobolCompiler.standard()
                        .compile(source.fileName, text);
                if (!result.succeeded()) {
                    throw new CobolCompilationException("cannot compile " + source.fileName
                            + ": " + result.diagnostics());
                }
                for (CobolCompiler.Compiled one : result.programs()) {
                    Class<?> type = loader.define(one.className(), one.classFile());
                    Supplier<CobolProgram> factory = factory(type);
                    CobolProgram generated = factory.get();
                    String programName = generated.name();
                    if (!one.procedureManifest().programId().value().equals(programName)) {
                        throw new CobolCompilationException("generated program and manifest disagree: "
                                + programName + " / " + one.procedureManifest().programId().value());
                    }
                    if (!one.programSignature().equals(generated.programSignature())
                            || !one.procedureManifest().equals(generated.procedureManifest())) {
                        throw new CobolCompilationException(
                                "generated class metadata disagrees with compiler result: "
                                        + programName);
                    }
                    programs.add(new NamedFactory(programName, factory,
                            generated.programSignature()));
                    ProcedureManifest previous = manifests.putIfAbsent(
                            generated.procedureManifest().programId(),
                            generated.procedureManifest());
                    if (previous != null) {
                        throw new CobolCompilationException("duplicate procedure manifest: "
                                + programName);
                    }
                }
            }
            registeredPrograms.forEach((name, factory) -> {
                CobolProgram program = Objects.requireNonNull(factory.get(),
                        "registered COBOL program factory result");
                dev.cobolonjava.runtime.interop.ProgramId id =
                        dev.cobolonjava.runtime.interop.ProgramId.of(name);
                ProgramSignature signature = program.programSignature();
                ProcedureManifest manifest = program.procedureManifest();
                if (signature != null && !id.equals(signature.programId())) {
                    throw new CobolCompilationException(
                            "registered program and embedded signature disagree: " + name);
                }
                if (manifest != null && !id.equals(manifest.programId())) {
                    throw new CobolCompilationException(
                            "registered program and embedded procedure manifest disagree: "
                                    + name);
                }
                if (manifest != null && manifests.putIfAbsent(id, manifest) != null) {
                    throw new CobolCompilationException("duplicate procedure manifest: " + name);
                }
                programs.add(new NamedFactory(name, factory, signature));
            });
            ProgramCatalog.Builder catalog = ProgramCatalog.builder()
                    .revision("test-" + hex(digest.digest(), 12));
            for (NamedFactory program : programs) {
                if (program.signature == null) {
                    catalog.cobolProgram(program.name, program.factory);
                } else {
                    catalog.cobolProgram(program.name, program.signature, program.factory);
                }
            }
            compiled = new CompiledSuite(catalog.build(), loader, Map.copyOf(manifests));
        } catch (CobolCompilationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new CobolCompilationException("cannot prepare COBOL test suite", e);
        }
    }

    private static Supplier<CobolProgram> factory(Class<?> type) {
        if (!CobolProgram.class.isAssignableFrom(type)) {
            throw new CobolCompilationException(type.getName()
                    + " does not implement CobolProgram");
        }
        return () -> {
            try {
                return (CobolProgram) type.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new CobolCompilationException("cannot create " + type.getName(), e);
            }
        };
    }

    private static String hex(byte[] bytes, int characters) {
        StringBuilder out = new StringBuilder();
        for (byte value : bytes) {
            out.append(String.format("%02x", value));
        }
        return out.substring(0, characters);
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new AssertionError(failure);
    }

    private record SourceSpec(String fileName, Supplier<String> text) {
    }

    private record NamedFactory(String name, Supplier<? extends CobolProgram> factory,
                                ProgramSignature signature) {
    }

    private record CompiledSuite(
            ProgramCatalog catalog,
            GeneratedLoader loader,
            Map<dev.cobolonjava.runtime.interop.ProgramId, ProcedureManifest> procedureManifests) {
    }

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader(ClassLoader parent) {
            super(parent);
        }

        private Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static final class TestState {

        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final Map<String, CobolProgramMock> mocks = new LinkedHashMap<>();
        private final Map<ProcedureId, CobolSectionMock> sectionMocks = new LinkedHashMap<>();
        private final AtomicLong sequence = new AtomicLong();
        private CobolSession session;
        private boolean frozen;

        private ProcedureHook procedureHook() {
            return new ProcedureHook() {
                @Override
                public ProcedureDecision before(ProcedureInvocation invocation) {
                    CobolSectionMock mock = sectionMocks.get(invocation.procedureId());
                    return mock == null ? ProcedureDecision.PROCEED : mock.before(invocation);
                }

                @Override
                public void after(ProcedureInvocation invocation, ProcedureOutcome outcome) {
                    CobolSectionMock mock = sectionMocks.get(invocation.procedureId());
                    if (mock != null) {
                        mock.after(invocation, outcome);
                    }
                }
            };
        }
    }

    public static final class Builder {

        private final List<SourceSpec> sources = new ArrayList<>();
        private final Map<String, Supplier<? extends CobolProgram>> programs =
                new LinkedHashMap<>();
        private DeployCatalogManifest deployCatalog;
        private ClassLoader deployCatalogLoader;
        private CodePage codePage = CodePages.DEFAULT;

        public Builder source(String path) {
            return source(Path.of(path));
        }

        public Builder source(Path path) {
            Objects.requireNonNull(path, "path");
            sources.add(new SourceSpec(path.getFileName().toString(), () -> read(path)));
            return this;
        }

        /** 動的に作るテストや extension 自身の契約試験向け。 */
        public Builder sourceText(String fileName, String source) {
            Objects.requireNonNull(fileName, "fileName");
            Objects.requireNonNull(source, "source");
            sources.add(new SourceSpec(fileName, () -> source));
            return this;
        }

        /** 事前コンパイル済みまたは手書きの CobolProgram をテストカタログへ加える。 */
        public Builder program(String name, Supplier<? extends CobolProgram> factory) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(factory, "factory");
            if (programs.putIfAbsent(name, factory) != null) {
                throw new IllegalArgumentException("duplicate test program: " + name);
            }
            return this;
        }

        /** 標準資源名から配備カタログを読み、生成済みJARを本番と同じ登録経路で使う。 */
        public Builder deployCatalog(ClassLoader loader) {
            try {
                return deployCatalog(DeployCatalogManifest.fromResource(loader), loader);
            } catch (IOException e) {
                throw new CobolCompilationException("cannot read COBOL deploy catalog", e);
            }
        }

        /** 資源を既に読んだビルドツール・契約試験向け。 */
        public Builder deployCatalog(DeployCatalogManifest manifest, ClassLoader loader) {
            if (deployCatalog != null) {
                throw new IllegalArgumentException("a deploy catalog is already registered");
            }
            deployCatalog = Objects.requireNonNull(manifest, "manifest");
            deployCatalogLoader = Objects.requireNonNull(loader, "loader");
            return this;
        }

        public Builder defaultCodePage(CodePage value) {
            codePage = Objects.requireNonNull(value, "value");
            return this;
        }

        public CobolExtension build() {
            if (sources.isEmpty() && programs.isEmpty() && deployCatalog == null) {
                throw new IllegalStateException("at least one COBOL source or program is required");
            }
            return new CobolExtension(this);
        }

        private static String read(Path path) {
            try {
                return Files.readString(path, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new CobolCompilationException("cannot read " + path, e);
            }
        }
    }
}
