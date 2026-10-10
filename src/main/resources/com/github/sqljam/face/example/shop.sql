-- Example database of SqlJam: a small online shop with categories, customers, products, orders and order items.
-- It is created at the first start, so that tables can be browsed, queried, exported and imported right away.

CREATE TABLE categories (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(50)  NOT NULL,
    description VARCHAR(200),
    CONSTRAINT uk_categories_name UNIQUE (name)
);
COMMENT ON TABLE categories IS 'Product categories';

CREATE TABLE customers (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    email      VARCHAR(200) NOT NULL,
    phone      VARCHAR(30),
    city       VARCHAR(50),
    vip        BOOLEAN   DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_customers_email UNIQUE (email)
);
COMMENT ON TABLE customers IS 'Registered customers';
COMMENT ON COLUMN customers.vip IS 'Customers with more than 5 orders';

CREATE TABLE products (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    category_id INT           NOT NULL,
    sku         VARCHAR(20)   NOT NULL,
    name        VARCHAR(100)  NOT NULL,
    price       DECIMAL(10,2) NOT NULL,
    stock       INT           DEFAULT 0,
    description CLOB,
    created_at  TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_products_sku UNIQUE (sku),
    CONSTRAINT fk_products_category FOREIGN KEY (category_id) REFERENCES categories (id)
);
COMMENT ON TABLE products IS 'Products for sale';
CREATE INDEX idx_products_category ON products (category_id);

CREATE TABLE orders (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no    VARCHAR(20)   NOT NULL,
    customer_id BIGINT        NOT NULL,
    status      VARCHAR(20)   NOT NULL,
    total       DECIMAL(12,2) DEFAULT 0 NOT NULL,
    paid_at     TIMESTAMP,
    created_at  TIMESTAMP     NOT NULL,
    CONSTRAINT uk_orders_order_no UNIQUE (order_no),
    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customers (id)
);
COMMENT ON TABLE orders IS 'Orders of customers';
COMMENT ON COLUMN orders.status IS 'NEW, PAID, SHIPPED, DELIVERED or CANCELLED';
CREATE INDEX idx_orders_customer ON orders (customer_id);
CREATE INDEX idx_orders_status ON orders (status, created_at);

CREATE TABLE order_items (
    order_id   BIGINT        NOT NULL,
    product_id BIGINT        NOT NULL,
    quantity   INT           NOT NULL,
    unit_price DECIMAL(10,2) NOT NULL,
    PRIMARY KEY (order_id, product_id),
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_order_items_product FOREIGN KEY (product_id) REFERENCES products (id)
);
COMMENT ON TABLE order_items IS 'Products of an order';

INSERT INTO categories (name, description) VALUES
    ('Electronics', 'Phones, laptops and accessories'),
    ('Books', 'Printed books and e-books'),
    ('Home', 'Furniture, kitchen and decoration'),
    ('Clothing', 'Clothes, shoes and bags'),
    ('Sports', 'Fitness, outdoor and cycling'),
    ('Toys', 'Toys and games for all ages');

INSERT INTO customers (name, email, phone, city, created_at)
SELECT ARRAY['Alice', 'Bob', 'Carol', 'David', 'Emma', 'Frank', 'Grace', 'Henry', 'Ivy', 'Jack'][MOD(X - 1, 10) + 1]
           || ' ' || ARRAY['Smith', 'Chen', 'Garcia', 'Müller', 'Tanaka'][MOD((X - 1) / 10, 5) + 1],
       LOWER(ARRAY['alice', 'bob', 'carol', 'david', 'emma', 'frank', 'grace', 'henry', 'ivy', 'jack'][MOD(X - 1, 10) + 1])
           || '.' || X || '@example.com',
       '+1-555-' || LPAD(CAST(MOD(X * 7919, 10000) AS VARCHAR), 4, '0'),
       ARRAY['New York', 'London', 'Berlin', 'Tokyo', 'Shanghai', 'Sydney', 'Paris', 'Toronto'][MOD(X * 3, 8) + 1],
       DATEADD('DAY', -X * 7, TIMESTAMP '2026-09-30 09:00:00')
FROM SYSTEM_RANGE(1, 50);

INSERT INTO products (category_id, sku, name, price, stock, description, created_at)
SELECT MOD(X - 1, 6) + 1,
       'SKU-' || LPAD(CAST(X AS VARCHAR), 5, '0'),
       ARRAY['Smart', 'Classic', 'Compact', 'Premium', 'Eco'][MOD(X - 1, 5) + 1] || ' '
           || ARRAY['Phone', 'Novel', 'Lamp', 'Jacket', 'Bike', 'Puzzle'][MOD(X - 1, 6) + 1] || ' ' || X,
       CAST(MOD(X * 37, 900) + 9.99 AS DECIMAL(10,2)),
       MOD(X * 13, 200),
       'Product ' || X || ' of the example shop. ' || REPEAT('Long description for a CLOB column. ', 5),
       DATEADD('DAY', -X * 11, TIMESTAMP '2026-09-30 10:00:00')
FROM SYSTEM_RANGE(1, 30);

INSERT INTO orders (order_no, customer_id, status, created_at)
SELECT 'SO-2026-' || LPAD(CAST(X AS VARCHAR), 5, '0'),
       MOD(X * 7, 50) + 1,
       ARRAY['NEW', 'PAID', 'SHIPPED', 'DELIVERED', 'CANCELLED'][MOD(X, 5) + 1],
       DATEADD('HOUR', -X * 13, TIMESTAMP '2026-09-30 12:00:00')
FROM SYSTEM_RANGE(1, 300);

INSERT INTO order_items (order_id, product_id, quantity, unit_price)
SELECT o.X, MOD(o.X * 3 + k.X * 7, 30) + 1, MOD(o.X + k.X, 4) + 1, p.price
FROM SYSTEM_RANGE(1, 300) o
JOIN SYSTEM_RANGE(1, 3) k ON k.X <= MOD(o.X, 3) + 1
JOIN products p ON p.id = MOD(o.X * 3 + k.X * 7, 30) + 1;

UPDATE orders o SET total = (SELECT SUM(i.quantity * i.unit_price) FROM order_items i WHERE i.order_id = o.id),
    paid_at = CASE WHEN o.status IN ('PAID', 'SHIPPED', 'DELIVERED') THEN DATEADD('MINUTE', 30, o.created_at) END;

UPDATE customers c SET vip = TRUE WHERE (SELECT COUNT(*) FROM orders o WHERE o.customer_id = c.id) > 5;
