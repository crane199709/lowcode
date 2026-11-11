package io.saasforge.lowcode.datasource.service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** 仅返回可公开的名称；隐藏大屏的存在只用通用提示表达。 */
public record DeletionReferences(List<Reference> references, boolean hiddenReferences) {
    public record Reference(UUID id, String name) {}

    public boolean blocked() {
        return hiddenReferences || !references.isEmpty();
    }

    public String summary() {
        String names = references.stream().map(Reference::name).collect(Collectors.joining("、"));
        return hiddenReferences ? (names.isEmpty() ? "其他无权查看的大屏" : names + "、其他无权查看的大屏") : names;
    }
}
