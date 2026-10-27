package com.ovengers.slotkey.credit.pg

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext

class PgClientTestProfileIntegrationTest {
    @Test
    @DisplayName("test 프로필은 .env 없이 실제 구현체를 제외하고 FakePgClient 하나만 로딩한다")
    fun testProfile_loadsOnlyFakePgClientWithoutDotenv() {
        AnnotationConfigApplicationContext().use { context ->
            context.environment.setActiveProfiles("test")
            context.register(TossPaymentsPgConfiguration::class.java, FakePgClient::class.java)
            context.refresh()

            assertThat(context.getBeanNamesForType(PgClient::class.java).asList())
                .singleElement()
                .satisfies(
                    java.util.function.Consumer<String> { beanName ->
                        assertThat(context.getBean(beanName))
                            .isInstanceOf(FakePgClient::class.java)
                    },
                )
        }
    }
}
