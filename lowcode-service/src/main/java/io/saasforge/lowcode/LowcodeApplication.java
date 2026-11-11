package io.saasforge.lowcode;

import io.saasforge.sdk.provisioning.TenantInitializer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class LowcodeApplication {

    @Bean
    TenantInitializer tenantInitializer() {
        return tenant -> {
            // 业务对象由 Tenant 自行创建；声明初始化器让 SDK 真实消费开通事件并回报成功。
        };
    }

    public static void main(String[] args) {
        SpringApplication.run(LowcodeApplication.class, args);
    }
}
