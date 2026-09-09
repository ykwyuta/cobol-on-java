package dev.cobolonjava.runtime.interop;

import dev.cobolonjava.runtime.storage.DataView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 主entryの引数個数・長さ・レイアウトを表す機械可読な低レベルABI契約。 */
public record ProgramSignature(
        ProgramId programId,
        List<ProgramParameter> parameters,
        String layoutHash) {

    public ProgramSignature {
        Objects.requireNonNull(programId, "programId");
        parameters = List.copyOf(parameters);
        if (layoutHash == null || layoutHash.isBlank()) {
            throw new IllegalArgumentException("layoutHash must not be blank");
        }
        boolean optionalSeen = false;
        Set<String> names = new HashSet<>();
        for (ProgramParameter parameter : parameters) {
            Objects.requireNonNull(parameter, "parameter");
            String normalized = parameter.name().toUpperCase(java.util.Locale.ROOT);
            if (!names.add(normalized)) {
                throw new IllegalArgumentException("duplicate parameter name: "
                        + parameter.name());
            }
            if (parameter.presence() == ProgramParameter.Presence.OPTIONAL) {
                optionalSeen = true;
            } else if (optionalSeen) {
                throw new IllegalArgumentException(
                        "required parameter cannot follow an optional parameter");
            }
        }
        String expected = hash(programId, parameters);
        if (!expected.equals(layoutHash)) {
            throw new IllegalArgumentException("layoutHash does not match signature contents");
        }
    }

    public static ProgramSignature of(String program, List<ProgramParameter> parameters) {
        ProgramId id = ProgramId.of(program);
        List<ProgramParameter> copy = List.copyOf(parameters);
        return new ProgramSignature(id, copy, hash(id, copy));
    }

    /** 実行前に、現行DataView ABIで観測できる個数とバイト長を検査する。 */
    public void validate(DataView[] arguments) {
        Objects.requireNonNull(arguments, "arguments");
        int required = 0;
        for (ProgramParameter parameter : parameters) {
            if (parameter.passingMode() == ProgramParameter.PassingMode.VALUE) {
                throw mismatch("parameter " + parameter.name()
                        + " uses BY VALUE, which is not supported by the DataView ABI");
            }
            if (parameter.presence() == ProgramParameter.Presence.REQUIRED) {
                required++;
            }
        }
        if (arguments.length < required || arguments.length > parameters.size()) {
            throw mismatch("expected " + required
                    + (required == parameters.size() ? "" : ".." + parameters.size())
                    + " argument(s), but got " + arguments.length);
        }
        for (int i = 0; i < arguments.length; i++) {
            DataView argument = arguments[i];
            if (argument == null) {
                throw mismatch("argument " + (i + 1) + " (" + parameters.get(i).name()
                        + ") is null; OMITTED is not supported by this ABI yet");
            }
            ProgramParameter parameter = parameters.get(i);
            int length = argument.length();
            if (length < parameter.minimumBytes() || length > parameter.maximumBytes()) {
                String expected = parameter.minimumBytes() == parameter.maximumBytes()
                        ? Integer.toString(parameter.minimumBytes())
                        : parameter.minimumBytes() + ".." + parameter.maximumBytes();
                throw mismatch("argument " + (i + 1) + " (" + parameter.name()
                        + ") expected " + expected + " byte(s), but got " + length);
            }
        }
    }

    private ProgramSignatureMismatchException mismatch(String detail) {
        return new ProgramSignatureMismatchException(programId, detail);
    }

    private static String hash(ProgramId id, List<ProgramParameter> parameters) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "program-signature-v1\n");
            update(digest, id.value());
            update(digest, "\n");
            for (ProgramParameter parameter : parameters) {
                update(digest, parameter.name().toUpperCase(java.util.Locale.ROOT));
                digest.update((byte) 0);
                update(digest, Integer.toString(parameter.minimumBytes()));
                digest.update((byte) ':');
                update(digest, Integer.toString(parameter.maximumBytes()));
                digest.update((byte) 0);
                update(digest, parameter.presence().name());
                digest.update((byte) 0);
                update(digest, parameter.passingMode().name());
                digest.update((byte) 0);
                update(digest, parameter.direction().name());
                digest.update((byte) 0);
                update(digest, parameter.layoutHash());
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }
}
