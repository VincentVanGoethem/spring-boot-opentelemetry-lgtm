DROP TABLE IF EXISTS order_item_contents;
DROP TABLE IF EXISTS order_items;
DROP TABLE IF EXISTS orders;

CREATE TABLE orders (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE order_items (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id  BIGINT NOT NULL REFERENCES orders(id),
    order_key INT NOT NULL DEFAULT 0,
    sku       VARCHAR(255) NOT NULL,
    quantity  INT NOT NULL DEFAULT 1
);

CREATE TABLE order_item_contents (
    order_item_id  BIGINT NOT NULL REFERENCES order_items(id),
    order_item_key INT NOT NULL DEFAULT 0,
    name           VARCHAR(255),
    description    TEXT
);
