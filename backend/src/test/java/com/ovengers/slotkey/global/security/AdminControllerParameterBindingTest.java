package com.ovengers.slotkey.global.security;

import com.ovengers.slotkey.inquiry.controller.AdminInquiryController;
import com.ovengers.slotkey.inquiry.entity.InquiryStatus;
import com.ovengers.slotkey.member.controller.AdminMemberController;
import com.ovengers.slotkey.member.entity.MemberStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.SynthesizingMethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.support.ConfigurableWebBindingInitializer;
import org.springframework.web.bind.support.DefaultDataBinderFactory;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.annotation.RequestParamMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.PathVariableMethodArgumentResolver;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("관리자 컨트롤러 파라미터 이름 부재 환경 바인딩 검증")
class AdminControllerParameterBindingTest {

    private final ParameterNameDiscoverer nullDiscoverer = new ParameterNameDiscoverer() {
        @Override
        public String[] getParameterNames(Method method) {
            return null;
        }

        @Override
        public String[] getParameterNames(Constructor<?> ctor) {
            return null;
        }
    };

    private final WebDataBinderFactory binderFactory =
            new DefaultDataBinderFactory(new ConfigurableWebBindingInitializer());
    private final RequestParamMethodArgumentResolver requestParamResolver =
            new RequestParamMethodArgumentResolver(false);
    private final PathVariableMethodArgumentResolver pathVariableResolver =
            new PathVariableMethodArgumentResolver();

    private MethodParameter createParameter(Method method, int paramIndex) {
        MethodParameter parameter = new SynthesizingMethodParameter(method, paramIndex);
        parameter.initParameterNameDiscovery(nullDiscoverer);
        return parameter;
    }

    // 이름 없는 @RequestParam 대조군
    public void controlMissingParamNameMethod(@RequestParam(required = true) String missingParam) {
    }

