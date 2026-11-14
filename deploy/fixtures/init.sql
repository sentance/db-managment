CREATE TABLE orders (id bigserial PRIMARY KEY, description text NOT NULL);
INSERT INTO orders (description) VALUES ('first order'), ('second order'), ('third order');
