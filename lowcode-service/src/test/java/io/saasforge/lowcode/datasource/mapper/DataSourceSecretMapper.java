package io.saasforge.lowcode.datasource.mapper;

import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 测试专用：直接读取数据源表里的密文，验证密码与请求头值不是明文存储。 */
@Mapper
public interface DataSourceSecretMapper {
    /**
     * 读取指定数据源的两列密文。
     *
     * @param id 数据源 ID
     * @return 列名到密文的映射
     */
    Map<String, Object> selectSecrets(@Param("id") UUID id);
}
