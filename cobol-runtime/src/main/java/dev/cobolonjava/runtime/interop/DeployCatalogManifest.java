package dev.cobolonjava.runtime.interop;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** JARへ格納する、生成COBOLプログラム一覧の版管理されたJSONカタログ。 */
public record DeployCatalogManifest(
        int formatVersion,
        CatalogRevision revision,
        String compilerVersion,
        String runtimeAbiVersion,
        String allowedPackage,
        List<GeneratedProgramArtifact> programs) {

    public static final int CURRENT_FORMAT_VERSION = 1;
    public static final String CURRENT_RUNTIME_ABI_VERSION = "1";
    public static final String RESOURCE_NAME = "META-INF/cobol/programs.json";

    public DeployCatalogManifest {
        if (formatVersion != CURRENT_FORMAT_VERSION) {
            throw new IllegalArgumentException("unsupported deploy catalog format: "
                    + formatVersion);
        }
        Objects.requireNonNull(revision, "revision");
        if (compilerVersion == null || compilerVersion.isBlank()) {
            throw new IllegalArgumentException("compilerVersion must not be blank");
        }
        if (!CURRENT_RUNTIME_ABI_VERSION.equals(runtimeAbiVersion)) {
            throw new IllegalArgumentException("unsupported runtime ABI: " + runtimeAbiVersion);
        }
        if (allowedPackage == null || allowedPackage.isBlank()
                || allowedPackage.startsWith(".") || allowedPackage.endsWith(".")) {
            throw new IllegalArgumentException("allowedPackage must be a package name");
        }
        programs = List.copyOf(programs);
        Set<ProgramId> ids = new HashSet<>();
        Set<String> classes = new HashSet<>();
        for (GeneratedProgramArtifact program : programs) {
            Objects.requireNonNull(program, "program");
            program.definition(allowedPackage);
            if (!ids.add(program.programId())) {
                throw new IllegalArgumentException("duplicate program in deploy catalog: "
                        + program.programId().value());
            }
            if (!classes.add(program.className())) {
                throw new IllegalArgumentException("duplicate generated class in deploy catalog: "
                        + program.className());
            }
        }
    }

    /** マニフェストの固定revisionと署名を使う本番向けカタログを作る。 */
    public ProgramCatalog toProgramCatalog() {
        ProgramCatalog.Builder builder = ProgramCatalog.builder().revision(revision.value());
        for (GeneratedProgramArtifact program : programs) {
            builder.register(program.definition(allowedPackage));
        }
        return builder.build();
    }

    /** 外部ライブラリに依存しない固定スキーマのJSON表現。 */
    public String toJson() {
        StringBuilder out = new StringBuilder(512 + programs.size() * 256);
        out.append("{\n")
                .append("  \"formatVersion\": ").append(formatVersion).append(",\n")
                .append("  \"revision\": ").append(quote(revision.value())).append(",\n")
                .append("  \"compilerVersion\": ").append(quote(compilerVersion)).append(",\n")
                .append("  \"runtimeAbiVersion\": ").append(quote(runtimeAbiVersion)).append(",\n")
                .append("  \"allowedPackage\": ").append(quote(allowedPackage)).append(",\n")
                .append("  \"programs\": [");
        for (int i = 0; i < programs.size(); i++) {
            GeneratedProgramArtifact program = programs.get(i);
            out.append(i == 0 ? "\n" : ",\n")
                    .append("    {\"programId\": ").append(quote(program.programId().value()))
                    .append(", \"className\": ").append(quote(program.className()))
                    .append(", \"procedureHash\": ").append(quote(program.procedureHash()))
                    .append(", \"signature\": {")
                    .append("\"layoutHash\": ").append(quote(program.signature().layoutHash()))
                    .append(", \"parameters\": [");
            List<ProgramParameter> parameters = program.signature().parameters();
            for (int p = 0; p < parameters.size(); p++) {
                ProgramParameter parameter = parameters.get(p);
                if (p > 0) {
                    out.append(',');
                }
                out.append("{\"name\": ").append(quote(parameter.name()))
                        .append(", \"minimumBytes\": ").append(parameter.minimumBytes())
                        .append(", \"maximumBytes\": ").append(parameter.maximumBytes())
                        .append(", \"presence\": ").append(quote(parameter.presence().name()))
                        .append(", \"passingMode\": ")
                        .append(quote(parameter.passingMode().name()))
                        .append(", \"direction\": ").append(quote(parameter.direction().name()))
                        .append(", \"layoutHash\": ").append(quote(parameter.layoutHash()))
                        .append('}');
            }
            out.append("]}} ");
        }
        if (!programs.isEmpty()) {
            out.append('\n');
        }
        return out.append("  ]\n}\n").toString();
    }

    /** 信頼境界で未知フィールドと型違いを拒否し、値オブジェクトのhash検査も行う。 */
    public static DeployCatalogManifest fromJson(String json) {
        Map<String, Object> root = object(Json.parse(json), "root");
        exactKeys(root, "root", Set.of("formatVersion", "revision", "compilerVersion",
                "runtimeAbiVersion", "allowedPackage", "programs"));
        int format = integer(root.get("formatVersion"), "formatVersion");
        String revision = string(root.get("revision"), "revision");
        String compiler = string(root.get("compilerVersion"), "compilerVersion");
        String runtime = string(root.get("runtimeAbiVersion"), "runtimeAbiVersion");
        String allowed = string(root.get("allowedPackage"), "allowedPackage");
        List<GeneratedProgramArtifact> artifacts = new ArrayList<>();
        for (Object value : array(root.get("programs"), "programs")) {
            Map<String, Object> program = object(value, "program");
            exactKeys(program, "program", Set.of(
                    "programId", "className", "procedureHash", "signature"));
            ProgramId id = ProgramId.of(string(program.get("programId"), "programId"));
            Map<String, Object> signatureJson = object(program.get("signature"), "signature");
            exactKeys(signatureJson, "signature", Set.of("layoutHash", "parameters"));
            List<ProgramParameter> parameters = new ArrayList<>();
            for (Object parameterValue : array(signatureJson.get("parameters"), "parameters")) {
                Map<String, Object> parameter = object(parameterValue, "parameter");
                exactKeys(parameter, "parameter", Set.of("name", "minimumBytes",
                        "maximumBytes", "presence", "passingMode", "direction", "layoutHash"));
                parameters.add(new ProgramParameter(
                        string(parameter.get("name"), "name"),
                        integer(parameter.get("minimumBytes"), "minimumBytes"),
                        integer(parameter.get("maximumBytes"), "maximumBytes"),
                        enumeration(ProgramParameter.Presence.class,
                                parameter.get("presence"), "presence"),
                        enumeration(ProgramParameter.PassingMode.class,
                                parameter.get("passingMode"), "passingMode"),
                        enumeration(ProgramParameter.Direction.class,
                                parameter.get("direction"), "direction"),
                        string(parameter.get("layoutHash"), "layoutHash")));
            }
            ProgramSignature signature = new ProgramSignature(id, parameters,
                    string(signatureJson.get("layoutHash"), "signature.layoutHash"));
            artifacts.add(new GeneratedProgramArtifact(id,
                    string(program.get("className"), "className"), signature,
                    string(program.get("procedureHash"), "procedureHash")));
        }
        return new DeployCatalogManifest(format, new CatalogRevision(revision), compiler, runtime,
                allowed, artifacts);
    }

    /** 標準資源名から単一の配備カタログを読む。0件・複数件はいずれも構成誤り。 */
    public static DeployCatalogManifest fromResource(ClassLoader loader) throws IOException {
        Objects.requireNonNull(loader, "loader");
        Enumeration<URL> resources = loader.getResources(RESOURCE_NAME);
        if (!resources.hasMoreElements()) {
            throw new IllegalArgumentException("deploy catalog resource was not found: "
                    + RESOURCE_NAME);
        }
        URL resource = resources.nextElement();
        if (resources.hasMoreElements()) {
            throw new IllegalArgumentException(
                    "multiple deploy catalog resources require an explicit merge policy: "
                            + RESOURCE_NAME);
        }
        try (InputStream input = resource.openStream()) {
            byte[] bytes = input.readNBytes(Json.MAX_CHARACTERS + 1);
            if (bytes.length > Json.MAX_CHARACTERS) {
                throw new IllegalArgumentException("deploy catalog JSON is too large");
            }
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            return fromJson(json);
        }
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String name) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(name + " must be a JSON object");
        }
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value, String name) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(name + " must be a JSON array");
        }
        return (List<Object>) list;
    }

    private static String string(Object value, String name) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(name + " must be a JSON string");
        }
        return text;
    }

    private static int integer(Object value, String name) {
        if (!(value instanceof Long number) || number < Integer.MIN_VALUE
                || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " must be a JSON integer");
        }
        return number.intValue();
    }

    private static <E extends Enum<E>> E enumeration(
            Class<E> type, Object value, String name) {
        String text = string(value, name);
        try {
            return Enum.valueOf(type, text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unsupported " + name + ": " + text, e);
        }
    }

    private static void exactKeys(Map<String, Object> object, String name, Set<String> expected) {
        if (!object.keySet().equals(expected)) {
            throw new IllegalArgumentException(name + " has missing or unknown fields: "
                    + object.keySet());
        }
    }

    /** このマニフェストの固定スキーマだけに用いる、深さとサイズに上限を持つJSON reader。 */
    private static final class Json {

        private static final int MAX_CHARACTERS = 4 * 1024 * 1024;
        private static final int MAX_DEPTH = 32;

        private final String input;
        private int position;

        private Json(String input) {
            this.input = input;
        }

        static Object parse(String input) {
            Objects.requireNonNull(input, "json");
            if (input.length() > MAX_CHARACTERS) {
                throw new IllegalArgumentException("deploy catalog JSON is too large");
            }
            Json reader = new Json(input);
            Object value = reader.value(0);
            reader.space();
            if (reader.position != input.length()) {
                throw reader.error("trailing JSON content");
            }
            return value;
        }

        private Object value(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("JSON nesting is too deep");
            }
            space();
            if (position >= input.length()) {
                throw error("unexpected end of JSON");
            }
            return switch (input.charAt(position)) {
                case '{' -> object(depth + 1);
                case '[' -> array(depth + 1);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object(int depth) {
            position++;
            Map<String, Object> result = new LinkedHashMap<>();
            space();
            if (take('}')) {
                return result;
            }
            while (true) {
                space();
                if (position >= input.length() || input.charAt(position) != '"') {
                    throw error("object key must be a string");
                }
                String key = string();
                space();
                require(':');
                if (result.containsKey(key)) {
                    throw error("duplicate object key: " + key);
                }
                result.put(key, value(depth));
                space();
                if (take('}')) {
                    return result;
                }
                require(',');
            }
        }

        private List<Object> array(int depth) {
            position++;
            List<Object> result = new ArrayList<>();
            space();
            if (take(']')) {
                return result;
            }
            while (true) {
                result.add(value(depth));
                space();
                if (take(']')) {
                    return result;
                }
                require(',');
            }
        }

        private String string() {
            require('"');
            StringBuilder out = new StringBuilder();
            while (position < input.length()) {
                char c = input.charAt(position++);
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    if (position >= input.length()) {
                        throw error("unfinished JSON escape");
                    }
                    char escaped = input.charAt(position++);
                    switch (escaped) {
                        case '"', '\\', '/' -> out.append(escaped);
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> out.append(unicode());
                        default -> throw error("invalid JSON escape");
                    }
                } else {
                    if (c < 0x20) {
                        throw error("unescaped control character in JSON string");
                    }
                    out.append(c);
                }
            }
            throw error("unterminated JSON string");
        }

        private char unicode() {
            if (position + 4 > input.length()) {
                throw error("unfinished Unicode escape");
            }
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(input.charAt(position++), 16);
                if (digit < 0) {
                    throw error("invalid Unicode escape");
                }
                value = value * 16 + digit;
            }
            return (char) value;
        }

        private Object number() {
            int start = position;
            if (take('-') && position >= input.length()) {
                throw error("unfinished JSON number");
            }
            if (take('0')) {
                if (position < input.length() && Character.isDigit(input.charAt(position))) {
                    throw error("leading zero in JSON number");
                }
            } else {
                int digits = position;
                while (position < input.length() && Character.isDigit(input.charAt(position))) {
                    position++;
                }
                if (position == digits) {
                    throw error("expected JSON value");
                }
            }
            if (position < input.length()
                    && (input.charAt(position) == '.' || input.charAt(position) == 'e'
                    || input.charAt(position) == 'E')) {
                throw error("only integer JSON numbers are supported");
            }
            try {
                return Long.parseLong(input.substring(start, position));
            } catch (NumberFormatException e) {
                throw error("JSON integer is out of range");
            }
        }

        private Object literal(String text, Object value) {
            if (!input.startsWith(text, position)) {
                throw error("invalid JSON literal");
            }
            position += text.length();
            return value;
        }

        private void space() {
            while (position < input.length()) {
                char c = input.charAt(position);
                if (c != ' ' && c != '\n' && c != '\r' && c != '\t') {
                    return;
                }
                position++;
            }
        }

        private boolean take(char expected) {
            if (position < input.length() && input.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void require(char expected) {
            if (!take(expected)) {
                throw error("expected '" + expected + "'");
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at JSON offset " + position);
        }
    }
}
