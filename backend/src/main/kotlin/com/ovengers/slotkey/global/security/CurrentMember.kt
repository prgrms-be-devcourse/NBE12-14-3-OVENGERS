package com.ovengers.slotkey.global.security

/** 컨트롤러 파라미터에 현재 인증된 회원을 주입한다. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class CurrentMember
