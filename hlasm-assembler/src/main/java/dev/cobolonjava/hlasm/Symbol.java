package dev.cobolonjava.hlasm;

/**
 * 名前欄に書かれた記号。
 *
 * <p>{@code length} は長さ属性 ({@code L'}) である。HLASM では記号の長さ属性が
 * {@code MVC A,B} のような明示長のない SS 形式の長さを決めるため、値と別に持つ必要がある。
 *
 * @param name    記号の綴り (大文字)
 * @param value   値と再配置属性
 * @param length  長さ属性。{@code EQU} で明示されなければ定義した文が決める
 */
public record Symbol(String name, Value value, int length) {
}
