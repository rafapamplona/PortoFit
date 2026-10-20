-- Esquema do messaging-service (docs/messaging-api.md §2 e §14).
-- Sem chaves estrangeiras para outros serviços: os ids de pessoas e de experiências são referências por id.
-- Sem eliminação em cascata nem dados de demonstração (docs/messaging-api.md §13).

-- Uma conversa por par: o par é guardado ordenado (user_low < user_high, pela ordem do tipo uuid do PostgreSQL),
-- por isso A→B e B→A são o mesmo par, também com pedidos simultâneos.
CREATE TABLE conversation (
	id                 uuid        PRIMARY KEY DEFAULT uuidv7(),
	user_low           uuid        NOT NULL,
	user_high          uuid        NOT NULL,
	-- Cópias de apresentação (docs/messaging-api.md §8.2); a participação usa sempre os ids.
	user_low_username  varchar(30) NOT NULL,
	user_high_username varchar(30) NOT NULL,
	-- seq da última mensagem e número de mensagens; só mudam com a linha bloqueada (FOR UPDATE).
	last_seq           bigint      NOT NULL DEFAULT 0,
	message_count      integer     NOT NULL DEFAULT 0,
	last_message_at    timestamptz,
	created_at         timestamptz NOT NULL DEFAULT now(),
	CONSTRAINT conversation_pair_order CHECK (user_low < user_high),
	CONSTRAINT conversation_pair_uq UNIQUE (user_low, user_high)
);

-- Uma linha por participante: caixa de conversas e cursor de leitura (docs/messaging-api.md §5.4).
CREATE TABLE conversation_member (
	user_id         uuid        NOT NULL,
	conversation_id uuid        NOT NULL REFERENCES conversation (id),
	last_read_seq   bigint      NOT NULL DEFAULT 0,
	-- Cópia de conversation.last_message_at, para ordenar a caixa só com este índice.
	last_message_at timestamptz,
	CONSTRAINT conversation_member_pk PRIMARY KEY (user_id, conversation_id),
	CONSTRAINT conversation_member_read_nonneg CHECK (last_read_seq >= 0)
);

-- A caixa: só conversas com mensagens, a mais recente primeiro, desempate pelo id.
CREATE INDEX conversation_member_inbox_idx
	ON conversation_member (user_id, last_message_at DESC, conversation_id DESC)
	WHERE last_message_at IS NOT NULL;

-- Mensagens: o cursor é o seq por conversa (chave primária). A chave de idempotência fica na própria linha.
CREATE TABLE message (
	conversation_id uuid        NOT NULL REFERENCES conversation (id),
	seq             bigint      NOT NULL,
	id              uuid        NOT NULL DEFAULT uuidv7(),
	sender_id       uuid        NOT NULL,
	text            text,
	experience_id   uuid,
	created_at      timestamptz NOT NULL,
	idempotency_key uuid        NOT NULL,
	request_hash    char(64)    NOT NULL,
	CONSTRAINT message_pk PRIMARY KEY (conversation_id, seq),
	CONSTRAINT message_id_uq UNIQUE (id),
	CONSTRAINT message_sender_key_uq UNIQUE (sender_id, idempotency_key),
	CONSTRAINT message_seq_positive CHECK (seq > 0),
	CONSTRAINT message_content CHECK (text IS NOT NULL OR experience_id IS NOT NULL),
	-- Teto do contrato; o limite efetivo (por omissão 1000) é configurável no serviço.
	CONSTRAINT message_text_length CHECK (text IS NULL OR char_length(text) BETWEEN 1 AND 2000)
);
