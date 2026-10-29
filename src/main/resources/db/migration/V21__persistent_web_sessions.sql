CREATE TABLE rbgs.spring_session (
    primary_id CHAR(36) NOT NULL PRIMARY KEY,
    session_id CHAR(36) NOT NULL,
    creation_time BIGINT NOT NULL,
    last_access_time BIGINT NOT NULL,
    max_inactive_interval INT NOT NULL,
    expiry_time BIGINT NOT NULL,
    principal_name VARCHAR(100)
);

CREATE UNIQUE INDEX spring_session_ix1 ON rbgs.spring_session (session_id);
CREATE INDEX spring_session_ix2 ON rbgs.spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON rbgs.spring_session (principal_name);

CREATE TABLE rbgs.spring_session_attributes (
    session_primary_id CHAR(36) NOT NULL REFERENCES rbgs.spring_session(primary_id) ON DELETE CASCADE,
    attribute_name VARCHAR(200) NOT NULL,
    attribute_bytes BYTEA NOT NULL,
    PRIMARY KEY (session_primary_id, attribute_name)
);
