-- Client registrations for MCP servers' authorization servers: one row per server, shared by every
-- instance of the application. Tokens live in Spring Security's own oauth2_authorized_client table
-- (org/springframework/security/oauth2/client/oauth2-client-schema.sql); create both.
CREATE TABLE acp_mcp_client_registration (
  registration_id varchar(100) NOT NULL,
  resource_id varchar(1000) DEFAULT NULL,
  client_id varchar(200) NOT NULL,
  client_secret varchar(1000) DEFAULT NULL,
  client_authentication_method varchar(100) NOT NULL,
  authorization_grant_type varchar(100) NOT NULL,
  redirect_uri varchar(1000) DEFAULT NULL,
  scopes varchar(1000) DEFAULT NULL,
  client_name varchar(200) DEFAULT NULL,
  authorization_uri varchar(1000) DEFAULT NULL,
  token_uri varchar(1000) NOT NULL,
  issuer_uri varchar(1000) DEFAULT NULL,
  jwk_set_uri varchar(1000) DEFAULT NULL,
  created_at timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
  PRIMARY KEY (registration_id)
);
