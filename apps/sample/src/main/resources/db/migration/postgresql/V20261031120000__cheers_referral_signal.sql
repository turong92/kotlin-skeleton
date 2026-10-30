-- cheers_referral_signal (postgresql). 이 응원 결제가 친구초대의 초대자 보상 자격을 만든(첫 결제 신호가 관계를 움직인) 주문인지 - 그 주문이 전액 환불될 때만 보류 중인 보상을 거둔다.
alter table cheers add column opened_referral boolean not null default false;
