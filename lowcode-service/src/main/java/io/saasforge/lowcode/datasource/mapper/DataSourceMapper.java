package io.saasforge.lowcode.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.saasforge.lowcode.datasource.entity.DataSource;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Data Source 只做功能权限，不是 Data Resource：本接口的方法只有租户条件，不受 Data Policy 过滤。 */
@Mapper
public interface DataSourceMapper extends BaseMapper<DataSource> {

    /**
     * 按最近更新在前分页查询当前租户的数据源，最后按 id 排序保证翻页稳定。租户条件由 SDK 注入。
     *
     * @param limit 每页条数
     * @param offset 偏移量
     * @return 当前页数据源
     */
    List<DataSource> selectPage(@Param("limit") int limit, @Param("offset") long offset);
    /** 分页列出 Dataset 可选的 Data Source，只返回标识与名称，不暴露连接信息。 */
    List<DataSource> selectDatasetPage(@Param("limit") int limit, @Param("offset") long offset);
}
