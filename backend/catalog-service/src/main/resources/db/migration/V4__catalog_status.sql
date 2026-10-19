-- Estado de locais e atividades (docs/catalog-api.md §2.2–2.3): retirar em vez de apagar, para que as
-- experiências que os referenciam continuem válidas (docs/experiences-api.md §15).
-- Os dados existentes ficam ACTIVE; nada é apagado nem alterado.

ALTER TABLE place
	ADD COLUMN status varchar(10) NOT NULL DEFAULT 'ACTIVE',
	ADD CONSTRAINT place_status_values CHECK (status IN ('ACTIVE', 'RETIRED'));

ALTER TABLE activity_offering
	ADD COLUMN status varchar(10) NOT NULL DEFAULT 'ACTIVE',
	ADD CONSTRAINT activity_offering_status_values CHECK (status IN ('ACTIVE', 'RETIRED'));
