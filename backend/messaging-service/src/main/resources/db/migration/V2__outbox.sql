-- Outbox de eventos para o notifications-service (docs/notifications-api.md §7; arquitetura.md §3.11).
-- O evento é gravado na mesma transação da mensagem. O payload nunca tem o texto da mensagem.

CREATE TABLE outbox (
	event_id        uuid        PRIMARY KEY,
	type            varchar(32) NOT NULL,
	payload         text        NOT NULL,
	created_at      timestamptz NOT NULL DEFAULT now(),
	status          varchar(8)  NOT NULL DEFAULT 'PENDING',
	attempts        integer     NOT NULL DEFAULT 0,
	next_attempt_at timestamptz NOT NULL DEFAULT now(),
	-- Só um código (ex.: HTTP_503, REJECTED/INVALID_EVENT); nunca o corpo de uma resposta.
	last_error      varchar(64),
	sent_at         timestamptz,
	CONSTRAINT outbox_status_valid CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
);

-- Eventos por enviar, pela ordem de criação.
CREATE INDEX outbox_due_idx ON outbox (next_attempt_at, created_at) WHERE status = 'PENDING';
-- Limpeza e resumo por estado.
CREATE INDEX outbox_status_idx ON outbox (status, created_at);
