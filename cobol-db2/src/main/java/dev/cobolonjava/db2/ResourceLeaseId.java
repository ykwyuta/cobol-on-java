package dev.cobolonjava.db2;

/** JDBC Connectionを露出せずtask内の同一resource leaseを照合するopaque ID。 */
public record ResourceLeaseId(String value) {

    public ResourceLeaseId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("resource lease id must not be blank");
        }
    }
}
