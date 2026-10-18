-- Esquema do users-service: contas, interesses e sessões (refresh tokens).
-- As relações de seguir entram numa migração própria, na etapa de "seguir".

CREATE TABLE app_user (
	id            uuid          PRIMARY KEY,
	username      varchar(30)   NOT NULL,
	email         varchar(254)  NOT NULL,
	password_hash varchar(100)  NOT NULL,
	description   varchar(300),
	created_at    timestamptz   NOT NULL DEFAULT now(),
	updated_at    timestamptz   NOT NULL DEFAULT now(),
	-- 3 a 30 caracteres a-z, 0-9, '.', '_'; começa e acaba em letra ou número.
	CONSTRAINT app_user_username_format CHECK (username ~ '^[a-z0-9][a-z0-9._]{1,28}[a-z0-9]$'),
	-- Guardado já normalizado (minúsculas), para a unicidade não depender de maiúsculas.
	CONSTRAINT app_user_email_lowercase CHECK (email = lower(email)),
	CONSTRAINT app_user_username_uq UNIQUE (username),
	CONSTRAINT app_user_email_uq UNIQUE (email)
);

-- Interesses: ids de modalidade do catálogo (sem chave estrangeira: pertencem a outro serviço).
CREATE TABLE user_interest (
	user_id  uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
	sport_id varchar(40) NOT NULL,
	position smallint    NOT NULL,
	CONSTRAINT user_interest_pk PRIMARY KEY (user_id, sport_id),
	CONSTRAINT user_interest_position_uq UNIQUE (user_id, position),
	CONSTRAINT user_interest_sport_format CHECK (sport_id ~ '^[a-z0-9]+(-[a-z0-9]+)*$')
);

-- Sessões: só o hash SHA-256 do refresh token é guardado.
-- Todos os tokens de uma família (um login) partilham o mesmo expires_at: a rotação não o prolonga.
CREATE TABLE refresh_token (
	id         uuid         PRIMARY KEY,
	user_id    uuid         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
	family_id  uuid         NOT NULL,
	token_hash char(64)     NOT NULL,
	created_at timestamptz  NOT NULL DEFAULT now(),
	expires_at timestamptz  NOT NULL,
	used_at    timestamptz,
	revoked_at timestamptz,
	CONSTRAINT refresh_token_hash_uq UNIQUE (token_hash)
);

CREATE INDEX refresh_token_family_idx ON refresh_token (family_id);
CREATE INDEX refresh_token_user_idx ON refresh_token (user_id);
