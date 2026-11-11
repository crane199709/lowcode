package io.saasforge.lowcode;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.mssqlserver.MSSQLServerContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * 同一测试 JVM 内共享的 PostgreSQL 与 Redis 容器。
 *
 * <p>各测试类原先各自启动两个容器，容器启动占了测试总时长的近四分之一。测试类的 Spring 配置各不相同、上下文无法复用，
 * 因此只共享容器、不共享数据：每个测试类分到一个新建数据库（重新执行部署用的初始化脚本）与一个清空的 Redis 库，
 * 与原先「每个类一套空容器」的隔离等价。容器在 JVM 退出后由 Testcontainers 回收。
 */
final class SharedContainers {
    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static final AtomicInteger REDIS_DATABASES = new AtomicInteger();
    // redis 默认 16 个库；每个 fork 的测试类少于此数，超出时循环复用并先清空
    private static final int REDIS_DATABASE_COUNT = 16;

    private SharedContainers() {}

    /** 为调用的测试类新建一个已执行初始化脚本的数据库。 */
    static Postgres postgres() {
        String name = "forge_" + DATABASES.incrementAndGet();
        exec(PostgresHolder.CONTAINER, "psql", "-U", "test", "-d", "forge", "-c", "CREATE DATABASE " + name);
        exec(
                PostgresHolder.CONTAINER,
                "psql",
                "-U",
                "test",
                "-d",
                name,
                "-v",
                "ON_ERROR_STOP=1",
                "-f",
                "/docker-entrypoint-initdb.d/01-init.sql");
        return new Postgres(PostgresHolder.CONTAINER.getJdbcUrl().replace("/forge?", "/" + name + "?"));
    }

    /**
     * 共享 PostgreSQL 容器中的一个普通账号，供数据源测试充当 Tenant 自有的外部数据库。密码与其他任何字段都不相同，
     * 测试才能断言接口不回显它。
     */
    static ExternalDatabase externalPostgres() {
        var container = PostgresHolder.CONTAINER;
        exec(
                container,
                "psql",
                "-U",
                "test",
                "-d",
                "forge",
                "-v",
                "ON_ERROR_STOP=1",
                "-c",
                "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '" + EXTERNAL_USER + "') THEN"
                        + " CREATE ROLE " + EXTERNAL_USER + " LOGIN PASSWORD '" + EXTERNAL_PASSWORD
                        + "'; END IF; END $$;"
                        + " GRANT CONNECT ON DATABASE forge TO " + EXTERNAL_USER + ";");
        return new ExternalDatabase(
                container.getHost(), container.getMappedPort(5432), "forge", EXTERNAL_USER, EXTERNAL_PASSWORD);
    }

    private static final String EXTERNAL_USER = "tenant_reader";
    // 同时满足 SQL Server 的密码复杂度要求
    private static final String EXTERNAL_PASSWORD = "Ext3rnal-Secr3t";

    /** 充当 Tenant 外部数据库的 MySQL，首次调用时启动。 */
    static ExternalDatabase externalMysql() {
        return external(MysqlHolder.CONTAINER, MysqlHolder.CONTAINER.getDatabaseName(), 3306);
    }

    /** 充当 Tenant 外部数据库的 SQL Server（只有 sa 账号），首次调用时启动。 */
    static ExternalDatabase externalSqlServer() {
        return external(SqlServerHolder.CONTAINER, "master", 1433);
    }

    /** 充当 Tenant 外部数据库的 Oracle，库名为服务名；启动慢，只在 slow-dialects profile 中使用。 */
    static ExternalDatabase externalOracle() {
        return external(OracleHolder.CONTAINER, OracleHolder.CONTAINER.getDatabaseName(), 1521);
    }

    private static ExternalDatabase external(JdbcDatabaseContainer<?> container, String database, int port) {
        return new ExternalDatabase(
                container.getHost(),
                container.getMappedPort(port),
                database,
                container.getUsername(),
                container.getPassword());
    }

    /** 为调用的测试类分配一个清空的 Redis 库。 */
    static Redis redis() {
        int database = REDIS_DATABASES.getAndIncrement() % REDIS_DATABASE_COUNT;
        exec(RedisHolder.CONTAINER, "redis-cli", "-n", String.valueOf(database), "FLUSHDB");
        return new Redis(RedisHolder.CONTAINER, database);
    }

    private static void exec(GenericContainer<?> container, String... command) {
        try {
            ExecResult result = container.execInContainer(command);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException(String.join(" ", command) + " failed: " + result.getStderr());
            }
        } catch (IOException error) {
            throw new IllegalStateException(error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }

    // 访问方法与 Testcontainers 容器同名，测试类原有的属性注册与连接代码无需改动
    record Postgres(String jdbcUrl) {
        String getJdbcUrl() {
            return jdbcUrl;
        }
    }

    record ExternalDatabase(String host, int port, String database, String username, String password) {}

    record Redis(GenericContainer<?> container, int database) {
        String getHost() {
            return container.getHost();
        }

        Integer getMappedPort(int port) {
            return container.getMappedPort(port);
        }
    }

    // 持有类按需启动：只用 PostgreSQL 的测试 JVM 不必启动 Redis
    private static final class PostgresHolder {
        static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("forge")
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("postgres"), "/docker-entrypoint-initdb.d/");

        static {
            CONTAINER.start();
        }
    }

    private static final class MysqlHolder {
        static final MySQLContainer CONTAINER =
                new MySQLContainer("mysql:8.4").withUsername(EXTERNAL_USER).withPassword(EXTERNAL_PASSWORD);

        static {
            CONTAINER.start();
        }
    }

    // 镜像只有 amd64，Apple Silicon 上经 Rosetta 运行
    private static final class SqlServerHolder {
        static final MSSQLServerContainer CONTAINER = new MSSQLServerContainer(
                        "mcr.microsoft.com/mssql/server:2022-latest")
                .acceptLicense()
                .withPassword(EXTERNAL_PASSWORD);

        static {
            CONTAINER.start();
        }
    }

    private static final class OracleHolder {
        static final OracleContainer CONTAINER = new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
                .withUsername(EXTERNAL_USER)
                .withPassword(EXTERNAL_PASSWORD);

        static {
            CONTAINER.start();
        }
    }

    private static final class RedisHolder {
        static final GenericContainer<?> CONTAINER = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

        static {
            CONTAINER.start();
        }
    }
}
