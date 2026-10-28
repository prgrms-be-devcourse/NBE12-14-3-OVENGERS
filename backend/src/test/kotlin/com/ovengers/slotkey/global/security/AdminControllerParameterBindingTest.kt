package com.ovengers.slotkey.global.security

import com.ovengers.slotkey.inquiry.controller.AdminInquiryController
import com.ovengers.slotkey.inquiry.dto.request.InquiryAnswerRequest
import com.ovengers.slotkey.inquiry.entity.InquiryStatus
import com.ovengers.slotkey.member.controller.AdminMemberController
import com.ovengers.slotkey.member.dto.request.MemberCreditGrantRequest
import com.ovengers.slotkey.member.dto.request.MemberStatusChangeRequest
import com.ovengers.slotkey.member.entity.MemberStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.core.MethodParameter
import org.springframework.core.ParameterNameDiscoverer
import org.springframework.core.annotation.SynthesizingMethodParameter
import org.springframework.data.domain.Pageable
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.support.ConfigurableWebBindingInitializer
import org.springframework.web.bind.support.DefaultDataBinderFactory
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.method.annotation.RequestParamMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.mvc.method.annotation.PathVariableMethodArgumentResolver
import java.lang.reflect.Constructor
import java.lang.reflect.Method

@DisplayName("관리자 컨트롤러 파라미터 이름 부재 환경 바인딩 검증")
class AdminControllerParameterBindingTest {
    private val nullDiscoverer =
        object : ParameterNameDiscoverer {
            override fun getParameterNames(method: Method): Array<String>? = null

            override fun getParameterNames(ctor: Constructor<*>): Array<String>? = null
        }

    private val binderFactory: WebDataBinderFactory =
        DefaultDataBinderFactory(ConfigurableWebBindingInitializer())
    private val requestParamResolver = RequestParamMethodArgumentResolver(false)
    private val pathVariableResolver = PathVariableMethodArgumentResolver()

    private fun createParameter(
        method: Method,
        paramIndex: Int,
    ): MethodParameter {
        val parameter = SynthesizingMethodParameter(method, paramIndex)
        parameter.initParameterNameDiscovery(nullDiscoverer)
        return parameter
    }

    // 이름 없는 @RequestParam 대조군
    fun controlMissingParamNameMethod(
        @Suppress("UNUSED_PARAMETER") @RequestParam(required = true) missingParam: String,
    ) {
    }

