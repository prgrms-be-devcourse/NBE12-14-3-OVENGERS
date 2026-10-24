package com.ovengers.slotkey.global.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder

@Configuration
class PasswordEncoderConfig {
    // 회원 가입 시 비밀번호 해시 생성과 로그인 시 비밀번호 검증에 사용
    // encode(): 비밀번호 해시 생성
    // matches(): 입력한 비밀번호와 저장된 해시의 일치 여부 확인
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()
}
