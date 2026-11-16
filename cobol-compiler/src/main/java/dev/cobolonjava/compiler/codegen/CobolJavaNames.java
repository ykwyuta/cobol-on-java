package dev.cobolonjava.compiler.codegen;

import dev.cobolonjava.compiler.semantic.DataItem;
import dev.cobolonjava.compiler.semantic.ProcedureBuilder;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Java に戻す中間クラスへ、解決済みの段落名とデータ項目名を残す。 */
final class CobolJavaNames {

    private final List<String> paragraphs;
    private final Set<String> dataNames = new HashSet<>();
    private final Map<DataItem, String> data = new HashMap<>();
    private final Set<String> declaredFields = new HashSet<>();

    CobolJavaNames(List<ProcedureBuilder.Paragraph> paragraphs) {
        Set<String> names = new HashSet<>();
        this.paragraphs = paragraphs.stream().map(paragraph ->
                unique(paragraphName(paragraph.name()), names)).toList();
    }

    String paragraph(int index) {
        return paragraphs.get(index);
    }

    String item(DataItem item) {
        return data.computeIfAbsent(item, key -> {
            ArrayDeque<String> qualified = new ArrayDeque<>();
            for (DataItem part = key; part != null; part = part.parent()) {
                if (part.name() != null) {
                    qualified.addFirst(identifierPart(part.name()));
                }
            }
            return unique(String.join("$", qualified), dataNames);
        });
    }

    boolean declareField(String name) {
        return declaredFields.add(name);
    }

    private static String paragraphName(String name) {
        if (name == null) {
            return "START";
        }
        if (name.startsWith(ProcedureBuilder.ENTRY_PARAGRAPH)) {
            return "ENTRY$" + identifierPart(name.substring(ProcedureBuilder.ENTRY_PARAGRAPH.length()));
        }
        return identifierPart(name);
    }

    private static String identifierPart(String name) {
        return name.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private static String unique(String stem, Set<String> names) {
        String name = stem;
        for (int suffix = 2; !names.add(name); suffix++) {
            name = stem + "$" + suffix;
        }
        return name;
    }
}
