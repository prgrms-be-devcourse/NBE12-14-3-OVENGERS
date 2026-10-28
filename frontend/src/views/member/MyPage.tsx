'use client';

import { useState } from 'react';
import type { FormEvent } from 'react';
import Link from 'next/link';
import { getMe, updateNickname } from '@/api/memberApi';
import { useAction, useAsync } from '@/hooks/useApi';
import { useAuth } from '@/hooks/useAuth';
import Button from '@/components/common/Button';
import { formatCredit } from '@/utils/price';
import LoadingSpinner from '@/components/common/LoadingSpinner';
import ErrorMessage from '@/components/common/ErrorMessage';
import WithdrawalDialog from '@/components/member/WithdrawalDialog';
import { ROUTES } from '@/constants/routePaths';

export default function MyPage() {
  
    const { data: member, loading, error, run, setData } = useAsync(getMe);
    const { refreshMember } = useAuth();
    
    const [editing, setEditing] = useState(false);
    const [nickname, setNickname] = useState('');
    const [validationError, setValidationError] = useState<string | null>(null);
    const [notice, setNotice] = useState<string | null>(null);
    const [withdrawalOpen, setWithdrawalOpen] = useState(false);
    
    const {
      execute,
      loading: saving,
      error: saveError,
      setError: setSaveError,
    } = useAction(updateNickname);
    
    function startEditing() {
      if (!member) return;
    
      setNickname(member.nickname);
      setValidationError(null);
      setSaveError(null);
      setNotice(null);
      setEditing(true);
    }
    
    async function handleSave(event: FormEvent<HTMLFormElement>) {
      event.preventDefault();
      if (saving) return;
    
      const value = nickname.trim();
      setValidationError(null);
      setSaveError(null);
      setNotice(null);
    
      if (value.length < 2 || value.length > 50) {
        setValidationError('닉네임은 2자 이상 50자 이하여야 합니다.');
        return;
      }
    
      try {
        const updatedMember = await execute(value);
        if (!updatedMember) return;
    
        // 수정 응답으로 마이페이지를 즉시 갱신합니다.
        setData(updatedMember);
        setEditing(false);
        setNotice('닉네임이 변경되었습니다.');
      } catch {
        // useAction이 저장한 오류를 화면에서 표시합니다.
        return;
      }
    
      try {
        // 공통 회원 정보를 갱신하여 헤더에도 반영합니다.
        await refreshMember();
      } catch {
        setNotice(
          '닉네임은 변경되었지만 상단 메뉴 갱신에 실패했습니다. 페이지를 새로고침해주세요.',
        );
      }
    }

  return (
    <div className="mypage">
      <div className="pagehead">
        <div>
          <p className="page-kicker">MY PAGE</p>
          <h1>마이페이지</h1>
          <p>내 계정 정보와 보유 크레딧을 확인하세요.</p>
        </div>
      </div>

      {loading && (
        <LoadingSpinner label="내 정보를 불러오고 있습니다…" />
      )}

      <ErrorMessage
        error={error}
        onRetry={() => {
          void run().catch(() => {});
        }}
      />

      {!loading && !error && member && (
        <>
          <section className="mypage-hero" aria-label="프로필 요약">
            <div className="mypage-avatar" aria-hidden="true">
              {member.nickname.slice(0, 1)}
            </div>
            <div className="mypage-hero-text">
              <strong>{member.nickname}</strong>
              <span>{member.email}</span>
            </div>
            <span className="badge cyan">
              {member.role === 'ADMIN' ? '관리자' : '일반 회원'}
            </span>
          </section>

          {member.role === 'USER' && (
            <section className="mypage-credit" aria-label="보유 크레딧">
              <div>
                <span className="mypage-credit-label">보유 크레딧</span>
                <strong>{formatCredit(member.balance)}</strong>
              </div>
              <div className="mypage-credit-actions">
                <Link className="btn small primary" href={ROUTES.creditCharge}>
                  충전하기
                </Link>
                <Link className="btn small" href={ROUTES.creditHistory}>
                  충전 내역
                </Link>
              </div>
            </section>
          )}

          {member.role === 'USER' && (
            <nav className="mypage-shortcuts" aria-label="바로가기">
              <Link href={ROUTES.reservations}><b>예약 내역</b><span>다가오는 일정과 지난 이용</span></Link>
              <Link href={ROUTES.waitlist}><b>빈자리 알림</b><span>자리를 기다리는 시간</span></Link>
              <Link href={ROUTES.creditHistory}><b>충전 내역</b><span>PG 결제·관리자 지급 기록</span></Link>
              <Link href={ROUTES.inquiries}><b>내 문의</b><span>문의 내역과 답변</span></Link>
            </nav>
          )}

          <section className="panel" aria-labelledby="member-info-title">
            <h2 id="member-info-title">계정 정보</h2>

            <dl className="mypage-info">
              <dt>닉네임</dt>
              <dd>
                {editing ? (
                  <form onSubmit={handleSave}>
                    <label htmlFor="mypage-nickname" className="muted">
                      새 닉네임
                    </label>

                    <input
                      id="mypage-nickname"
                      name="nickname"
                      autoComplete="nickname"
                      value={nickname}
                      onChange={(event) => setNickname(event.target.value)}
                      minLength={2}
                      maxLength={50}
                      required
                      disabled={saving}
                      aria-invalid={Boolean(validationError)}
                      aria-describedby={
                        validationError ? 'nickname-validation-error' : undefined
                      }
                    />

                    {validationError && (
                      <p
                        id="nickname-validation-error"
                        className="form-error"
                        role="alert"
                      >
                        {validationError}
                      </p>
                    )}

                    <ErrorMessage error={saveError} />

                    <div className="actions wrap" style={{ marginTop: 12 }}>
                      <Button type="submit" variant="primary" size="small" loading={saving}>
                        저장
                      </Button>

                      <Button
                        size="small"
                        disabled={saving}
                        onClick={() => setEditing(false)}
                      >
                        취소
                      </Button>
                    </div>
                  </form>
                ) : (
                  <div className="row wrap">
                    <span>{member.nickname}</span>
                    <Button size="small" onClick={startEditing}>
                      수정
                    </Button>
                  </div>
                )}
              </dd>

              <dt>이메일</dt>
              <dd>{member.email}</dd>

              <dt>가입일</dt>
              <dd>{member.createdAt.slice(0, 10)}</dd>
            </dl>

            {notice && (
              <p className="note" role="status">
                {notice}
              </p>
            )}
          </section>

          {member.role === 'USER' && (
            <section className="panel mypage-danger" aria-labelledby="withdraw-title">
              <h3 id="withdraw-title">회원탈퇴</h3>
              <p>탈퇴 시 계정을 복구할 수 없고 잔여 크레딧이 소멸합니다. 정지 회원은 운영자에게 문의해주세요.</p>
              <Button variant="danger" size="small" disabled={saving || editing}
                onClick={() => setWithdrawalOpen(true)}>회원탈퇴</Button>
            </section>
          )}
          {withdrawalOpen && member.role === 'USER' && (
            <WithdrawalDialog balance={member.balance} onClose={() => setWithdrawalOpen(false)} />
          )}
        </>
      )}
    </div>
  );
}
