package io.saasforge.lowcode.screen.model;

import java.util.List;

public record WidgetData(List<OutputColumn> columns, List<List<Object>> rows, boolean truncated, int maxRows) {}
