-- 대기 방 준비(Ready) 상태 — 방장 외 전원 준비 시에만 시작 가능
ALTER TABLE game_player ADD COLUMN ready BOOLEAN NOT NULL DEFAULT FALSE;
