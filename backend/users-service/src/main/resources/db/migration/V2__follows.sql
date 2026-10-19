-- Relações de seguir (docs/users-api.md §2.2, §3.9–3.10). V1 mantém-se inalterada.

CREATE TABLE follow (
	follower_id uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
	followed_id uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
	created_at  timestamptz NOT NULL DEFAULT now(),
	-- Uma relação por par: a chave primária garante a unicidade, também com pedidos simultâneos.
	CONSTRAINT follow_pk PRIMARY KEY (follower_id, followed_id),
	CONSTRAINT follow_not_self CHECK (follower_id <> followed_id)
);

-- "Seguidores de X": WHERE followed_id = X ORDER BY created_at, follower_id.
CREATE INDEX follow_followed_idx ON follow (followed_id, created_at, follower_id);

-- "Quem X segue": WHERE follower_id = X ORDER BY created_at, followed_id.
CREATE INDEX follow_follower_idx ON follow (follower_id, created_at, followed_id);
