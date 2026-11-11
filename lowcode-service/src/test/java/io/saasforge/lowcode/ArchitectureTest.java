package io.saasforge.lowcode;

import io.saasforge.architecture.ForgeArchitectureTest;
import java.util.Set;

class ArchitectureTest extends ForgeArchitectureTest {
    @Override
    protected Set<String> externalJdbcClasses() {
        // 代 Tenant 连接其自有数据库：库中没有租户列，MyBatis-Plus 拦截器不适用
        return Set.of("io.saasforge.lowcode.datasource.service.ExternalJdbcConnector");
    }
}
