package io.saasforge.lowcode.datasource.service;

import java.util.UUID;

/** 删除 Dataset 前查询 Screen 的引用；实现只读取自身功能包，不依赖 Dataset 查询链。 */
public interface DatasetReferences {
    DeletionReferences find(UUID datasetId);
}
