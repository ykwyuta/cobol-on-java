package dev.cobolonjava.runtime.interop;

public final class MissingRuntimeServiceException extends IllegalStateException {

    private final Class<?> serviceType;

    public MissingRuntimeServiceException(Class<?> serviceType) {
        super("required runtime service is not configured: " + serviceType.getName());
        this.serviceType = serviceType;
    }

    public Class<?> serviceType() {
        return serviceType;
    }
}
