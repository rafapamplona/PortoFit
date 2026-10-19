-- Comentários, gostos e visualizações, etapa E3 (docs/experiences-api.md §13).
-- As contagens são calculadas a partir destas tabelas em cada pedido (sem contadores duplicados).

CREATE TABLE comment (
	-- UUID versão 7: a ordem dos ids é a de criação (desempate da lista).
	id              uuid        PRIMARY KEY DEFAULT uuidv7(),
	experience_id   uuid        NOT NULL REFERENCES experience (id),
	author_id       uuid        NOT NULL,
	-- Cópia do token no momento do comentário, como nas experiências.
	author_username varchar(30) NOT NULL,
	-- Nulo só depois de eliminado (fica a marca, nunca exposta).
	text            text,
	created_at      timestamptz NOT NULL DEFAULT now(),
	edited_at       timestamptz,
	deleted_at      timestamptz,
	CONSTRAINT comment_content CHECK (
		(deleted_at IS NULL AND text IS NOT NULL AND char_length(text) BETWEEN 1 AND 1000)
		OR (deleted_at IS NOT NULL AND text IS NULL)
	)
);

-- Lista por experiência (mais antigos primeiro) e contagem dos ativos.
CREATE INDEX comment_experience_idx ON comment (experience_id, created_at, id) WHERE deleted_at IS NULL;

-- Tentativas de comentário (idempotência, como publish_request). O comentário é inserido depois do registo,
-- na mesma transação, por isso a verificação da referência é adiada para o fim.
CREATE TABLE comment_request (
	author_id       uuid        NOT NULL,
	idempotency_key uuid        NOT NULL,
	request_hash    char(64)    NOT NULL,
	comment_id      uuid        NOT NULL REFERENCES comment (id) DEFERRABLE INITIALLY DEFERRED,
	created_at      timestamptz NOT NULL DEFAULT now(),
	CONSTRAINT comment_request_pk PRIMARY KEY (author_id, idempotency_key)
);

CREATE INDEX comment_request_created_idx ON comment_request (created_at);

-- Um gosto por (experiência, pessoa): a chave primária garante-o também com pedidos simultâneos.
CREATE TABLE experience_like (
	experience_id uuid        NOT NULL REFERENCES experience (id),
	user_id       uuid        NOT NULL,
	created_at    timestamptz NOT NULL DEFAULT now(),
	CONSTRAINT experience_like_pk PRIMARY KEY (experience_id, user_id)
);

-- likedByMe numa página de experiências.
CREATE INDEX experience_like_user_idx ON experience_like (user_id, experience_id);

-- Visualizações contadas: no máximo uma por (experiência, quem vê, período). Quem vê é o hash SHA-256 de
-- "user:<sub>" ou "key:<chave de instalação>"; nunca o IP nem a chave original (docs/experiences-api.md §13.5).
CREATE TABLE experience_view (
	experience_id uuid        NOT NULL REFERENCES experience (id),
	viewer_hash   char(64)    NOT NULL,
	period_start  timestamptz NOT NULL,
	created_at    timestamptz NOT NULL DEFAULT now(),
	CONSTRAINT experience_view_pk PRIMARY KEY (experience_id, viewer_hash, period_start)
);
