package dev.cobolonjava.cics;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** COMMAREAとchannel/containerのコピー分離された値。 */
public final class CicsPayload {

    private static final Pattern CONTAINER_NAME = Pattern.compile("[A-Z0-9_-]{1,16}");
    private static final CicsPayload EMPTY = new CicsPayload(new byte[0], Map.of());

    private final byte[] commarea;
    private final Map<String, byte[]> containers;
    private final int containerTotalLength;
    private final int maxContainerLength;
    /** containerを入れたchannelの名前。RUN TRANSIDで起こす子のtaskが、同じ名前で現在のchannelを開く。分からなければnull。 */
    private final String channelName;

    public CicsPayload(byte[] commarea, Map<String, byte[]> containers) {
        this(commarea, containers, null);
    }

    public CicsPayload(byte[] commarea, Map<String, byte[]> containers, String channelName) {
        if (channelName != null && !CONTAINER_NAME.matcher(channelName).matches()) {
            throw new IllegalArgumentException("channel name must contain 1 to 16 supported characters");
        }
        this.channelName = channelName;
        this.commarea = Arrays.copyOf(Objects.requireNonNull(commarea, "commarea"), commarea.length);
        Objects.requireNonNull(containers, "containers");
        Map<String, byte[]> copied = new LinkedHashMap<>();
        long total = 0;
        int maximum = 0;
        for (Map.Entry<String, byte[]> entry : containers.entrySet()) {
            String name = normalizeContainerName(entry.getKey());
            byte[] value = Arrays.copyOf(
                    Objects.requireNonNull(entry.getValue(), "container value"), entry.getValue().length);
            if (copied.putIfAbsent(name, value) != null) {
                throw new IllegalArgumentException("duplicate normalized container name: " + name);
            }
            total += value.length;
            if (total > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("container total length exceeds integer range");
            }
            maximum = Math.max(maximum, value.length);
        }
        this.containers = Map.copyOf(copied);
        this.containerTotalLength = (int) total;
        this.maxContainerLength = maximum;
    }

    public static CicsPayload empty() {
        return EMPTY;
    }

    public static CicsPayload ofCommarea(byte[] commarea) {
        return new CicsPayload(commarea, Map.of());
    }

    public byte[] commarea() {
        return Arrays.copyOf(commarea, commarea.length);
    }

    public Map<String, byte[]> containers() {
        Map<String, byte[]> copy = new LinkedHashMap<>();
        containers.forEach((name, value) -> copy.put(name, Arrays.copyOf(value, value.length)));
        return Map.copyOf(copy);
    }

    /** containerを入れたchannelの名前。 */
    public java.util.Optional<String> channelName() {
        return java.util.Optional.ofNullable(channelName);
    }

    public int commareaLength() {
        return commarea.length;
    }

    public int containerCount() {
        return containers.size();
    }

    public int containerTotalLength() {
        return containerTotalLength;
    }

    public int maxContainerLength() {
        return maxContainerLength;
    }

    private static String normalizeContainerName(String name) {
        Objects.requireNonNull(name, "container name");
        String normalized = name.strip().toUpperCase(Locale.ROOT);
        if (!CONTAINER_NAME.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "container name must contain 1 to 16 supported characters");
        }
        return normalized;
    }
}
