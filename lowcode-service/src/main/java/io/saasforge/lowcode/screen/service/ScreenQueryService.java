package io.saasforge.lowcode.screen.service;

import io.saasforge.lowcode.datasource.service.DatasetQueryService;
import io.saasforge.lowcode.screen.model.ScreenDraft;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.web.PageQuery;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ScreenQueryService {
    private final ScreenService screens;
    private final DatasetQueryService datasets;
    private final JsonMapper json;

    public ScreenQueryService(ScreenService screens, DatasetQueryService datasets, JsonMapper json) {
        this.screens = screens;
        this.datasets = datasets;
        this.json = json;
    }

    public List<BindingDataset> catalog(UUID screenId, PageQuery page) {
        screens.get(screenId);
        return datasets.catalog(page).stream()
                .map(d -> new BindingDataset(
                        d.id(),
                        d.name(),
                        d.fields().stream()
                                .map(f -> new BindingField(f.name(), f.displayName(), f.type(), f.role(), f.date()))
                                .toList()))
                .toList();
    }

    public long datasetCount() {
        return datasets.count();
    }

    public WidgetData published(UUID screenId, String widgetId) {
        var definition = screens.published(screenId).definition();
        var widget = definition.widgets().stream()
                .filter(w -> w.id().equals(widgetId))
                .findFirst()
                .orElseThrow(() -> new ForgeException(ScreenErrorCode.WIDGET_NOT_FOUND));
        return execute(widget);
    }

    public WidgetData preview(UUID screenId, ScreenDraft.Widget widget) {
        screens.get(screenId);
        return execute(widget);
    }

    private WidgetData execute(ScreenDraft.Widget widget) {
        Config config = config(json, widget);
        var result = datasets.query(config.datasetId(), config.query());
        return new WidgetData(
                result.columns().stream()
                        .map(c -> new OutputColumn(c.id(), c.label(), c.type()))
                        .toList(),
                result.rows(),
                result.truncated(),
                result.maxRows());
    }

    static Config config(JsonMapper json, ScreenDraft.Widget widget) {
        if (!"BAR".equals(widget.type()) || !widget.isValidConfig()) {
            throw new ForgeException(ScreenErrorCode.WIDGET_CONFIG_INVALID);
        }
        Config config;
        try {
            config = json.treeToValue(widget.config(), Config.class);
        } catch (RuntimeException e) {
            throw new ForgeException(ScreenErrorCode.WIDGET_CONFIG_INVALID);
        }
        DatasetQueryService.validateShape(config.query());
        return config;
    }

    // 屏幕响应由 screen 自己拥有；Controller 不得依赖其他功能包，即使仅在泛型中引用。
    public record WidgetData(List<OutputColumn> columns, List<List<Object>> rows, boolean truncated, int maxRows) {}

    public record OutputColumn(String id, String label, String type) {}

    public record BindingDataset(UUID id, String name, List<BindingField> fields) {}

    public record BindingField(String name, String displayName, String type, String role, boolean date) {}

    public record Config(UUID datasetId, DatasetQueryService.Query query, int refreshSeconds) {}
}
