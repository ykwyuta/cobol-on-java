package dev.cobolonjava.pli;

import java.util.Optional;

/** {@code %INCLUDE member;} の探索境界。 */
@FunctionalInterface
public interface IncludeResolver {
    Optional<String> resolve(String member);
}
