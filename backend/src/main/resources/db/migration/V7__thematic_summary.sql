ALTER TABLE material_summary_versions ADD COLUMN workflow VARCHAR(24) NOT NULL DEFAULT 'LEGACY';
ALTER TABLE material_summary_parts ADD COLUMN phase VARCHAR(24) NULL;
