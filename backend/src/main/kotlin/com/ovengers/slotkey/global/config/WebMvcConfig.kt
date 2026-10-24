package com.ovengers.slotkey.global.config

import com.ovengers.slotkey.global.security.CurrentMemberArgumentResolver
import org.springframework.context.annotation.Configuration
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/** @CurrentMember 파라미터 리졸버를 Spring MVC에 등록한다. */
@Configuration
class WebMvcConfig(
    private val currentMemberArgumentResolver: CurrentMemberArgumentResolver,
) : WebMvcConfigurer {
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(currentMemberArgumentResolver)
    }
}
