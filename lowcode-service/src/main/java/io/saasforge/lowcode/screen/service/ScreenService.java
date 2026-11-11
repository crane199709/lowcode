package io.saasforge.lowcode.screen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.saasforge.lowcode.screen.entity.Screen;
import io.saasforge.lowcode.screen.mapper.ScreenMapper;
import io.saasforge.lowcode.screen.model.ScreenDraft;
import io.saasforge.sdk.abac.data.DataPermission;
import io.saasforge.sdk.core.CurrentActorProvider;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.security.MembershipGrantStore;
import io.saasforge.sdk.storage.ObjectStorage;
import io.saasforge.sdk.web.PageQuery;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 大屏的基础维护。业务代码不含租户与数据权限条件：隔离与按 Data Scope 过滤都由 SDK 注入 SQL。
 *
 * <p>超出数据范围的大屏与不存在的大屏一样返回 {@code screen.notFound}，不泄露它是否存在。
 */
@Service
public class ScreenService {
    /** 大屏在清单中声明的 Data Resource 编码。 */
    static final String DATA_RESOURCE = "screen";

    private final ScreenMapper screenMapper;
    private final DataPermission dataPermission;
    private final JsonMapper json;
    private final MembershipGrantStore grants;
    private final CurrentActorProvider actors;
    private final ObjectStorage storage;

    public ScreenService(
            ScreenMapper screenMapper,
            DataPermission dataPermission,
            JsonMapper json,
            MembershipGrantStore grants,
            CurrentActorProvider actors,
            ObjectStorage storage) {
        this.screenMapper = screenMapper;
        this.dataPermission = dataPermission;
        this.json = json;
        this.grants = grants;
        this.actors = actors;
        this.storage = storage;
    }

    /**
     * 成员创建的大屏归属本人与其主部门；INSERT 不经 SQL 过滤，先校验新大屏在自己的数据范围内，否则 403，
     * 避免创建出自己看不到的数据。系统操作（无认证上下文）创建的大屏没有归属。
     */
    public Screen create(String name) {
        return create(name, ScreenDraft.empty());
    }

    public Screen create(String name, ScreenDraft draft) {
        if (draft != null) {
            validateQueries(draft);
        }
        var screen = new Screen();
        screen.setName(name);
        screen.setDraft(json.writeValueAsString(draft == null ? ScreenDraft.empty() : draft));
        dataPermission.currentSubject().ifPresent(subject -> {
            screen.setOwnerId((UUID) subject.get("subject.membershipId"));
            screen.setDepartmentPath((String) subject.get("subject.primaryDepartmentPath"));
        });
        dataPermission.check(DATA_RESOURCE, attributes(screen));
        validateImages(draft);
        screenMapper.insert(screen);
        // 创建时间由数据库生成，重新读取才能返回与之后查询一致的值
        return get(screen.getId());
    }

    public List<Screen> list(PageQuery query) {
        return screenMapper.selectPage(query.size(), query.offset(), !canEdit());
    }

    public long count() {
        return screenMapper.selectCount(new LambdaQueryWrapper<Screen>().isNotNull(!canEdit(), Screen::getPublished));
    }

    public boolean canEdit() {
        // 系统异步任务沿用 SDK 的约定：只受租户隔离，不要求成员权限；HTTP 仍由方法权限先拦截。
        return dataPermission.currentSubject().isEmpty()
                || grants.current().permissions().contains("screen:edit");
    }

    public Screen readable(UUID id) {
        Screen screen = get(id);
        if (!canEdit() && screen.getPublished() == null) {
            throw notFound(id);
        }
        return screen;
    }

    public Screen get(UUID id) {
        Screen screen = screenMapper.selectById(id);
        if (screen == null) {
            throw notFound(id);
        }
        return screen;
    }

    public Screen rename(UUID id, String name) {
        var screen = new Screen();
        screen.setId(id);
        screen.setName(name);
        if (screenMapper.updateById(screen) == 0) {
            throw notFound(id);
        }
        return get(id);
    }

    public Screen update(UUID id, String name, ScreenDraft draft) {
        get(id);
        validateImages(draft);
        var screen = new Screen();
        screen.setId(id);
        screen.setName(name);
        if (draft != null) {
            validateQueries(draft);
            screen.setDraft(json.writeValueAsString(draft));
        }
        if (screenMapper.updateById(screen) == 0) {
            throw notFound(id);
        }
        return get(id);
    }

    public ScreenDraft draft(Screen screen) {
        return json.readValue(screen.getDraft(), ScreenDraft.class);
    }

    public ScreenDraft saveDraft(UUID id, ScreenDraft draft) {
        validateQueries(draft);
        get(id);
        validateImages(draft);
        var screen = new Screen();
        screen.setId(id);
        screen.setDraft(json.writeValueAsString(draft));
        if (screenMapper.updateById(screen) == 0) {
            throw notFound(id);
        }
        return draft(get(id));
    }

    @Transactional
    public Screen copy(UUID id) {
        Screen original = get(id);
        String name = original.getName();
        return create(name.substring(0, Math.min(name.length(), 116)) + "（副本）", draft(original));
    }

    @Transactional
    public PublishedScreen publish(UUID id) {
        if (screenMapper.publish(id, actors.currentActor().orElse(null)) == 0) {
            throw notFound(id);
        }
        return published(id);
    }

    /** 播放与相关资源读取共同使用此入口，只解析可见 Screen 的已发布快照。 */
    public PublishedScreen published(UUID id) {
        Screen screen = get(id);
        return published(screen);
    }

    /** 复用已按数据范围查询的行，避免列表响应逐条重新查询及跨次读取不一致。 */
    public PublishedScreen published(Screen screen) {
        if (screen.getPublished() == null) {
            throw notFound(screen.getId());
        }
        return new PublishedScreen(
                screen.getId(),
                screen.getPublishedName(),
                json.readValue(screen.getPublished(), ScreenDraft.class),
                screen.getPublishedAt());
    }

    public record PublishedScreen(UUID id, String name, ScreenDraft definition, OffsetDateTime publishedAt) {}

    public void delete(UUID id) {
        if (screenMapper.deleteById(id) == 0) {
            throw notFound(id);
        }
    }

    private void validateImages(ScreenDraft draft) {
        if (draft != null) {
            for (UUID objectId : draft.imageObjectIds()) {
                var image = storage.get(ScreenImageService.PURPOSE, objectId);
                ScreenImageService.validateMediaType(image.filename(), image.contentType());
            }
        }
    }

    private void validateQueries(ScreenDraft draft) {
        for (var widget : draft.widgets()) {
            if (widget.isQueryWidget()) {
                ScreenQueryService.config(json, widget);
            }
        }
    }

    private static Map<String, Object> attributes(Screen screen) {
        var attributes = new HashMap<String, Object>();
        attributes.put("ownerId", screen.getOwnerId());
        attributes.put("departmentPath", screen.getDepartmentPath());
        return attributes;
    }

    private static ForgeException notFound(UUID id) {
        return ForgeException.withErrorMessage(
                ScreenErrorCode.NOT_FOUND, "Screen " + id + " not found in current tenant");
    }
}
