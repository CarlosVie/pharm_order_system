CREATE TABLE IF NOT EXISTS stock (
    medication VARCHAR(80) PRIMARY KEY,
    category   VARCHAR(60) NOT NULL,
    quantity   INT NOT NULL CHECK (quantity >= 0)
);

CREATE TABLE IF NOT EXISTS orders (
    seq            BIGINT AUTO_INCREMENT PRIMARY KEY,
    id             VARCHAR(36)  NOT NULL UNIQUE,
    patient_name   VARCHAR(80)  NOT NULL,
    status         VARCHAR(30)  NOT NULL,
    stock_reserved BOOLEAN      NOT NULL DEFAULT FALSE,
    message        VARCHAR(300),
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_orders_status ON orders (status, seq);

CREATE TABLE IF NOT EXISTS order_items (
    order_seq  BIGINT      NOT NULL,
    medication VARCHAR(80) NOT NULL,
    quantity   INT         NOT NULL,
    FOREIGN KEY (order_seq) REFERENCES orders (seq)
);
