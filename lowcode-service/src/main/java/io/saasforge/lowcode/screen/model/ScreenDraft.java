package io.saasforge.lowcode.screen.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Design Resolution 与 Widget 坐标均为设计像素；修改分辨率不换算已有坐标。 */
public record ScreenDraft(
        @Min(640) @Max(7680) int designWidth,
        @Min(640) @Max(7680) int designHeight,

        @NotNull @Pattern(regexp = "#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")
        String backgroundColor,

        @NotNull @Size(max = 200) List<@NotNull @Valid Widget> widgets) {
    public static ScreenDraft empty() {
        return new ScreenDraft(1920, 1080, "#101827", List.of());
    }

    @AssertTrue(message = "组件 ID 不得重复")
    @JsonIgnore
    public boolean isUniqueWidgetIds() {
        if (widgets == null) {
            return true;
        }
        var ids = new HashSet<String>();
        for (Widget widget : widgets) {
            if (widget != null && !ids.add(widget.id())) {
                return false;
            }
        }
        return true;
    }

    public record Widget(
            @NotBlank @Size(max = 80) String id,
            @NotNull @Pattern(regexp = "TEXT") String type,
            @Min(0) @Max(7680) int x,
            @Min(0) @Max(7680) int y,
            @Min(1) @Max(7680) int width,
            @Min(1) @Max(7680) int height,
            @Min(0) @Max(10000) int zIndex,
            @NotNull JsonNode config) {
        // config 保留按 Widget 类型演进的接口，但已支持类型必须在保存前验证，不能把坏配置留给渲染器。
        @AssertTrue(message = "文本卡配置无效")
        @JsonIgnore
        public boolean isValidConfig() {
            if (!"TEXT".equals(type) || config == null) {
                return true;
            }
            return config.isObject()
                    && config.path("text").isString()
                    && config.path("text").asString().length() <= 20000
                    && config.path("fontSize").isIntegralNumber()
                    && config.path("fontSize").canConvertToInt()
                    && config.path("fontSize").asInt() >= 8
                    && config.path("fontSize").asInt() <= 256
                    && config.path("color").asString("").matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")
                    && config.path("backgroundColor").asString("").matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")
                    && List.of("normal", "bold")
                            .contains(config.path("fontWeight").asString(""))
                    && List.of("left", "center", "right")
                            .contains(config.path("textAlign").asString(""));
        }
    }
}
