package io.saasforge.lowcode.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.saasforge.lowcode.datasource.entity.Dataset;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Dataset 只做功能权限；所有方法由 SDK 注入租户条件，不受 Data Policy 过滤。 */
@Mapper
public interface DatasetMapper extends BaseMapper<Dataset> {
    /** 按更新时间倒序、ID 稳定分页，排除逻辑删除。 */
    List<Dataset> selectPage(@Param("limit") int limit, @Param("offset") long offset);
    /** 删除保护使用的当前租户全部有效 Dataset；仍受租户隔离，不受 Screen Data Policy 影响。 */
    List<Dataset> selectReferences(@Param("dataSourceId") UUID dataSourceId);
    /** 引用写入和删除共享的目标行锁；只锁当前 Tenant 的有效记录，保持 Tenant 与 RLS。 */
    Dataset selectForUpdate(@Param("id") UUID id);
}
