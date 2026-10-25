CREATE TABLE pdf_page_cache (
  id BIGINT NOT NULL AUTO_INCREMENT,
  material_id BIGINT NOT NULL,
  material_version INT NOT NULL,
  page_no INT NOT NULL,
  total_pages INT NOT NULL,
  extraction_status VARCHAR(16) NOT NULL,
  page_text LONGTEXT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_pdf_page_version (material_id, material_version, page_no),
  CONSTRAINT fk_pdf_page_material FOREIGN KEY (material_id) REFERENCES material(id),
  CONSTRAINT ck_pdf_page_status CHECK (extraction_status IN ('TEXT', 'NEEDS_OCR'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
