package io.saasforge.lowcode.screen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.saasforge.lowcode.screen.entity.Screen;
import io.saasforge.lowcode.screen.mapper.ScreenMapper;
import io.saasforge.sdk.abac.data.DataPermission;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.web.PageQuery;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

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

    public ScreenService(ScreenMapper screenMapper, DataPermission dataPermission) {
        this.screenMapper = screenMapper;
        this.dataPermission = dataPermission;
    }

    /**
     * 成员创建的大屏归属本人与其主部门；INSERT 不经 SQL 过滤，先校验新大屏在自己的数据范围内，否则 403，
     * 避免创建出自己看不到的数据。系统操作（无认证上下文）创建的大屏没有归属。
     */
    public Screen create(String name) {
        var screen = new Screen();
        screen.setName(name);
        dataPermission.currentSubject().ifPresent(subject -> {
            screen.setOwnerId((UUID) subject.get("subject.membershipId"));
            screen.setDepartmentPath((String) subject.get("subject.primaryDepartmentPath"));
        });
        dataPermission.check(DATA_RESOURCE, attributes(screen));
        screenMapper.insert(screen);
        // 创建时间由数据库生成，重新读取才能返回与之后查询一致的值
        return get(screen.getId());
    }

    public List<Screen> list(PageQuery query) {
        return screenMapper.selectPage(query.size(), query.offset());
    }

    public long count() {
        return screenMapper.selectCount(new LambdaQueryWrapper<>());
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

    public void delete(UUID id) {
        if (screenMapper.deleteById(id) == 0) {
            throw notFound(id);
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
