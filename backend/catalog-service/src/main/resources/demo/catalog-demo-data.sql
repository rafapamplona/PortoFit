-- Dados FICTÍCIOS de demonstração do PortoFit (perfil "demo").
-- Os locais não existem: nomes, moradas e contactos são inventados. As coordenadas ficam em zonas
-- do Porto apenas para a pesquisa por proximidade ser realista; não assinalam estabelecimentos reais.
-- Repetível: ids fixos + INSERT ... ON CONFLICT DO UPDATE (sem duplicados ao reiniciar).

INSERT INTO place (id, name, description, street, postal_code, parish, city, latitude, longitude, phone, website, is_demo) VALUES
	('3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e01', 'Clube de Combate Ribeira (fictício)',
	 'Local fictício criado para demonstração do PortoFit. Não corresponde a nenhum estabelecimento real.',
	 'Rua Exemplo, 10', '4050-001', 'Centro Histórico', 'Porto', 41.140600, -8.613100, NULL, NULL, true),
	('3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e02', 'Ginásio Exemplo Boavista (fictício)',
	 'Local fictício criado para demonstração do PortoFit. Não corresponde a nenhum estabelecimento real.',
	 'Avenida Exemplo, 200', '4100-001', 'Lordelo do Ouro e Massarelos', 'Porto', 41.157900, -8.629100, NULL, 'https://example.org/portofit-demo', true),
	('3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e03', 'Estúdio Exemplo Cedofeita (fictício)',
	 'Local fictício criado para demonstração do PortoFit. Não corresponde a nenhum estabelecimento real.',
	 'Rua Exemplo, 45', '4050-002', 'Cedofeita', 'Porto', 41.153500, -8.618900, NULL, NULL, true),
	('3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e04', 'Piscina Exemplo Campanhã (fictício)',
	 'Local fictício criado para demonstração do PortoFit. Não corresponde a nenhum estabelecimento real.',
	 'Rua Exemplo, 300', '4300-001', 'Campanhã', 'Porto', 41.150500, -8.585300, NULL, NULL, true),
	('3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e05', 'Centro Aquático Exemplo Foz (fictício)',
	 'Local fictício criado para demonstração do PortoFit. Não corresponde a nenhum estabelecimento real.',
	 'Avenida Exemplo, 1000', '4150-001', 'Aldoar, Foz do Douro e Nevogilde', 'Porto', 41.152000, -8.675000, NULL, NULL, true),
	('3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e06', 'Box Funcional Exemplo Bonfim (fictício)',
	 'Local fictício criado para demonstração do PortoFit. Não corresponde a nenhum estabelecimento real.',
	 'Rua Exemplo, 77', '4000-001', 'Bonfim', 'Porto', 41.147800, -8.598700, NULL, NULL, true),
	('3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e07', 'Parede de Escalada Exemplo Paranhos (fictício)',
	 'Local fictício criado para demonstração do PortoFit. Não corresponde a nenhum estabelecimento real.',
	 'Rua Exemplo, 120', '4200-001', 'Paranhos', 'Porto', 41.172800, -8.601500, NULL, NULL, true),
	('3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e08', 'Grupo de Corrida Exemplo Ramalde (fictício)',
	 'Local fictício criado para demonstração do PortoFit. Não corresponde a nenhum estabelecimento real.',
	 'Rua Exemplo, 5', '4100-002', 'Ramalde', 'Porto', 41.168000, -8.655000, NULL, NULL, true),
	('3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e09', 'Ginásio Exemplo Antas (fictício)',
	 'Local fictício criado para demonstração do PortoFit. Não corresponde a nenhum estabelecimento real.',
	 'Avenida Exemplo, 50', '4350-001', 'Campanhã', 'Porto', 41.161500, -8.583500, NULL, NULL, true),
	('3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e10', 'Academia Exemplo Aliados (fictício)',
	 'Local fictício criado para demonstração do PortoFit. Não corresponde a nenhum estabelecimento real.',
	 'Praça Exemplo, 1', '4000-002', 'Centro Histórico', 'Porto', 41.148600, -8.611000, NULL, NULL, true)
ON CONFLICT (id) DO UPDATE SET
	name = EXCLUDED.name, description = EXCLUDED.description, street = EXCLUDED.street,
	postal_code = EXCLUDED.postal_code, parish = EXCLUDED.parish, city = EXCLUDED.city,
	latitude = EXCLUDED.latitude, longitude = EXCLUDED.longitude, phone = EXCLUDED.phone,
	website = EXCLUDED.website, is_demo = EXCLUDED.is_demo, updated_at = now()
WHERE (place.name, place.description, place.street, place.postal_code, place.parish, place.city,
       place.latitude, place.longitude, place.phone, place.website, place.is_demo)
      IS DISTINCT FROM
      (EXCLUDED.name, EXCLUDED.description, EXCLUDED.street, EXCLUDED.postal_code, EXCLUDED.parish, EXCLUDED.city,
       EXCLUDED.latitude, EXCLUDED.longitude, EXCLUDED.phone, EXCLUDED.website, EXCLUDED.is_demo);

