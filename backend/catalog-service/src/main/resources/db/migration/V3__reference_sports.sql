-- Modalidades: dados de referência (categorias reais, não fictícias), necessários em qualquer ambiente.
-- Os ids são slugs estáveis: também são usados como interesses no users-service.
INSERT INTO sport (id, name, description) VALUES
	('boxe',       'Boxe',       'Desporto de combate com os punhos.'),
	('corrida',    'Corrida',    'Corrida ao ar livre ou em pista, individual ou em grupo.'),
	('crossfit',   'CrossFit',   'Treino funcional de alta intensidade.'),
	('escalada',   'Escalada',   'Escalada em parede artificial ou em bloco.'),
	('ioga',       'Ioga',       'Prática de posturas, respiração e relaxamento.'),
	('kickboxing', 'Kickboxing', 'Desporto de combate com punhos e pernas.'),
	('natacao',    'Natação',    'Natação em piscina, para vários níveis.'),
	('pilates',    'Pilates',    'Exercícios de força, controlo e flexibilidade.');