    @Test
    @DisplayName("대조군: 파라미터 이름 정보가 없는 환경에서 이름이 생략된 @RequestParam은 IllegalArgumentException을 던진다")
    void missingNameControlThrowsIllegalArgumentException() throws NoSuchMethodException {
        Method method = getClass().getMethod("controlMissingParamNameMethod", String.class);
        MethodParameter parameter = createParameter(method, 0);

        assertThat(parameter.getParameterName()).isNull();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("missingParam", "value");
        NativeWebRequest webRequest = new ServletWebRequest(request);
        ModelAndViewContainer mavContainer = new ModelAndViewContainer();

        assertThatThrownBy(() -> requestParamResolver.resolveArgument(
                parameter, mavContainer, webRequest, binderFactory
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("not specified, and parameter name information not available via reflection");
    }

    @Test
    @DisplayName("회원 목록 @RequestParam: 파라미터 이름 정보가 없어도 status와 keyword가 정상 해석된다")
    void adminMemberGetMembersParamsBound() throws Exception {
        Method method = AdminMemberController.class.getMethod(
                "getMembers", MemberStatus.class, String.class,
                org.springframework.data.domain.Pageable.class, AuthPrincipal.class);

        MethodParameter statusParam = createParameter(method, 0);
        MethodParameter keywordParam = createParameter(method, 1);

        assertThat(statusParam.getParameterName()).isNull();
        assertThat(keywordParam.getParameterName()).isNull();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("status", "ACTIVE");
        request.setParameter("keyword", "sample");
        NativeWebRequest webRequest = new ServletWebRequest(request);
        ModelAndViewContainer mavContainer = new ModelAndViewContainer();

        Object statusValue = requestParamResolver.resolveArgument(statusParam, mavContainer, webRequest, binderFactory);
        Object keywordValue = requestParamResolver.resolveArgument(keywordParam, mavContainer, webRequest, binderFactory);

        assertThat(statusValue).isEqualTo(MemberStatus.ACTIVE);
        assertThat(keywordValue).isEqualTo("sample");
    }

    @Test
    @DisplayName("회원 목록 @RequestParam: 파라미터 생략 시 null로 해석된다")
    void adminMemberGetMembersParamsOmittedAreNull() throws Exception {
        Method method = AdminMemberController.class.getMethod(
                "getMembers", MemberStatus.class, String.class,
                org.springframework.data.domain.Pageable.class, AuthPrincipal.class);

        MethodParameter statusParam = createParameter(method, 0);
        MethodParameter keywordParam = createParameter(method, 1);

        MockHttpServletRequest request = new MockHttpServletRequest();
        NativeWebRequest webRequest = new ServletWebRequest(request);
        ModelAndViewContainer mavContainer = new ModelAndViewContainer();

        Object statusValue = requestParamResolver.resolveArgument(statusParam, mavContainer, webRequest, binderFactory);
        Object keywordValue = requestParamResolver.resolveArgument(keywordParam, mavContainer, webRequest, binderFactory);

        assertThat(statusValue).isNull();
        assertThat(keywordValue).isNull();
    }

    @Test
    @DisplayName("문의 목록 @RequestParam: 파라미터 이름 정보가 없어도 status가 정상 해석된다")
    void adminInquiryGetInquiriesStatusBound() throws Exception {
        Method method = AdminInquiryController.class.getMethod(
                "getInquiries", InquiryStatus.class, org.springframework.data.domain.Pageable.class);

        MethodParameter statusParam = createParameter(method, 0);
        assertThat(statusParam.getParameterName()).isNull();

        MockHttpServletRequest requestWithStatus = new MockHttpServletRequest();
        requestWithStatus.setParameter("status", "WAITING");
        NativeWebRequest webRequestWithStatus = new ServletWebRequest(requestWithStatus);
        ModelAndViewContainer mavContainer = new ModelAndViewContainer();

        Object statusValue = requestParamResolver.resolveArgument(statusParam, mavContainer, webRequestWithStatus, binderFactory);
        assertThat(statusValue).isEqualTo(InquiryStatus.WAITING);

        MockHttpServletRequest requestEmpty = new MockHttpServletRequest();
        NativeWebRequest webRequestEmpty = new ServletWebRequest(requestEmpty);
        Object emptyStatusValue = requestParamResolver.resolveArgument(statusParam, mavContainer, webRequestEmpty, binderFactory);
        assertThat(emptyStatusValue).isNull();
    }

    @Test
    @DisplayName("회원 @PathVariable 3곳: 파라미터 이름 정보가 없어도 memberId=42L로 정상 해석된다")
    void adminMemberPathVariablesBound() throws Exception {
        Method suspendMethod = AdminMemberController.class.getMethod(
                "suspend", long.class, com.ovengers.slotkey.member.dto.request.MemberStatusChangeRequest.class, AuthPrincipal.class);
        Method restoreMethod = AdminMemberController.class.getMethod(
                "restore", long.class, com.ovengers.slotkey.member.dto.request.MemberStatusChangeRequest.class, AuthPrincipal.class);
        Method grantCreditMethod = AdminMemberController.class.getMethod(
                "grantCredit", long.class, com.ovengers.slotkey.member.dto.request.MemberCreditGrantRequest.class, AuthPrincipal.class);

        MethodParameter suspendParam = createParameter(suspendMethod, 0);
        MethodParameter restoreParam = createParameter(restoreMethod, 0);
        MethodParameter grantParam = createParameter(grantCreditMethod, 0);

        assertThat(suspendParam.getParameterName()).isNull();
        assertThat(restoreParam.getParameterName()).isNull();
        assertThat(grantParam.getParameterName()).isNull();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("memberId", "42"));
        NativeWebRequest webRequest = new ServletWebRequest(request);
        ModelAndViewContainer mavContainer = new ModelAndViewContainer();

        Object suspendId = pathVariableResolver.resolveArgument(suspendParam, mavContainer, webRequest, binderFactory);
        Object restoreId = pathVariableResolver.resolveArgument(restoreParam, mavContainer, webRequest, binderFactory);
        Object grantId = pathVariableResolver.resolveArgument(grantParam, mavContainer, webRequest, binderFactory);

        assertThat(suspendId).isEqualTo(42L);
        assertThat(restoreId).isEqualTo(42L);
        assertThat(grantId).isEqualTo(42L);
    }

    @Test
    @DisplayName("문의 @PathVariable 2곳: 파라미터 이름 정보가 없어도 inquiryId=7L로 정상 해석된다")
    void adminInquiryPathVariablesBound() throws Exception {
        Method getInquiryMethod = AdminInquiryController.class.getMethod("getInquiry", long.class);
        Method answerMethod = AdminInquiryController.class.getMethod(
                "answer", AuthPrincipal.class, long.class, com.ovengers.slotkey.inquiry.dto.request.InquiryAnswerRequest.class);

        MethodParameter getInquiryParam = createParameter(getInquiryMethod, 0);
        MethodParameter answerParam = createParameter(answerMethod, 1);

        assertThat(getInquiryParam.getParameterName()).isNull();
        assertThat(answerParam.getParameterName()).isNull();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("inquiryId", "7"));
        NativeWebRequest webRequest = new ServletWebRequest(request);
        ModelAndViewContainer mavContainer = new ModelAndViewContainer();

        Object getInquiryId = pathVariableResolver.resolveArgument(getInquiryParam, mavContainer, webRequest, binderFactory);
        Object answerId = pathVariableResolver.resolveArgument(answerParam, mavContainer, webRequest, binderFactory);

        assertThat(getInquiryId).isEqualTo(7L);
        assertThat(answerId).isEqualTo(7L);
    }
}
