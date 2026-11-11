package io.saasforge.lowcode.reference.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 测试专用并发调度；不用于验证持久结果。业务行锁保留 Tenant 与 RLS。 */
@Mapper
public interface ReferenceConcurrencyMapper {
    UUID lockScreen(@Param("id") UUID id);
    // pg_stat_activity 是无 tenant_id 的 PostgreSQL 系统视图；该例外只用于测试时序探针。
    @InterceptorIgnore(tenantLine = "true")
    int waitingScreenWrites();

    @InterceptorIgnore(tenantLine = "true")
    int waitingDatasetLocks();
}