INSERT INTO activity_offering (id, place_id, sport_id, name, description, level, schedule_notes) VALUES
	('8a7b6c5d-4e3f-4a1b-9c8d-000000000101', '3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e01', 'boxe', 'Boxe iniciação',
	 'Técnica base, condição física e sparring leve.', 'BEGINNER', 'Seg. e qua., 19h–20h (informativo)'),
	('8a7b6c5d-4e3f-4a1b-9c8d-000000000102', '3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e01', 'kickboxing', 'Kickboxing aberto',
	 NULL, 'ALL_LEVELS', NULL),
	('8a7b6c5d-4e3f-4a1b-9c8d-000000000201', '3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e02', 'boxe', 'Boxe fitness',
	 'Treino cardiovascular com técnica de boxe, sem contacto.', 'ALL_LEVELS', NULL),
	('8a7b6c5d-4e3f-4a1b-9c8d-000000000202', '3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e02', 'ioga', 'Ioga ao fim do dia',
	 NULL, 'BEGINNER', 'Ter. e qui., 18h30 (informativo)'),
	('8a7b6c5d-4e3f-4a1b-9c8d-000000000301', '3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e03', 'ioga', 'Ioga vinyasa',
	 NULL, 'INTERMEDIATE', NULL),
	('8a7b6c5d-4e3f-4a1b-9c8d-000000000302', '3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e03', 'pilates', 'Pilates de solo',
	 NULL, 'ALL_LEVELS', NULL),
	('8a7b6c5d-4e3f-4a1b-9c8d-000000000401', '3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e04', 'natacao', 'Natação para adultos',
	 'Aprender a nadar ou recuperar a técnica.', 'BEGINNER', NULL),
	('8a7b6c5d-4e3f-4a1b-9c8d-000000000402', '3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e04', 'natacao', 'Natação aperfeiçoamento',
	 NULL, 'ADVANCED', NULL),
	('8a7b6c5d-4e3f-4a1b-9c8d-000000000501', '3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e05', 'natacao', 'Natação livre',
	 NULL, 'ALL_LEVELS', NULL),
	('8a7b6c5d-4e3f-4a1b-9c8d-000000000502', '3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e05', 'ioga', 'Ioga junto ao mar',
	 NULL, 'ALL_LEVELS', 'Sáb., 10h (informativo)'),
	('8a7b6c5d-4e3f-4a1b-9c8d-000000000601', '3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e06', 'crossfit', 'CrossFit',
	 NULL, 'ALL_LEVELS', NULL),
	('8a7b6c5d-4e3f-4a1b-9c8d-000000000602', '3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e06', 'boxe', 'Boxe funcional',
	 NULL, 'INTERMEDIATE', NULL),
	('8a7b6c5d-4e3f-4a1b-9c8d-000000000701', '3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e07', 'escalada', 'Escalada em bloco — iniciação',
	 NULL, 'BEGINNER', NULL),
	('8a7b6c5d-4e3f-4a1b-9c8d-000000000801', '3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e08', 'corrida', 'Corrida em grupo',
	 'Grupo informal com ritmos diferentes.', 'ALL_LEVELS', 'Sáb., 9h (informativo)'),
	('8a7b6c5d-4e3f-4a1b-9c8d-000000000901', '3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e09', 'boxe', 'Boxe competição',
	 NULL, 'ADVANCED', NULL),
	('8a7b6c5d-4e3f-4a1b-9c8d-000000000902', '3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e09', 'pilates', 'Pilates para iniciantes',
	 NULL, 'BEGINNER', NULL),
	('8a7b6c5d-4e3f-4a1b-9c8d-000000000903', '3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e09', 'crossfit', 'CrossFit iniciação',
	 NULL, 'BEGINNER', NULL),
	('8a7b6c5d-4e3f-4a1b-9c8d-000000001001', '3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e10', 'kickboxing', 'Kickboxing técnico',
	 NULL, 'INTERMEDIATE', NULL),
	('8a7b6c5d-4e3f-4a1b-9c8d-000000001002', '3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e10', 'ioga', 'Ioga matinal',
	 NULL, 'ALL_LEVELS', NULL)
ON CONFLICT (id) DO UPDATE SET
	place_id = EXCLUDED.place_id, sport_id = EXCLUDED.sport_id, name = EXCLUDED.name,
	description = EXCLUDED.description, level = EXCLUDED.level, schedule_notes = EXCLUDED.schedule_notes,
	updated_at = now()
WHERE (activity_offering.place_id, activity_offering.sport_id, activity_offering.name,
       activity_offering.description, activity_offering.level, activity_offering.schedule_notes)
      IS DISTINCT FROM
      (EXCLUDED.place_id, EXCLUDED.sport_id, EXCLUDED.name,
       EXCLUDED.description, EXCLUDED.level, EXCLUDED.schedule_notes);
