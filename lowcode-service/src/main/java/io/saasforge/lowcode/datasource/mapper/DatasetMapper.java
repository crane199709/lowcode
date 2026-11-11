package io.saasforge.lowcode.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.saasforge.lowcode.datasource.entity.Dataset;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Dataset 只做功能权限；所有方法由 SDK 注入租户条件，不受 Data Policy 过滤。 */
@Mapper
public interface DatasetMapper extends BaseMapper<Dataset> {
    /** 按更新时间倒序、ID 稳定分页，排除逻辑删除。 */
    List<Dataset> selectPage(@Param("limit") int limit, @Param("offset") long offset);
}
