-- Mais modalidades de referência (docs/locais-reais.md §12). Os ids são slugs estáveis, também usados como interesses
-- no users-service e nas publicações; os existentes não mudam. Futebol e futsal, ténis e padel são categorias distintas.
INSERT INTO sport (id, name, description) VALUES
	('basquetebol', 'Basquetebol', 'Desporto coletivo de cesto, em pavilhão ou ao ar livre.'),
	('futebol',     'Futebol',     'Futebol de 5, 7 ou 11, em campo relvado ou sintético.'),
	('futsal',      'Futsal',      'Futebol de salão, em pavilhão.'),
	('musculacao',  'Musculação',  'Treino de força com pesos e máquinas.'),
	('padel',       'Padel',       'Desporto de raquete em campo fechado, a pares.'),
	('tenis',       'Ténis',       'Desporto de raquete em court, individual ou a pares.'),
	('voleibol',    'Voleibol',    'Desporto coletivo de rede, em pavilhão ou na praia.')
ON CONFLICT (id) DO NOTHING;
