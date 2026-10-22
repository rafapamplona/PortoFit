-- Esquema do notifications-service, primeira parte (docs/notifications-api.md §2 e §14).
-- Sem chaves estrangeiras para outros serviços: pessoas, conversas, comentários e experiências são referências por id.
-- Nenhum conteúdo privado: só tipo, ator (id e cópia do username) e referências.

-- Uma linha por pessoa: bloqueada ao criar notificações, para atribuir o seq (único e crescente; pode ter lacunas).
CREATE TABLE inbox (
	recipient_id uuid   PRIMARY KEY,
	last_seq     bigint NOT NULL DEFAULT 0
);

CREATE TABLE notification (
	id              uuid        PRIMARY KEY DEFAULT uuidv7(),
	recipient_id    uuid        NOT NULL,
	seq             bigint      NOT NULL,
	type            varchar(32) NOT NULL,
	actor_id        uuid        NOT NULL,
	-- Cópia de apresentação (docs/notifications-api.md §2).
	actor_username  varchar(30) NOT NULL,
	experience_id   uuid,
	comment_id      uuid,
	conversation_id uuid,
	message_seq     bigint,
	message_kind    varchar(8),
	event_id        uuid        NOT NULL,
	occurred_at     timestamptz NOT NULL,
	created_at      timestamptz NOT NULL DEFAULT now(),
	read_at         timestamptz,
	CONSTRAINT notification_recipient_seq_uq UNIQUE (recipient_id, seq),
	CONSTRAINT notification_event_recipient_uq UNIQUE (event_id, recipient_id),
	CONSTRAINT notification_seq_positive CHECK (seq > 0),
	CONSTRAINT notification_not_self CHECK (actor_id <> recipient_id)
);

-- Contagem por ler e "marcar todas".
CREATE INDEX notification_unread_idx ON notification (recipient_id, seq) WHERE read_at IS NULL;
-- Retenção por idade.
CREATE INDEX notification_created_idx ON notification (created_at);

-- Deduplicação por eventId (docs/notifications-api.md §6.3): dura mais do que a idade máxima de um evento aceite e
-- não é apagada com as notificações.
CREATE TABLE received_event (
	event_id     uuid        PRIMARY KEY,
	producer     varchar(16) NOT NULL,
	type         varchar(32) NOT NULL,
	payload_hash char(64)    NOT NULL,
	received_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX received_event_received_idx ON received_event (received_at);
