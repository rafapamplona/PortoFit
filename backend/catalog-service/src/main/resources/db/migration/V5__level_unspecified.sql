-- Nível não indicado (docs/catalog-api.md §2.3; docs/locais-reais.md §4): os locais reais não publicam níveis, e
-- escolher um seria inventar. UNSPECIFIED junta-se aos valores existentes; nada mais muda.
ALTER TABLE activity_offering DROP CONSTRAINT activity_offering_level_values;
ALTER TABLE activity_offering ADD CONSTRAINT activity_offering_level_values
	CHECK (level IN ('BEGINNER', 'INTERMEDIATE', 'ADVANCED', 'ALL_LEVELS', 'UNSPECIFIED'));
