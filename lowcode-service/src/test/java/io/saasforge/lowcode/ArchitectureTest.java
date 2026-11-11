package io.saasforge.lowcode;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.saasforge.architecture.ForgeArchitectureRules;
import org.junit.jupiter.api.Test;

/**
 * 与 {@code ForgeArchitectureTest} 执行相同的共享规则，只有一处豁免：连接 Tenant 外部数据库的连接器可以使用原生 JDBC。
 * 共享的 persistence 规则无法只关掉其中的 JDBC 检查，因此该类整体跳过 persistence 规则（含 SQL 注解与 Wrapper 检查）；
 * 该类不使用 MyBatis，代码审查时确认它只做外部连接。
 *
 * <p>平台规则禁止业务代码使用 JDBC，是为了让服务自己的库都经 MyBatis-Plus 拦截器注入租户隔离；外部数据库不在此列，
 * 平台暂无豁免机制，因此不再继承 {@code ForgeArchitectureTest}（其测试方法为 final）。平台提供豁免后恢复继承。
 * 见 saas-forge-v2 {@code .scratch/lowcode-validation/issues/06-external-jdbc-exemption.md}。
 */
class ArchitectureTest {
    private static final String EXTERNAL_JDBC = "io.saasforge.lowcode.datasource.service.ExternalJdbcConnector";

    @Test
    void architecture() {
        String root = getClass().getPackageName();
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(root, "io.saasforge.sdk");
        ForgeArchitectureRules.persistence().check(classes.that(notExternalJdbc()));
        ForgeArchitectureRules.scheduling().check(classes);
        ForgeArchitectureRules.sdkDependencies().check(classes);
        ForgeArchitectureRules.sdkCycles().check(classes);
        ForgeArchitectureRules.iamMappers().check(classes);
        ForgeArchitectureRules.apiResponses().check(classes);
        ForgeArchitectureRules.serviceStructure(root).check(classes);
        ForgeArchitectureRules.featureCycles(root).check(classes);
    }

    // 只豁免连接器本身（含其内部类），不按包豁免，免得 JDBC 用法扩散到其他类
    private static DescribedPredicate<JavaClass> notExternalJdbc() {
        return DescribedPredicate.describe(
                "not " + EXTERNAL_JDBC,
                type -> !type.getName().equals(EXTERNAL_JDBC)
                        && !type.getName().startsWith(EXTERNAL_JDBC + "$"));
    }
}