    @Test
    @DisplayName("대조군: 파라미터 이름 정보가 없는 환경에서 이름이 생략된 @RequestParam은 IllegalArgumentException을 던진다")
    fun missingNameControlThrowsIllegalArgumentException() {
        val method = javaClass.getMethod("controlMissingParamNameMethod", String::class.java)
        val parameter = createParameter(method, 0)

        assertThat(parameter.parameterName).isNull()

        val request = MockHttpServletRequest()
        request.setParameter("missingParam", "value")
        val webRequest = ServletWebRequest(request)
        val mavContainer = ModelAndViewContainer()

        assertThatThrownBy {
            requestParamResolver.resolveArgument(parameter, mavContainer, webRequest, binderFactory)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("not specified, and parameter name information not available via reflection")
    }

    @Test
    @DisplayName("회원 목록 @RequestParam: 파라미터 이름 정보가 없어도 status와 keyword가 정상 해석된다")
    fun adminMemberGetMembersParamsBound() {
        val method = getMembersMethod()

        val statusParam = createParameter(method, 0)
        val keywordParam = createParameter(method, 1)

        assertThat(statusParam.parameterName).isNull()
        assertThat(keywordParam.parameterName).isNull()

        val request = MockHttpServletRequest()
        request.setParameter("status", "ACTIVE")
        request.setParameter("keyword", "sample")
        val webRequest = ServletWebRequest(request)
        val mavContainer = ModelAndViewContainer()

        val statusValue = requestParamResolver.resolveArgument(statusParam, mavContainer, webRequest, binderFactory)
        val keywordValue = requestParamResolver.resolveArgument(keywordParam, mavContainer, webRequest, binderFactory)

        assertThat(statusValue).isEqualTo(MemberStatus.ACTIVE)
        assertThat(keywordValue).isEqualTo("sample")
    }

    @Test
    @DisplayName("회원 목록 @RequestParam: 파라미터 생략 시 null로 해석된다")
    fun adminMemberGetMembersParamsOmittedAreNull() {
        val method = getMembersMethod()

        val statusParam = createParameter(method, 0)
        val keywordParam = createParameter(method, 1)

        val webRequest = ServletWebRequest(MockHttpServletRequest())
        val mavContainer = ModelAndViewContainer()

        val statusValue = requestParamResolver.resolveArgument(statusParam, mavContainer, webRequest, binderFactory)
        val keywordValue = requestParamResolver.resolveArgument(keywordParam, mavContainer, webRequest, binderFactory)

        assertThat(statusValue).isNull()
        assertThat(keywordValue).isNull()
    }

    @Test
    @DisplayName("문의 목록 @RequestParam: 파라미터 이름 정보가 없어도 status가 정상 해석된다")
    fun adminInquiryGetInquiriesStatusBound() {
        val method =
            AdminInquiryController::class.java.getMethod(
                "getInquiries",
                InquiryStatus::class.java,
                Pageable::class.java,
            )

        val statusParam = createParameter(method, 0)
        assertThat(statusParam.parameterName).isNull()

        val requestWithStatus = MockHttpServletRequest()
        requestWithStatus.setParameter("status", "WAITING")
        val webRequestWithStatus = ServletWebRequest(requestWithStatus)
        val mavContainer = ModelAndViewContainer()

        val statusValue =
            requestParamResolver.resolveArgument(statusParam, mavContainer, webRequestWithStatus, binderFactory)
        assertThat(statusValue).isEqualTo(InquiryStatus.WAITING)

        val webRequestEmpty = ServletWebRequest(MockHttpServletRequest())
        val emptyStatusValue =
            requestParamResolver.resolveArgument(statusParam, mavContainer, webRequestEmpty, binderFactory)
        assertThat(emptyStatusValue).isNull()
    }

    @Test
    @DisplayName("회원 @PathVariable 3곳: 파라미터 이름 정보가 없어도 memberId=42L로 정상 해석된다")
    fun adminMemberPathVariablesBound() {
        val primitiveLong = Long::class.javaPrimitiveType!!
        val suspendMethod =
            AdminMemberController::class.java.getMethod(
                "suspend",
                primitiveLong,
                MemberStatusChangeRequest::class.java,
                AuthPrincipal::class.java,
            )
        val restoreMethod =
            AdminMemberController::class.java.getMethod(
                "restore",
                primitiveLong,
                MemberStatusChangeRequest::class.java,
                AuthPrincipal::class.java,
            )
        val grantCreditMethod =
            AdminMemberController::class.java.getMethod(
                "grantCredit",
                primitiveLong,
                MemberCreditGrantRequest::class.java,
                AuthPrincipal::class.java,
            )

        val suspendParam = createParameter(suspendMethod, 0)
        val restoreParam = createParameter(restoreMethod, 0)
        val grantParam = createParameter(grantCreditMethod, 0)

        assertThat(suspendParam.parameterName).isNull()
        assertThat(restoreParam.parameterName).isNull()
        assertThat(grantParam.parameterName).isNull()

        val request = MockHttpServletRequest()
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, mapOf("memberId" to "42"))
        val webRequest = ServletWebRequest(request)
        val mavContainer = ModelAndViewContainer()

        val suspendId = pathVariableResolver.resolveArgument(suspendParam, mavContainer, webRequest, binderFactory)
        val restoreId = pathVariableResolver.resolveArgument(restoreParam, mavContainer, webRequest, binderFactory)
        val grantId = pathVariableResolver.resolveArgument(grantParam, mavContainer, webRequest, binderFactory)

        assertThat(suspendId).isEqualTo(42L)
        assertThat(restoreId).isEqualTo(42L)
        assertThat(grantId).isEqualTo(42L)
    }

    @Test
    @DisplayName("문의 @PathVariable 2곳: 파라미터 이름 정보가 없어도 inquiryId=7L로 정상 해석된다")
    fun adminInquiryPathVariablesBound() {
        val primitiveLong = Long::class.javaPrimitiveType!!
        val getInquiryMethod = AdminInquiryController::class.java.getMethod("getInquiry", primitiveLong)
        val answerMethod =
            AdminInquiryController::class.java.getMethod(
                "answer",
                AuthPrincipal::class.java,
                primitiveLong,
                InquiryAnswerRequest::class.java,
            )

        val getInquiryParam = createParameter(getInquiryMethod, 0)
        val answerParam = createParameter(answerMethod, 1)

        assertThat(getInquiryParam.parameterName).isNull()
        assertThat(answerParam.parameterName).isNull()

        val request = MockHttpServletRequest()
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, mapOf("inquiryId" to "7"))
        val webRequest = ServletWebRequest(request)
        val mavContainer = ModelAndViewContainer()

        val getInquiryId = pathVariableResolver.resolveArgument(getInquiryParam, mavContainer, webRequest, binderFactory)
        val answerId = pathVariableResolver.resolveArgument(answerParam, mavContainer, webRequest, binderFactory)

        assertThat(getInquiryId).isEqualTo(7L)
        assertThat(answerId).isEqualTo(7L)
    }

    private fun getMembersMethod(): Method =
        AdminMemberController::class.java.getMethod(
            "getMembers",
            MemberStatus::class.java,
            String::class.java,
            Pageable::class.java,
            AuthPrincipal::class.java,
        )
}
