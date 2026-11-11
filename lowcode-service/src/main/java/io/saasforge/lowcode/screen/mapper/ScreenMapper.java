package io.saasforge.lowcode.screen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.saasforge.lowcode.screen.entity.Screen;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 大屏是 Data Resource「screen」：BaseMapper 的查询、更新与删除都按当前成员的 Data Policy 过滤。 */
@Mapper
public interface ScreenMapper extends BaseMapper<Screen> {

    /**
     * 按最近更新在前分页查询当前租户的大屏，最后按 id 排序保证翻页稳定。租户条件与当前成员的 Data Policy 条件由 SDK 注入。
     *
     * @param limit 每页条数
     * @param offset 偏移量
     * @return 当前页大屏
     */
    List<Screen> selectPage(
            @Param("limit") int limit, @Param("offset") long offset, @Param("publishedOnly") boolean publishedOnly);
    /** 将当前已保存的草稿与名称原子冻结为快照；受 Tenant 与 Data Policy 过滤。 */
    int publish(@Param("id") UUID id, @Param("updatedBy") UUID updatedBy);
}
