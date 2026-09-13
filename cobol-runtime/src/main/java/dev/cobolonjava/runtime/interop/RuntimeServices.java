package dev.cobolonjava.runtime.interop;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 実行sessionへtask-scoped subsystem portを型で渡す不変registry。 */
public final class RuntimeServices {

    public static final RuntimeServices EMPTY = new RuntimeServices(Map.of());

    private final Map<Class<?>, Object> services;

    private RuntimeServices(Map<Class<?>, Object> services) {
        this.services = Map.copyOf(services);
    }

    public static Builder builder() {
        return new Builder();
    }

    public <T> T require(Class<T> type) {
        Objects.requireNonNull(type, "type");
        Object service = services.get(type);
        if (service == null) {
            throw new MissingRuntimeServiceException(type);
        }
        return type.cast(service);
    }

    public boolean contains(Class<?> type) {
        return services.containsKey(Objects.requireNonNull(type, "type"));
    }

    public static final class Builder {

        private final Map<Class<?>, Object> services = new LinkedHashMap<>();

        public <T> Builder service(Class<T> type, T service) {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(service, "service");
            if (!type.isInstance(service)) {
                throw new IllegalArgumentException("service does not implement " + type.getName());
            }
            if (services.putIfAbsent(type, service) != null) {
                throw new IllegalArgumentException("duplicate runtime service: " + type.getName());
            }
            return this;
        }

        public RuntimeServices build() {
            return services.isEmpty() ? EMPTY : new RuntimeServices(services);
        }
    }
}
