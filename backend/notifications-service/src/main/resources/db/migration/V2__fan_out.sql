-- Segunda parte (docs/notifications-api.md §8): resolução por páginas dos destinatários de EXPERIENCE_PUBLISHED.
-- Um trabalho por evento, gravado na transação da deduplicação; o cursor só avança com as notificações da página.

CREATE TABLE fan_out_job (
	event_id        uuid        PRIMARY KEY,
	actor_id        uuid        NOT NULL,
	-- Cópia de apresentação (como em notification).
	actor_username  varchar(30) NOT NULL,
	experience_id   uuid        NOT NULL,
	-- followedBefore da consulta dos seguidores (§8.1).
	occurred_at     timestamptz NOT NULL,
	status          varchar(10) NOT NULL DEFAULT 'PENDING',
	-- Cursor opaco do users-service da próxima página; null = primeira página.
	cursor          varchar(200),
	recipients      integer     NOT NULL DEFAULT 0,
	attempts        integer     NOT NULL DEFAULT 0,
	next_attempt_at timestamptz NOT NULL DEFAULT now(),
	-- Só um código (ex.: HTTP_503, AUTHOR_NOT_FOUND); nunca o corpo de uma resposta.
	last_error      varchar(64),
	created_at      timestamptz NOT NULL DEFAULT now(),
	updated_at      timestamptz NOT NULL DEFAULT now(),
	CONSTRAINT fan_out_job_status_valid CHECK (status IN ('PENDING', 'DONE', 'TRUNCATED', 'FAILED'))
);

CREATE INDEX fan_out_job_due_idx ON fan_out_job (next_attempt_at, created_at) WHERE status = 'PENDING';
CREATE INDEX fan_out_job_updated_idx ON fan_out_job (updated_at) WHERE status <> 'PENDING';

-- Seguir de novo o mesmo par dentro da janela não gera outra notificação (§6.3).
CREATE INDEX notification_follow_idx ON notification (recipient_id, actor_id, occurred_at) WHERE type = 'USER_FOLLOWED';
