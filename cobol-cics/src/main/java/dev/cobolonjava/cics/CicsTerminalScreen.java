package dev.cobolonjava.cics;

import dev.cobolonjava.cics.bms.BmsScreenSnapshot;
import java.util.Objects;

/**
 * task が端末へ送った画面 (設計 79 §8)。HTML を含まない中立な状態であり、adapter が描画する。
 */
public sealed interface CicsTerminalScreen
        permits CicsTerminalScreen.MapScreen, CicsTerminalScreen.TextScreen {

    boolean keyboardRestored();

    boolean alarm();

    /** SEND MAP で作った画面。 */
    record MapScreen(BmsScreenSnapshot snapshot) implements CicsTerminalScreen {

        public MapScreen {
            Objects.requireNonNull(snapshot, "snapshot");
        }

        @Override
        public boolean keyboardRestored() {
            return snapshot.keyboardRestored();
        }

        @Override
        public boolean alarm() {
            return snapshot.alarm();
        }
    }

    /**
     * SEND TEXT または SEND CONTROL ERASE で作った、map を持たない画面。
     *
     * @param text 画面の先頭から置く文字。消去しただけなら空
     */
    record TextScreen(String text, boolean keyboardRestored, boolean alarm)
            implements CicsTerminalScreen {

        public TextScreen {
            Objects.requireNonNull(text, "text");
        }
    }
}
