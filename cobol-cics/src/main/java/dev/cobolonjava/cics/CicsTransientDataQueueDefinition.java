package dev.cobolonjava.cics;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 区画内の一時データのキューの定義 (暫定判断 P-137、設計 83 §6)。
 *
 * <p>trigger level を 1 以上にすると、キューの record の数がそれに達したときに {@code transaction} の task を起こす (ATI)。
 * 属性の意味は CICS TS の TDQUEUE の定義による。TRIGGERLEVEL の既定 (TRANSID を書けば 1) はこの record では持たず、
 * 利用者が数を書く。
 *
 * @param name            {@code QUEUE('名前')} で指す 1〜4 文字の名前
 * @param maxRecordLength 1 つの record の最大の長さ。越える WRITEQ TD は LENGERR
 * @param triggerLevel    0〜32767。0 は ATI をしない
 * @param transaction     trigger level で起こす transaction。trigger level が 1 以上なら必須
 * @param facility        ATIFACILITY。{@code FILE} は端末と結び付かない。{@code TERMINAL} は端末が空くまで起こさない
 * @param facilityId      FACILITYID。TERMINAL の端末の名前で、書かなければキューの名前。FILE では書けない
 * @param userId          USERID。FILE の task の user ID。FILE のときだけ書ける
 * @param recovery        RECOVSTATUS。LOGICAL のキューは task の UOW に入る (設計 85 §7.2)
 */
public record CicsTransientDataQueueDefinition(String name, int maxRecordLength, int triggerLevel,
                                               Optional<TransId> transaction, Facility facility,
                                               Optional<String> facilityId, Optional<String> userId,
                                               Recovery recovery) {

    /** ATIFACILITY。SYSTEM (DTP の session) は持たない。 */
    public enum Facility {
        FILE,
        TERMINAL
    }

    /** RECOVSTATUS。PHYSICAL (region の再始動をまたぐ回復) は持たない。 */
    public enum Recovery {
        NONE,
        LOGICAL
    }

    private static final Pattern NAME = Pattern.compile("[A-Z0-9@#$]{1,4}");
    private static final Pattern USER = Pattern.compile("[A-Z0-9@#$]{1,8}");

    public CicsTransientDataQueueDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(transaction, "transaction");
        Objects.requireNonNull(facility, "facility");
        Objects.requireNonNull(facilityId, "facilityId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(recovery, "recovery");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("transient data queue name must be 1 to 4 characters: " + name);
        }
        if (maxRecordLength < 1 || maxRecordLength > 32767) {
            throw new IllegalArgumentException("record length must be 1 to 32767: " + name);
        }
        if (triggerLevel < 0 || triggerLevel > 32767) {
            throw new IllegalArgumentException("trigger level must be 0 to 32767: " + name);
        }
        if (triggerLevel > 0 && transaction.isEmpty()) {
            throw new IllegalArgumentException("a trigger level requires TRANSID: " + name);
        }
        if (facility == Facility.FILE && facilityId.isPresent()) {
            throw new IllegalArgumentException("FACILITYID must be blank for ATIFACILITY(FILE): " + name);
        }
        if (facility != Facility.FILE && userId.isPresent()) {
            throw new IllegalArgumentException("USERID is valid only with ATIFACILITY(FILE): " + name);
        }
        facilityId.ifPresent(value -> {
            if (!NAME.matcher(value).matches()) {
                throw new IllegalArgumentException("FACILITYID must be 1 to 4 characters: " + value);
            }
        });
        userId.ifPresent(value -> {
            if (!USER.matcher(value).matches()) {
                throw new IllegalArgumentException("USERID must be 1 to 8 characters: " + value);
            }
        });
    }

    /** 回復不能のキュー。 */
    public CicsTransientDataQueueDefinition(String name, int maxRecordLength, int triggerLevel,
                                            Optional<TransId> transaction, Facility facility,
                                            Optional<String> facilityId, Optional<String> userId) {
        this(name, maxRecordLength, triggerLevel, transaction, facility, facilityId, userId, Recovery.NONE);
    }

    /** RECOVSTATUS を替えた定義。 */
    public CicsTransientDataQueueDefinition withRecovery(Recovery value) {
        return new CicsTransientDataQueueDefinition(name, maxRecordLength, triggerLevel, transaction, facility,
                facilityId, userId, value);
    }

    /** trigger level を持たないキュー。 */
    public CicsTransientDataQueueDefinition(String name, int maxRecordLength) {
        this(name, maxRecordLength, 0, Optional.empty(), Facility.FILE, Optional.empty(), Optional.empty());
    }

    /** trigger level で task を起こすか。 */
    public boolean triggers() {
        return triggerLevel > 0;
    }

    /** TERMINAL の task を出す端末。FACILITYID を書かなければキューの名前 (TDQUEUE の定義)。 */
    public String terminalId() {
        return facilityId.orElse(name);
    }
}
