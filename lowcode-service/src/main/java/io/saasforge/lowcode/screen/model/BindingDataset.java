package io.saasforge.lowcode.screen.model;

import java.util.List;
import java.util.UUID;

public record BindingDataset(UUID id, String name, List<BindingField> fields) {}
