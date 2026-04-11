DROP TABLE IF EXISTS mystery_box_items;
DROP TABLE IF EXISTS mystery_boxes;

CREATE TABLE mystery_boxes (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY
);

CREATE TABLE mystery_box_items (
     mystery_box_id  BIGINT NOT NULL REFERENCES mystery_boxes(id),
     mystery_box_key INT NOT NULL DEFAULT 0,
     name           VARCHAR(255),
     description    TEXT
);