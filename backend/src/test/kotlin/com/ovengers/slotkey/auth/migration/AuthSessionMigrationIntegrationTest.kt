package com.ovengers.slotkey.auth.migration

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.configuration.FluentConfiguration
import org.junit.jupiter.api.Test
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager

internal class AuthSessionMigrationIntegrationTest {
    @Test
    fun v24DeletesLegacyRefreshTokensBeforeRequiringSessionForeignKey() {
        mysqlContainer().use { mysql ->
            mysql.start()
            val v23: Flyway = flyway(mysql).target("23").load()
            v23.migrate()

            connection(mysql).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        """
                        INSERT INTO member (
                            email, nickname, role, status, balance,
                            created_at, updated_at, withdrawn_at, email_verified_at
                        ) VALUES (
                            'legacy@example.test', 'legacy', 'USER', 'ACTIVE', 0,
                            CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), NULL, CURRENT_TIMESTAMP(6)
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        INSERT INTO refresh_token (member_id, token_hash, expires_at)
                        VALUES (1, REPEAT('a', 64), DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 1 DAY))
                        """.trimIndent(),
                    )
                }
            }
            val v24: Flyway = flyway(mysql).target("24").load()
            v24.migrate()
            assertThat(
                v24
                    .info()
                    .current()
                    .version
                    .version,
            ).isEqualTo("24")

            connection(mysql).use { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            "SELECT COUNT(*) FROM refresh_token",
                        ).use { result ->
                            result.next()
                            assertThat(result.getInt(1)).isZero()
                        }
                    statement
                        .executeQuery(
                            """
                            SELECT IS_NULLABLE FROM information_schema.columns
                            WHERE table_schema = DATABASE()
                              AND table_name = 'refresh_token'
                              AND column_name = 'auth_session_id'
                            """.trimIndent(),
                        ).use { result ->
                            assertThat(result.next()).isTrue()
                            assertThat(result.getString(1)).isEqualTo("NO")
                        }
                    statement
                        .executeQuery(
                            """
                            SELECT COUNT(*) FROM information_schema.key_column_usage
                            WHERE table_schema = DATABASE()
                              AND table_name = 'refresh_token'
                              AND column_name = 'auth_session_id'
                              AND referenced_table_name = 'auth_session'
                            """.trimIndent(),
                        ).use { result ->
                            result.next()
                            assertThat(result.getInt(1)).isEqualTo(1)
                        }
                }
            }
        }
    }

    @Test
    fun allMigrationsApplyToAnEmptyDatabase() {
        mysqlContainer().use { mysql ->
            mysql.start()
            val flyway: Flyway = flyway(mysql).load()

            flyway.migrate()

            assertThat(
                flyway
                    .info()
                    .current()
                    .version
                    .version,
            ).isEqualTo("26")
            connection(mysql).use { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            (
                                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'auth_session'"
                            ),
                        ).use { result ->
                            result.next()
                            assertThat(result.getInt(1)).isEqualTo(1)
                        }
                }
            }
            connection(mysql).use { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            (
                                "SELECT IS_NULLABLE FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'refresh_token' AND column_name = 'consumed_at'"
                            ),
                        ).use { result ->
                            assertThat(result.next()).isTrue()
                            assertThat(result.getString(1)).isEqualTo("YES")
                        }
                }
            }
            connection(mysql).use { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            (
                                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'login_attempt_bucket' AND column_name IN ('email', 'window_id', 'attempt_count')"
                            ),
                        ).use { result ->
                            result.next()
                            assertThat(result.getInt(1)).isEqualTo(3)
                        }
                }
            }
            connection(mysql).use { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            (
                                "SELECT IS_NULLABLE FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'login_attempt_bucket' AND column_name = 'attempt_count'"
                            ),
                        ).use { result ->
                            assertThat(result.next()).isTrue()
                            assertThat(result.getString(1)).isEqualTo("NO")
                        }
                }
            }
        }
    }

    private fun mysqlContainer(): MySQLContainer<*> =
        MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("slotkey_migration_test")
            .withUsername("test")
            .withPassword("test")

    private fun flyway(mysql: MySQLContainer<*>): FluentConfiguration =
        Flyway
            .configure()
            .dataSource(mysql.jdbcUrl, mysql.username, mysql.password)
            .locations("classpath:db/migration")
            .baselineOnMigrate(false)
            .validateOnMigrate(true)

    private fun connection(mysql: MySQLContainer<*>): Connection =
        DriverManager.getConnection(
            mysql.jdbcUrl,
            mysql.username,
            mysql.password,
        )
}
