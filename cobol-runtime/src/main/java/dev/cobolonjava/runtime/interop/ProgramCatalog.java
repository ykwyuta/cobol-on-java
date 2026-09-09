package dev.cobolonjava.runtime.interop;

import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramNotFoundException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** 構築後に変更されない、COBOL と登録済み Java に共通の呼び先カタログ。 */
public final class ProgramCatalog implements ProgramResolver {

    private final CatalogRevision revision;
    private final Map<ProgramId, ProgramDefinition> definitions;
    private final ProgramResolver fallback;

    private ProgramCatalog(CatalogRevision revision,
                           Map<ProgramId, ProgramDefinition> definitions,
                           ProgramResolver fallback) {
        this.revision = revision;
        this.definitions = Map.copyOf(definitions);
        this.fallback = fallback;
    }

    public static Builder builder() {
        return new Builder();
    }

    public CatalogRevision revision() {
        return revision;
    }

    public Map<ProgramId, ProgramDefinition> definitions() {
        return definitions;
    }

    @Override
    public CobolProgram resolve(ProgramId id, ClassLoader loader) {
        ProgramDefinition definition = definitions.get(id);
        if (definition != null) {
            return definition.factory().create(loader);
        }
        if (fallback != null) {
            return fallback.resolve(id, loader);
        }
        return missing(id);
    }

    @Override
    public ProgramSignature signature(ProgramId id) {
        ProgramDefinition definition = definitions.get(id);
        if (definition != null) {
            return definition.signature();
        }
        return fallback == null ? null : fallback.signature(id);
    }

    private static CobolProgram missing(ProgramId id) {
        throw new ProgramNotFoundException(id.value(),
                new ClassNotFoundException("program is not registered"));
    }

    /** 起動時にカタログを組み立てる。build 後のカタログは変更されない。 */
    public static final class Builder {

        private final Map<ProgramId, ProgramDefinition> definitions = new LinkedHashMap<>();
        private CatalogRevision revision;
        private ProgramResolver fallback;

        public Builder revision(String value) {
            revision = new CatalogRevision(value);
            return this;
        }

        public Builder cobolProgram(String name, Supplier<? extends CobolProgram> factory) {
            Objects.requireNonNull(factory, "factory");
            ProgramId id = ProgramId.of(name);
            return register(new ProgramDefinition(id, ProgramKind.COBOL,
                    loader -> Objects.requireNonNull(factory.get(), "COBOL program factory result")));
        }

        public Builder cobolProgram(String name, ProgramSignature signature,
                                    Supplier<? extends CobolProgram> factory) {
            Objects.requireNonNull(factory, "factory");
            ProgramId id = ProgramId.of(name);
            return register(new ProgramDefinition(id, ProgramKind.COBOL,
                    Objects.requireNonNull(signature, "signature"),
                    loader -> Objects.requireNonNull(factory.get(),
                            "COBOL program factory result")));
        }

        public Builder javaProgram(String name, Supplier<? extends JavaCallable> factory) {
            Objects.requireNonNull(factory, "factory");
            ProgramId id = ProgramId.of(name);
            return register(new ProgramDefinition(id, ProgramKind.JAVA, loader ->
                    new JavaProgramAdapter(id,
                            Objects.requireNonNull(factory.get(), "Java program factory result"), loader)));
        }

        public Builder javaProgram(String name, ProgramSignature signature,
                                   Supplier<? extends JavaCallable> factory) {
            Objects.requireNonNull(factory, "factory");
            ProgramId id = ProgramId.of(name);
            return register(new ProgramDefinition(id, ProgramKind.JAVA,
                    Objects.requireNonNull(signature, "signature"), loader ->
                    new JavaProgramAdapter(id,
                            Objects.requireNonNull(factory.get(), "Java program factory result"),
                            loader)));
        }

        public Builder register(ProgramDefinition definition) {
            Objects.requireNonNull(definition, "definition");
            ProgramDefinition previous = definitions.putIfAbsent(definition.id(), definition);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "duplicate program registration: " + definition.id().value());
            }
            return this;
        }

        /** マニフェスト移行中だけ、従来の生成クラス名による反射解決を末尾へ加える。 */
        public Builder legacyClassNameFallback() {
            fallback = LegacyClassNameResolver.INSTANCE;
            return this;
        }

        public Builder fallback(ProgramResolver value) {
            fallback = Objects.requireNonNull(value, "value");
            return this;
        }

        public ProgramCatalog build() {
            CatalogRevision effective = revision != null
                    ? revision : new CatalogRevision(UUID.randomUUID().toString());
            return new ProgramCatalog(effective, definitions, fallback);
        }
    }
}
