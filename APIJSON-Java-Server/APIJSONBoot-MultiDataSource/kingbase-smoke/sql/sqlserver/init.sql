DROP TABLE IF EXISTS "apijson_kb_smoke";
CREATE TABLE "apijson_kb_smoke" (
  "id" BIGINT IDENTITY(1,1) PRIMARY KEY,
  "name" VARCHAR(128) NOT NULL,
  "quantity" INTEGER NOT NULL
);
