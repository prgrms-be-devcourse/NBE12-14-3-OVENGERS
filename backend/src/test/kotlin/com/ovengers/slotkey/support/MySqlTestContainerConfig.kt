package com.ovengers.slotkey.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.MySQLContainer

@TestConfiguration(proxyBeanMethods = false)
class MySqlTestContainerConfig {
    @Bean @ServiceConnection
    fun mysqlContainer(): MySQLContainer<*> =
        MySQLContainer("mysql:8.4")
            .withDatabaseName("slotkey_test")
            .withUsername("test")
            .withPassword("test")
}
