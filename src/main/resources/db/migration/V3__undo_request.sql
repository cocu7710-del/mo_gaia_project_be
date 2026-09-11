-- 언두 동의(CONSENT) 플로우 — 상대가 행동한 뒤의 언두는 영향받은 플레이어 전원 승인 필요.
-- { requestedBy, targetSeq, needConsent: [enginePlayerId], approved: [enginePlayerId] }
ALTER TABLE game ADD COLUMN undo_request JSONB;
