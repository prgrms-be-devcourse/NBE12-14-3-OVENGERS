package com.ovengers.slotkey.auth.service;

import com.ovengers.slotkey.auth.entity.VerificationPurpose;
import com.ovengers.slotkey.auth.mail.VerificationMailSender;
import com.ovengers.slotkey.auth.repository.VerificationGrantRepository;
import com.ovengers.slotkey.global.error.BusinessException;
import com.ovengers.slotkey.member.repository.MemberRepository;
import com.ovengers.slotkey.member.service.MemberService;
import com.ovengers.slotkey.support.MySqlTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainerConfig.class)
class EmailSignupIntegrationTest {

    @Autowired
    private MemberService memberService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private VerificationChallengeService challengeService;

    @Autowired
    private VerificationCheckService checkService;

    @Autowired
    private VerificationGrantRepository grantRepository;

    @Autowired
    private VerificationSecretHasher secretHasher;

    @MockBean
    private VerificationMailSender mailSender;

    @Test
    void 비밀번호_확인에_실패해도_같은_증표로_다시_가입할_수_있다() {
        String email = newEmail();
        String grant = issueSignupGrant(email);

        assertThatThrownBy(() -> memberService.signup(
                email, "Test1234!", "Different1234!", "테스트회원", grant
        )).isInstanceOf(BusinessException.class);

        assertThat(memberRepository.existsByEmail(email)).isFalse();
        assertNotConsumed(grant);

        var member = memberService.signup(
                email, "Test1234!", "Test1234!", "테스트회원", grant
        );

        assertThat(member.getEmailVerifiedAt()).isNotNull();

        var storedGrant = grantRepository
                .findByGrantHash(secretHasher.hashGrant(grant))
                .orElseThrow();

        assertThat(storedGrant.getConsumedAt()).isNotNull();
    }

    @Test
    void 다른_이메일로_가입할_수_없고_원래_이메일의_증표는_유지된다() {
        String email = newEmail();
        String otherEmail = newEmail();
        String grant = issueSignupGrant(email);

        assertThatThrownBy(() -> memberService.signup(
                otherEmail, "Test1234!", "Test1234!", "테스트회원", grant
        )).isInstanceOf(BusinessException.class);

        assertThat(memberRepository.existsByEmail(otherEmail)).isFalse();
        assertNotConsumed(grant);

        memberService.signup(
                email, "Test1234!", "Test1234!", "테스트회원", grant
        );

        assertThat(memberRepository.existsByEmail(email)).isTrue();
    }

    private void assertNotConsumed(String rawGrant) {
        var storedGrant = grantRepository
                .findByGrantHash(secretHasher.hashGrant(rawGrant))
                .orElseThrow();

        assertThat(storedGrant.getConsumedAt()).isNull();
        assertThat(storedGrant.getInvalidatedAt()).isNull();
    }

    private String issueSignupGrant(String email) {
        AtomicReference<String> deliveredCode = new AtomicReference<>();

        doAnswer(invocation -> {
            deliveredCode.set(invocation.getArgument(2, String.class));
            return null;
        }).when(mailSender).sendCode(
                eq(email),
                eq(VerificationPurpose.SIGNUP),
                anyString()
        );

        var issued = challengeService.issue(
                email, VerificationPurpose.SIGNUP
        );

        var checked = checkService.verify(
                email,
                VerificationPurpose.SIGNUP,
                issued.challengeId(),
                deliveredCode.get()
        );

        assertThat(checked.grant()).isNotBlank();
        return checked.grant();
    }

    private String newEmail() {
        return "signup-" + UUID.randomUUID() + "@example.com";
    }
}
